"""生成与分配（同一页面两步）。

生成：预演（只算起止号与号段，记下 PI 当前最大号，不落库）→ 凭预演令牌生成。
同一 PI 同一时刻只有一个生成任务（sn_gen_job.running_pi 唯一）；生成在一个事务内完成，
失败整批回滚；遇到与本 PI 已有号（含转入号）相同的完整 SN 立即停止，不跳号。

只能以 PI 为单位生成（不提供按单张订单生成）：按 PI 预演会给该 PI 的每张订单各记一份接续的预演；按 PI 生成把这些
订单放进同一个事务依次生成（每张一个任务，同一个生成批次 gen_batch），任何一张失败整张 PI 回滚。
有订单的生产组织未建档时整张 PI 不能生成。
单次数量不超过 SN_GEN_MAX_PER_RUN；页面默认单次上限 SN_GEN_DEFAULT_LIMIT（可解除）。

进度与取消按批次：进度 = 整批已写入枚数 / 总数；生成中可请求取消，生成线程看到后整批回滚，任务记为 CANCELED，
取消请求本身留痕。

撤销生成（点错了重来）：按批次整次撤销，只能撤销这张 PI 流水排在最后的一次生成，且这次的号全部仍待分配；
号与查重护栏一并删除，PI 最大号、各订单已生成数退回，额度恢复，重新预演生成的号与撤销前相同。

分配：按 PI 一次分完，各订单的待分配号分到各自订单的生产组织；按单据分配也只能整单分配。
"""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import timedelta
from typing import NoReturn

import pymysql
import structlog

from app.core import encoding as codec
from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError
from app.core.security import CurrentUser
from app.db.session import ER_LOCK_NOWAIT, db, error_no, is_duplicate, marks
from app.services import audit, factories, orders, rules, sn_items

log = structlog.get_logger()

CHUNK = 1000
RUNNING = "RUNNING"
SUCCESS = "SUCCESS"
FAILED = "FAILED"
REVOKED = "REVOKED"
CANCELED = "CANCELED"

JOB_FIELDS = (
    "id",
    "bill_no",
    "pi_no",
    "gen_batch",
    "factory_code",
    "customer_code",
    "rule_version_id",
    "qty",
    "start_seq_dec",
    "end_seq_dec",
    "start_sn",
    "end_sn",
    "status",
    "done_qty",
    "error_code",
    "error_msg",
    "running_pi",
    "preview_token",
    "segments_json",
    "created_by",
    "cancel_by",
    "cancel_at",
    "created_at",
    "finished_at",
)
ALLOCATION_FIELDS = ("id", "bill_no", "pi_no", "factory_code", "qty", "start_sn", "end_sn", "created_by", "created_at")

_INSERT_ITEM = (
    "INSERT INTO sn_item(sn, pi_no, customer_code, material_code, factory_code, bill_no, rule_version_id, "
    "seq_pi_no, seq_dec, status, created_at, updated_at) "
    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
)

#: 大批量生成走后台线程
executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="sn-gen")


def job_json(j: dict | None) -> dict | None:
    return util.jrow(j, JOB_FIELDS)


# ================================================================== 上下文


def counter(pi: str) -> dict:
    c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s", pi)
    if c is None:
        return {
            "pi_no": pi,
            "last_seq_dec": 0,
            "generated_qty": 0,
            "start_locked": False,
        }
    return {
        "pi_no": pi,
        "last_seq_dec": c["last_seq_dec"],
        "generated_qty": c["generated_qty"],
        "start_locked": bool(c["start_locked"]),
    }


@dataclass(frozen=True)
class NextStart:
    """下一次生成的起点：本 PI 自己的最大号，与本 PI 下「流水不归属本 PI」的号（转入的号）
    按当前规则解析出的最大流水，两者取大再加 1。连续转入时等于接着排；不连续时越过中间未用的号。"""

    own_last: int
    foreign_seq: int | None = None
    foreign_sn: str | None = None

    @property
    def base(self) -> int:
        return self.own_last if self.foreign_seq is None else max(self.own_last, self.foreign_seq)

    @property
    def start(self) -> int:
        return self.base + 1

    @property
    def adjusted(self) -> bool:
        return self.foreign_seq is not None and self.foreign_seq > self.own_last

    def as_json(self, spec: codec.Spec | None) -> dict:
        sn = None
        if spec is not None and self.start <= codec.max_seq(spec):
            sn = codec.format_sn(self.start, spec)
        return {
            "own_last_seq": self.own_last,
            "start_seq": self.start,
            "start_sn": sn,
            "adjusted": self.adjusted,
            "by_sn": self.foreign_sn if self.adjusted else None,
            "by_seq": self.foreign_seq if self.adjusted else None,
        }


def max_parsed(sns, spec: codec.Spec) -> tuple[int, str] | None:
    """按规则解析一批完整 SN，返回最大流水及其 SN；都解析不出返回 None（格式不同的号将来不会重复）。"""
    best: tuple[int, str] | None = None
    for sn in sns:
        seq = codec.parse(sn, spec)
        if seq is not None and (best is None or seq > best[0]):
            best = (seq, sn)
    return best


def foreign_max(pi: str, spec: codec.Spec) -> tuple[int, str] | None:
    """本 PI 下流水不归属本 PI 的号（转入的号）按当前规则的最大流水。"""
    sns = db.column(
        "SELECT sn FROM sn_item WHERE pi_no = %s AND seq_pi_no <> %s AND CHAR_LENGTH(sn) = %s",
        pi,
        pi,
        len(spec.prefix) + spec.seq_len + len(spec.suffix),
    )
    return max_parsed(sns, spec)


def next_start(pi: str, own_last: int, spec: codec.Spec) -> NextStart:
    hit = foreign_max(pi, spec)
    return NextStart(own_last) if hit is None else NextStart(own_last, hit[0], hit[1])


def _bill_gen(bill_no: str, lock: bool = False) -> dict | None:
    return db.one("SELECT * FROM sn_bill_gen WHERE bill_no = %s" + (" FOR UPDATE" if lock else ""), bill_no)


def _pending_alloc(bill_no: str) -> int:
    return db.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'PENDING_ALLOC'", bill_no)


def context(bill_no: str) -> dict:
    b = orders.bill(bill_no)
    issues = []
    if util.blank(b.customer_code):
        issues.append(ErrorCode.ORDER_NO_CUSTOMER.name)
    if util.blank(b.pi):
        issues.append(ErrorCode.ORDER_NO_PI.name)
    if b.factory_code is None:
        issues.append(ErrorCode.FACTORY_UNMAPPED.name)
    elif b.factory_code in factories.disabled_codes():
        issues.append(ErrorCode.FACTORY_DISABLED.name)
    g = _bill_gen(b.bill_no)
    generated = 0 if g is None else g["generated_qty"]
    quota = max(0, b.total_qty - generated)
    if quota == 0:
        issues.append(ErrorCode.GEN_QUOTA_EMPTY.name)
    # 有绑定或该 PI 已选定过：规则确定；否则列出规则让用户选（默认通用规则）
    r = None if util.blank(b.pi) else rules.fixed(b.pi, b.customer_code)
    rule_options = [] if r is not None else rules.options(b.pi or None)
    if r is None and not rule_options:
        issues.append(ErrorCode.RULE_MISSING.name)
    f = factories.get(b.factory_code)
    running = None if util.blank(b.pi) else db.one("SELECT * FROM sn_gen_job WHERE running_pi = %s", b.pi)
    recent = db.all("SELECT * FROM sn_gen_job WHERE bill_no = %s ORDER BY id DESC LIMIT 20", b.bill_no)
    return {
        "bill": b.as_json(),
        "factory_name": None if f is None else f["factory_name"],
        "generated_qty": generated,
        "allocated_qty": 0 if g is None else g["allocated_qty"],
        "quota": quota,
        "pending_alloc": _pending_alloc(b.bill_no),
        "pi_summary": [] if util.blank(b.pi) else orders.pi_summary(b.factory_code, b.prd_org_name, b.pi),
        "counter": counter(b.pi),
        "next": None if r is None else _next_json(b.pi, rules.spec_of(r.version)),
        "rule": None if r is None else rules.rule_info(r),
        "rule_options": rule_options,
        "issues": issues,
        "running_job": job_json(running),
        "jobs": [job_json(j) for j in recent],
    }


def _pi_quota(pi: str) -> list[tuple[orders.Bill, int, int]]:
    """该 PI 在快照中的订单（单据号从小到大）及各自的已生成数与可生成额度。"""
    nos = orders.pi_bills(pi)
    gens = {g["bill_no"]: g for g in db.all("SELECT * FROM sn_bill_gen WHERE pi_no = %s", pi)}
    out = []
    for no in nos:
        b = orders.bill(no)
        generated = gens[no]["generated_qty"] if no in gens else 0
        out.append((b, generated, max(0, b.total_qty - generated)))
    return out


def _pending_by_bill(pi: str) -> dict[str, int]:
    rows = db.all(
        "SELECT bill_no, COUNT(*) n FROM sn_item WHERE pi_no = %s AND status = 'PENDING_ALLOC' GROUP BY bill_no", pi
    )
    return {r["bill_no"]: int(r["n"]) for r in rows}


def pi_context(pi_in: str | None, bill_no: str | None = None) -> dict:
    """按 PI 的生成上下文：PI 流水与规则、各订单的额度 / 已生成 / 已分配 / 待分配、任务与分配记录。

    只给单据时取它所属的 PI（订单页跳转用）。已生成过但已离开快照的订单也列出（额度为 0，仍可分配）。
    """
    pi = util.trim(pi_in)
    if not pi and not util.blank(bill_no):
        pi = orders.bill(bill_no).pi
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    names = {f["factory_code"]: f["factory_name"] for f in db.all("SELECT factory_code, factory_name FROM sn_factory")}
    gens = {g["bill_no"]: g for g in db.all("SELECT * FROM sn_bill_gen WHERE pi_no = %s ORDER BY bill_no", pi)}
    pending = _pending_by_bill(pi)
    disabled = factories.disabled_codes()
    bills, issues, customer = [], [], ""
    for b, generated, quota in _pi_quota(pi):
        customer = customer or b.customer_code
        g = gens.get(b.bill_no)
        if b.factory_code is None and quota > 0 and ErrorCode.FACTORY_UNMAPPED.name not in issues:
            issues.append(ErrorCode.FACTORY_UNMAPPED.name)
        if b.factory_code in disabled and quota > 0 and ErrorCode.FACTORY_DISABLED.name not in issues:
            issues.append(ErrorCode.FACTORY_DISABLED.name)
        bills.append(
            {
                "bill_no": b.bill_no,
                "po": b.po,
                "customer_code": b.customer_code,
                "prd_org_name": b.prd_org_name,
                "factory_code": b.factory_code,
                "factory_name": names.get(b.factory_code or ""),
                "factory_disabled": b.factory_code in disabled,
                "in_snapshot": True,
                "total_qty": b.total_qty,
                "generated_qty": generated,
                "allocated_qty": 0 if g is None else g["allocated_qty"],
                "pending_alloc": pending.get(b.bill_no, 0),
                "quota": quota,
                "lines": [orders.prd_mo_json(x) for x in b.lines],
            }
        )
    seen = {x["bill_no"] for x in bills}
    for no, g in gens.items():
        if no in seen:
            continue
        customer = customer or g["customer_code"]
        bills.append(
            {
                "bill_no": no,
                "po": "",
                "customer_code": g["customer_code"],
                "prd_org_name": names.get(g["factory_code"], ""),
                "factory_code": g["factory_code"],
                "factory_name": names.get(g["factory_code"]),
                "factory_disabled": g["factory_code"] in disabled,
                "in_snapshot": False,
                "total_qty": g["generated_qty"],
                "generated_qty": g["generated_qty"],
                "allocated_qty": g["allocated_qty"],
                "pending_alloc": pending.get(no, 0),
                "quota": 0,
                "lines": [],
            }
        )
    if not bills:
        raise biz(ErrorCode.ORDER_NOT_FOUND, bill_no=pi)
    quota = sum(x["quota"] for x in bills)
    if quota == 0:
        issues.append(ErrorCode.GEN_QUOTA_EMPTY.name)
    r = rules.fixed(pi, customer)
    # 选项总是给出：未绑定时从中选；按客户绑定时可为本 PI 单独选一条。没有模板时也能手动设置，不算缺规则
    rule_options = rules.options(pi, counter(pi)["last_seq_dec"] + 1)
    running = db.one("SELECT * FROM sn_gen_job WHERE running_pi = %s", pi)
    recent = db.all("SELECT * FROM sn_gen_job WHERE pi_no = %s ORDER BY id DESC LIMIT 20", pi)
    revocable = None if running is not None else _revocable_batch(pi)
    rb = None if revocable is None else revocable["batch"]
    return {
        "pi_no": pi,
        "customer_code": customer,
        "bills": bills,
        "total_qty": sum(x["total_qty"] for x in bills),
        "generated_qty": sum(x["generated_qty"] for x in bills),
        "allocated_qty": sum(x["allocated_qty"] for x in bills),
        "pending_alloc": sum(x["pending_alloc"] for x in bills),
        "quota": quota,
        "counter": counter(pi),
        "next": None if r is None else _next_json(pi, rules.spec_of(r.version)),
        "rule": None if r is None else rules.rule_info(r),
        "rule_options": rule_options,
        "issues": issues,
        "running_job": job_json(running),
        "running_batch": None if running is None else batch_progress(running["gen_batch"]),
        "revocable_batch": revocable,
        "limits": limits(),
        "jobs": [{**job_json(j), "revocable": rb is not None and j["gen_batch"] == rb} for j in recent],
        "allocations": allocations([x["bill_no"] for x in bills]),
    }


def limits() -> dict:
    """生成弹窗的数量限制：default = 默认单次上限（可解除），max = 单次硬上限。"""
    return {"default": settings.gen_default_limit, "max": settings.gen_max_per_run}


def _next_json(pi: str, spec: codec.Spec) -> dict:
    return next_start(pi, counter(pi)["last_seq_dec"], spec).as_json(spec)


# ================================================================== 预演


def _check_max(qty: int) -> None:
    mx = settings.gen_max_per_run
    if qty > mx:
        raise biz(ErrorCode.GEN_QTY_OVER_MAX, qty=qty, max=mx)


def _check_capacity(end: int, spec: codec.Spec) -> None:
    mx = codec.max_seq(spec)
    if end > mx:
        raise biz(ErrorCode.GEN_RULE_EXHAUSTED, max=mx, end=end)


def segments_json(segs: list[sn_items.Segment]) -> str:
    return json.dumps([s.as_camel() for s in segs], ensure_ascii=False, separators=(",", ":"))


def _save_preview(
    cu: CurrentUser,
    b: orders.Bill,
    generated: int,
    qty: int,
    start: int,
    base: int,
    r: rules.Resolved,
    spec: codec.Spec,
    manual_json: str | None,
) -> dict:
    """记下一张订单的预演（不落 SN）：base 为生成时该 PI 计数器应有的最大号。"""
    end = start + qty - 1
    segs = sn_items.split(b.lines, generated, qty, start, spec)
    now = util.now()
    token = util.token_hex()
    start_sn, end_sn = codec.format_sn(start, spec), codec.format_sn(end, spec)
    expires = now + timedelta(minutes=settings.preview_ttl_minutes)
    db.exec(
        "INSERT INTO sn_gen_preview(token, bill_no, pi_no, factory_code, qty, start_seq_dec, end_seq_dec, start_sn, "
        "end_sn, rule_version_id, rule_id, manual_json, base_last_seq, segments_json, created_by, created_at, "
        "expires_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        token,
        b.bill_no,
        b.pi,
        b.factory_code,
        qty,
        start,
        end,
        start_sn,
        end_sn,
        r.version["id"],
        r.rule["id"],
        manual_json,
        base,
        segments_json(segs),
        cu.emp_no,
        now,
        expires,
    )
    return {
        "token": token,
        "bill_no": b.bill_no,
        "pi_no": b.pi,
        "factory_code": b.factory_code,
        "qty": qty,
        "start_seq": start,
        "end_seq": end,
        "start_sn": start_sn,
        "end_sn": end_sn,
        "base_last_seq": base,
        "segments": [s.as_json() for s in segs],
        "expires_at": util.fmt(expires),
    }


def pi_preview(
    cu: CurrentUser,
    pi_in: str | None,
    qty_in: int | None,
    rule_id: int | None = None,
    manual: rules.ManualIn | None = None,
) -> dict:
    """按 PI 预演：数量按单据号顺序依次占用各订单的额度，号段在 PI 的一套流水上接续。

    规则未确定时用所选模板（rule_id）或生成页手动设置的参数（manual）；沿用派生版与手动规则在生成成功时才落库。

    每张订单各记一份预演（base 为前一张生成后的最大号），凭这些令牌调用 generate_pi 在一个事务里一次生成。
    """
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    qty = 0 if qty_in is None else qty_in
    if qty <= 0:
        raise biz(ErrorCode.GEN_QTY_INVALID)
    todo = [(b, gen, quota) for b, gen, quota in _pi_quota(pi) if quota > 0]
    if not todo:
        raise biz(ErrorCode.GEN_PI_QUOTA_EMPTY, pi=pi)
    # 有订单的生产组织还没建档为工厂：整张 PI 都不能生成，建档后再一起生成
    unmapped = list(dict.fromkeys(b.prd_org_name for b, _, _ in todo if b.factory_code is None))
    if unmapped:
        raise biz(ErrorCode.FACTORY_UNMAPPED, org="、".join(unmapped))
    # 生产组织对应的工厂已停用：同样整张 PI 不能生成
    for code in dict.fromkeys(b.factory_code for b, _, _ in todo):
        factories.must_be_enabled(code)
    total = sum(q for _, _, q in todo)
    if qty > total:
        raise biz(ErrorCode.GEN_QUOTA_EXCEEDED, qty=qty, quota=total)
    _check_max(qty)
    r = rules.for_generation(pi, todo[0][0].customer_code, rule_id, manual)
    spec = rules.spec_of(r.version)
    mj = None if r.plan != rules.PLAN_NEW else _manual_json(manual)
    c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s", pi)
    base = 0 if c is None else c["last_seq_dec"]
    ns = next_start(pi, base, spec)
    start = ns.start
    _check_capacity(start + qty - 1, spec)
    items = []
    cur, left, prev = start, qty, base
    for b, gen, quota in todo:
        if left == 0:
            break
        n = min(quota, left)
        items.append(_save_preview(cu, b, gen, n, cur, prev, r, spec, mj))
        prev, cur, left = cur + n - 1, cur + n, left - n
    return {
        "pi_no": pi,
        "qty": qty,
        "start_seq": start,
        "end_seq": start + qty - 1,
        "start_sn": items[0]["start_sn"],
        "end_sn": items[-1]["end_sn"],
        "base_last_seq": base,
        "next": ns.as_json(spec),
        "rule": rules.rule_info(r, start),
        "items": items,
        "expires_at": items[0]["expires_at"],
    }


def _manual_json(m: rules.ManualIn) -> str:
    return json.dumps(
        {"prefix": util.trim(m.prefix), "seq_len": m.seq_len, "suffix": util.trim(m.suffix)},
        ensure_ascii=False,
        separators=(",", ":"),
    )


def _manual_of(p: dict) -> rules.ManualIn | None:
    if not p.get("manual_json"):
        return None
    d = json.loads(p["manual_json"])
    return rules.ManualIn(d.get("prefix"), d.get("seq_len"), d.get("suffix"))


def rule_plan(pi_in: str | None, rule_id: int | None, manual: rules.ManualIn | None) -> dict:
    """生成页选模板 / 手动设置时的即时判断：本 PI 将用哪一版、第一个号；参数不合法时报错。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    if manual is not None:
        r = rules.manual_plan(pi, manual)
    else:
        r = rules.plan_for(pi, rules.pickable_rule(rule_id), rules.SRC_PICKED)
    spec = rules.spec_of(r.version)
    ns = next_start(pi, _last_seq(pi), spec)
    return {"rule": rules.rule_info(r, ns.start), "next": ns.as_json(spec)}


# ================================================================== 生成


def generate_pi(cu: CurrentUser, pi_in: str | None, items: list[dict] | None) -> dict:
    """凭按 PI 预演的各订单令牌，一次生成整张 PI：所有订单在同一个事务里依次生成，任何一张失败整张 PI 回滚。

    每张订单仍是一个任务、仍走与按单据生成相同的校验与写号（_generate_in_tx）；
    第一张的任务占住 running_pi，整张 PI 结束前别的生成进不来。
    """
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    if not items:
        raise biz(ErrorCode.GEN_PREVIEW_REQUIRED)
    ps = []
    for it in items:
        p = _usable_preview(cu, it.get("preview_token"), it.get("bill_no"), it.get("qty"))
        # 须是同一次按 PI 预演、按原顺序：号段首尾相接
        prev = ps[-1] if ps else None
        if (
            p["pi_no"] != pi
            or (prev is not None and p["base_last_seq"] != prev["end_seq_dec"])
            or (prev is not None and _plan_key(p) != _plan_key(prev))
            or any(x["bill_no"] == p["bill_no"] for x in ps)
        ):
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
        ps.append(p)
    _check_base(ps[0], _last_seq(pi))
    batch = _new_batch()
    try:
        with db.tx():
            ids = [_insert_job(cu, p, pi if i == 0 else None, batch) for i, p in enumerate(ps)]
            now = util.now()
            for p in ps:
                db.exec("UPDATE sn_gen_preview SET used_at = %s WHERE token = %s", now, p["token"])
    except pymysql.err.IntegrityError as e:
        _raise_job_conflict(e, pi)
    work = list(zip(ids, ps, strict=True))
    if sum(p["qty"] for p in ps) <= settings.gen_sync_threshold:
        _run_jobs(cu, work, True)
    else:
        executor.submit(_run_jobs, cu, work, False)
    rows = db.all(f"SELECT * FROM sn_gen_job WHERE id IN ({marks(len(ids))}) ORDER BY id", *ids)
    return {"pi_no": pi, "batch": batch, "qty": sum(p["qty"] for p in ps), "jobs": [job_json(j) for j in rows]}


def _plan_key(p: dict) -> tuple:
    """同一次预演的各订单用同一条规则、同一版（或同一份手动参数）。"""
    return p["rule_version_id"], p["rule_id"], p["manual_json"]


def _new_batch() -> str:
    """生成批次号：一次生成（整张 PI 的各订单）共用。"""
    return util.next_id("G") + util.token_hex()[:6]


def _usable_preview(cu: CurrentUser, token: str | None, bill_no: str | None, qty: int | None) -> dict:
    """本人、未用、未过期，且订单 / 数量与预演一致的预演。"""
    p = None if util.blank(token) else db.one("SELECT * FROM sn_gen_preview WHERE token = %s", token.strip())
    if p is None or p["created_by"] != cu.emp_no:
        raise biz(ErrorCode.GEN_PREVIEW_REQUIRED)
    if p["used_at"] is not None:
        raise biz(ErrorCode.GEN_PREVIEW_USED)
    if util.now() > p["expires_at"]:
        raise biz(ErrorCode.GEN_PREVIEW_EXPIRED)
    if p["bill_no"] != util.trim(bill_no) or qty is None or qty != p["qty"]:
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    return p


def _last_seq(pi: str) -> int:
    c = db.one("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi)
    return 0 if c is None else c["last_seq_dec"]


def _check_base(p: dict, last: int) -> None:
    if last != p["base_last_seq"]:
        raise biz(ErrorCode.GEN_PREVIEW_STALE, pi=p["pi_no"], expected=p["base_last_seq"], actual=last)


def _insert_job(cu: CurrentUser, p: dict, running_pi: str | None, batch: str) -> int:
    return db.insert(
        "INSERT INTO sn_gen_job(bill_no, pi_no, gen_batch, factory_code, customer_code, rule_version_id, qty, "
        "start_seq_dec, end_seq_dec, start_sn, end_sn, status, done_qty, running_pi, preview_token, segments_json, "
        "created_by, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,0,%s,%s,%s,%s,%s)",
        p["bill_no"],
        p["pi_no"],
        batch,
        p["factory_code"],
        orders.bill(p["bill_no"]).customer_code,
        p["rule_version_id"],
        p["qty"],
        p["start_seq_dec"],
        p["end_seq_dec"],
        p["start_sn"],
        p["end_sn"],
        RUNNING,
        running_pi,
        p["token"],
        p["segments_json"],
        cu.emp_no,
        util.now(),
    )


def _raise_job_conflict(e: pymysql.err.IntegrityError, pi: str) -> NoReturn:
    if not is_duplicate(e):
        raise e
    if "ux_job_preview" in str(e):
        raise biz(ErrorCode.GEN_PREVIEW_USED) from None
    raise biz(ErrorCode.GEN_BUSY, pi=pi) from None


def job(job_id: int) -> dict:
    j = db.one("SELECT * FROM sn_gen_job WHERE id = %s", job_id)
    if j is None:
        raise biz(ErrorCode.GEN_JOB_NOT_FOUND)
    return job_json(j)


def jobs(bill_no: str | None, pi: str | None, limit: int) -> list[dict]:
    sql = "SELECT * FROM sn_gen_job WHERE 1=1"
    args: list = []
    if not util.blank(bill_no):
        sql += " AND bill_no = %s"
        args.append(util.trim(bill_no))
    if not util.blank(pi):
        sql += " AND pi_no = %s"
        args.append(util.trim(pi))
    sql += f" ORDER BY id DESC LIMIT {min(max(limit, 1), 200)}"
    return [job_json(j) for j in db.all(sql, *args)]


def _run_jobs(cu: CurrentUser, work: list[tuple[int, dict]], rethrow: bool) -> None:
    """按顺序执行一个或多个生成任务（同一 PI 的各订单），全部在一个事务里：任何一张失败，全部回滚、全部标为失败。"""
    current = work[0]
    try:
        # 号与任务状态同一事务提交：不会出现「号已写入、任务仍是 RUNNING」
        with db.tx():
            version = None
            for item in work:
                current = item
                # 同一次生成的各订单用同一版：第一张确定（必要时落库）后，后面几张直接沿用
                version = _generate_in_tx(cu, *item, version)
            for job_id, p in work:
                # 当前读：取消请求已提交就整批回滚；取消请求晚到时会等本事务提交，再看到「已结束」
                # 实际所用版本在这里才写回任务：生成过程中任务行要留给独立事务写进度
                n = db.exec(
                    "UPDATE sn_gen_job SET status = %s, done_qty = %s, rule_version_id = %s, running_pi = NULL, "
                    "finished_at = %s WHERE id = %s AND status = %s AND cancel_at IS NULL",
                    SUCCESS,
                    p["qty"],
                    version["id"],
                    util.now(),
                    job_id,
                    RUNNING,
                )
                if n != 1:
                    if _cancel_requested(job_id):
                        raise biz(ErrorCode.GEN_CANCELED)
                    raise biz(ErrorCode.CONFLICT_RETRY)
        for job_id, p in work:
            log.info("gen_done", job=job_id, pi=p["pi_no"], qty=p["qty"])
    except Exception as e:  # noqa: BLE001
        code = e.code if isinstance(e, BizError) else "INTERNAL"
        canceled = code == ErrorCode.GEN_CANCELED.name
        msg = str(e) or type(e).__name__
        if len(work) > 1 and not canceled:
            msg = f"{current[1]['bill_no']}: {msg}"
        msg = util.cut(msg, 1000)
        # 整批已回滚（号、查重护栏、计数器、订单已生成数、规则使用标记都没写入）；任务只记结果
        for job_id, p in work:
            try:
                db.exec(
                    "UPDATE sn_gen_job SET status = %s, done_qty = 0, error_code = %s, error_msg = %s, "
                    "running_pi = NULL, finished_at = %s WHERE id = %s AND status = %s",
                    CANCELED if canceled else FAILED,
                    code,
                    msg,
                    util.now(),
                    job_id,
                    RUNNING,
                )
            except Exception:  # noqa: BLE001
                log.exception("gen_job_status_update_failed", job=job_id)
            # 同步执行时由全局异常处理记失败痕迹；后台任务在这里记
            if not rethrow:
                audit.record_isolated(
                    cu,
                    audit.entry(audit.SN_GENERATE)
                    .fail(msg)
                    .pi(p["pi_no"])
                    .factory(p["factory_code"])
                    .bill(p["bill_no"])
                    .count(p["qty"])
                    .range(p["start_sn"], p["end_sn"])
                    .info(f"job={job_id}"),
                )
        if not isinstance(e, BizError):
            log.error("gen_failed", job=current[0], pi=current[1]["pi_no"], exc_info=e)
        if rethrow:
            raise


def _generate_in_tx(cu: CurrentUser, job_id: int, p: dict, version: dict | None = None) -> dict:
    now = util.now()
    pi = p["pi_no"]
    # 1) 锁 PI 计数器（一张 PI 一行），核对预演时的最大号
    db.exec(
        "INSERT INTO sn_pi_counter(pi_no, last_seq_dec, generated_qty, start_locked, updated_at) "
        "VALUES (%s,0,0,0,%s) ON DUPLICATE KEY UPDATE pi_no = pi_no",
        pi,
        now,
    )
    c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", pi)
    # 已被启动回收标为失败的任务不再执行（回收要先拿到计数器锁，持锁期间不会再改任务状态）
    st = db.scalar("SELECT status FROM sn_gen_job WHERE id = %s", job_id)
    if st != RUNNING:
        raise biz(ErrorCode.CONFLICT_RETRY)
    if _cancel_requested(job_id):
        raise biz(ErrorCode.GEN_CANCELED)
    if c["last_seq_dec"] != p["base_last_seq"]:
        raise biz(ErrorCode.GEN_PREVIEW_STALE, pi=pi, expected=p["base_last_seq"], actual=c["last_seq_dec"])
    # 2) 订单仍在快照、额度与号段未变
    b = orders.bill(p["bill_no"])
    db.exec(
        "INSERT INTO sn_bill_gen(bill_no, pi_no, factory_code, customer_code, generated_qty, allocated_qty, "
        "updated_at) VALUES (%s,%s,%s,%s,0,0,%s) ON DUPLICATE KEY UPDATE bill_no = bill_no",
        b.bill_no,
        b.pi,
        p["factory_code"],
        b.customer_code,
        now,
    )
    g = _bill_gen(b.bill_no, lock=True)
    quota = b.total_qty - g["generated_qty"]
    if p["qty"] > quota:
        raise biz(ErrorCode.GEN_QUOTA_EXCEEDED, qty=p["qty"], quota=max(0, quota))
    # 3) 规则与版本未变：按预演时的输入重新判断，须仍是同一条规则、同一版（或同样要派生 / 新建）；
    #    确认后落库（派生版 / 手动规则）并标记已使用、归属本 PI，之后该版本只能另出新版
    source = None
    if version is None:
        r = rules.for_generation(b.pi, b.customer_code, p["rule_id"], _manual_of(p), c)
        if r.version["id"] != p["rule_version_id"] or (p["rule_id"] is not None and r.rule["id"] != p["rule_id"]):
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
        source = r.source
        version = rules.realize(cu, pi, r)
    v = version
    spec = rules.spec_of(v)
    if codec.format_sn(p["start_seq_dec"], spec) != p["start_sn"] or b.pi != pi or p["factory_code"] != b.factory_code:
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    segs = sn_items.split(b.lines, g["generated_qty"], p["qty"], p["start_seq_dec"], spec)
    if segments_json(segs) != p["segments_json"]:
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    _check_capacity(p["end_seq_dec"], spec)
    # 预演之后又有号转入本 PI：起点可能要后移，预演失效
    ns = next_start(pi, c["last_seq_dec"], spec)
    if ns.start != p["start_seq_dec"]:
        raise biz(ErrorCode.GEN_PREVIEW_STALE, pi=pi, expected=p["start_seq_dec"] - 1, actual=ns.base)
    # 4) 逐块写号：SN 全局唯一键查重，撞号时再查出是哪个号、属于哪张 PI
    done = 0
    for seg in segs:
        s = seg.start_seq
        while s <= seg.end_seq:
            e = min(seg.end_seq, s + CHUNK - 1)
            sns = [codec.format_sn(q, spec) for q in range(s, e + 1)]
            items = [
                (
                    sn,
                    pi,
                    b.customer_code,
                    seg.material_code,
                    None,
                    b.bill_no,
                    v["id"],
                    pi,
                    s + i,
                    util.PENDING_ALLOC,
                    now,
                    now,
                )
                for i, sn in enumerate(sns)
            ]
            try:
                db.exec_many(_INSERT_ITEM, items)
            except pymysql.err.IntegrityError as ex:
                if not is_duplicate(ex):
                    raise
                hit = _first_existing(sns)
                if hit is None:
                    raise biz(ErrorCode.CONFLICT_RETRY) from None
                raise biz(ErrorCode.GEN_DUPLICATE_SN, sn=hit["sn"], pi=hit["pi_no"]) from None
            done += len(sns)
            if _progress(job_id, done):
                raise biz(ErrorCode.GEN_CANCELED)
            s += CHUNK
    # 5) 推进计数器、订单已生成数；留痕
    sets = "last_seq_dec = %s, generated_qty = %s, start_locked = 1, rule_version_id = %s, updated_at = %s"
    args: list = [p["end_seq_dec"], c["generated_qty"] + p["qty"], v["id"], now]
    if source == rules.SRC_PICKED:
        # 没有绑定：记下所选模板，这张 PI 之后一直沿用（手动设置的规则已按 PI 绑定）
        sets += ", rule_id = %s"
        args.append(v["rule_id"])
    if not c["start_locked"]:
        sets += ", locked_by = %s, locked_at = %s"
        args += [cu.emp_no, now]
    db.exec(f"UPDATE sn_pi_counter SET {sets} WHERE pi_no = %s", *args, pi)
    db.exec(
        "UPDATE sn_bill_gen SET generated_qty = %s, factory_code = %s, updated_at = %s WHERE bill_no = %s",
        g["generated_qty"] + p["qty"],
        p["factory_code"],
        now,
        b.bill_no,
    )
    detail = f"job={job_id} rule_version={v['id']} rule_source={source or 'BATCH'} segments={len(segs)}"
    if ns.adjusted:
        detail += f" start_after_transferred={ns.foreign_sn}(seq {ns.foreign_seq}, own_last {ns.own_last})"
    audit.record(
        cu,
        audit.entry(audit.SN_GENERATE)
        .factory(p["factory_code"])
        .pi(pi)
        .customer(b.customer_code)
        .bill(b.bill_no)
        .range(p["start_sn"], p["end_sn"])
        .count(p["qty"])
        .status(None, util.PENDING_ALLOC)
        .info(detail),
    )
    return v


INTERRUPTED = "INTERRUPTED"


def recover_interrupted() -> int:
    """启动时回收中断的任务：进程重启时仍是 RUNNING 的任务标为失败并释放 running_pi，否则该 PI 永远「正在生成」。

    生成事务全程持有该 PI 计数器的行锁；这里对任务行与计数器都用 NOWAIT 加锁，拿不到说明生成仍在进行，跳过。
    未提交的生成事务已随进程退出回滚，所以这些任务一枚号也没有写入。
    """
    recovered = 0
    for j in db.all("SELECT id, pi_no FROM sn_gen_job WHERE status = %s", RUNNING):
        try:
            with db.new_tx():
                row = db.one("SELECT status FROM sn_gen_job WHERE id = %s FOR UPDATE NOWAIT", j["id"])
                if row is None or row["status"] != RUNNING:
                    continue
                db.all("SELECT pi_no FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE NOWAIT", j["pi_no"])
                db.exec(
                    "UPDATE sn_gen_job SET status = %s, done_qty = 0, error_code = %s, error_msg = %s, "
                    "running_pi = NULL, finished_at = %s WHERE id = %s",
                    FAILED,
                    INTERRUPTED,
                    "服务重启时任务未完成，已回滚，请重新预演后生成",
                    util.now(),
                    j["id"],
                )
                recovered += 1
        except pymysql.err.OperationalError as e:
            if error_no(e) != ER_LOCK_NOWAIT:
                raise
            log.info("gen_job_still_running", job=j["id"], pi=j["pi_no"])
    if recovered:
        log.warning("gen_jobs_recovered", count=recovered)
    return recovered


def _first_existing(sns: list[str]) -> dict | None:
    """库里（任一 PI）已存在的第一个 SN 及其所在 PI；没有返回 None。"""
    return db.one(f"SELECT sn, pi_no FROM sn_item WHERE sn IN ({marks(len(sns))}) LIMIT 1", *sns)


def _progress(job_id: int, done: int) -> bool:
    """在独立事务里写进度（页面实时可见），并返回是否已请求取消。"""
    try:
        with db.new_tx():
            db.exec("UPDATE sn_gen_job SET done_qty = %s WHERE id = %s", done, job_id)
            return db.scalar("SELECT cancel_at FROM sn_gen_job WHERE id = %s", job_id) is not None
    except Exception:  # noqa: BLE001
        log.debug("progress_update_failed", job=job_id)
        return False


def _cancel_requested(job_id: int) -> bool:
    """独立连接读取（生成事务自己的快照看不到之后提交的取消请求）。"""
    with db.new_tx():
        return db.scalar("SELECT cancel_at FROM sn_gen_job WHERE id = %s", job_id) is not None


# ================================================================== 进度 / 取消


def _batch_jobs(batch_in: str | None, lock: bool = False) -> list[dict]:
    batch = util.trim(batch_in)
    js = (
        []
        if not batch
        else db.all(
            "SELECT * FROM sn_gen_job WHERE gen_batch = %s ORDER BY id" + (" FOR UPDATE" if lock else ""), batch
        )
    )
    if not js:
        raise biz(ErrorCode.GEN_BATCH_NOT_FOUND)
    return js


def _batch_status(js: list[dict]) -> str:
    st = {j["status"] for j in js}
    for s in (RUNNING, CANCELED, FAILED, REVOKED):
        if s in st:
            return s
    return SUCCESS


def batch_progress(batch_in: str | None) -> dict:
    """一次生成（整张 PI 的各订单）的整体进度：已写入枚数 / 总数、已写到的号；不分订单、不分段。"""
    js = _batch_jobs(batch_in)
    qty = sum(j["qty"] for j in js)
    done = sum(j["done_qty"] for j in js)
    current = None
    for j in js:
        if j["done_qty"] > 0:
            spec = rules.cached_spec(j["rule_version_id"])
            if spec is not None:
                current = codec.format_sn(j["start_seq_dec"] + min(j["done_qty"], j["qty"]) - 1, spec)
    status = _batch_status(js)
    finished = [j["finished_at"] for j in js if j["finished_at"] is not None]
    err = next((j for j in js if j["error_msg"]), None)
    return {
        "batch": js[0]["gen_batch"],
        "pi_no": js[0]["pi_no"],
        "status": status,
        "qty": qty,
        "done_qty": done,
        "bills": len(js),
        "start_sn": js[0]["start_sn"],
        "end_sn": js[-1]["end_sn"],
        "current_sn": current,
        "created_by": js[0]["created_by"],
        "created_at": util.fmt(js[0]["created_at"]),
        "finished_at": util.fmt(max(finished)) if status != RUNNING and finished else None,
        "cancel_by": js[0]["cancel_by"],
        "cancel_at": util.fmt(js[0]["cancel_at"]),
        "error_code": None if err is None else err["error_code"],
        "error_msg": None if err is None else err["error_msg"],
        "items": [{"bill_no": j["bill_no"], "factory_code": j["factory_code"], "qty": j["qty"]} for j in js],
    }


def cancel(cu: CurrentUser, batch_in: str | None) -> dict:
    """请求取消正在进行的生成：生成线程在下一块写号前后看到请求，整批回滚（已写入的号、查重护栏、计数器、
    订单已生成数都不保留），任务记为 CANCELED。取消请求本身留痕。"""
    with db.tx():
        js = _batch_jobs(batch_in, lock=True)
        if not any(j["status"] == RUNNING for j in js):
            raise biz(ErrorCode.GEN_CANCEL_NOT_RUNNING)
        now = util.now()
        n = db.exec(
            "UPDATE sn_gen_job SET cancel_at = %s, cancel_by = %s WHERE gen_batch = %s AND status = %s "
            "AND cancel_at IS NULL",
            now,
            cu.emp_no,
            js[0]["gen_batch"],
            RUNNING,
        )
        if n:
            qty = sum(j["qty"] for j in js)
            done = sum(j["done_qty"] for j in js)
            audit.record(
                cu,
                audit.entry(audit.SN_GEN_CANCEL)
                .pi(js[0]["pi_no"])
                .customer(js[0]["customer_code"])
                .range(js[0]["start_sn"], js[-1]["end_sn"])
                .count(qty)
                .status(RUNNING, CANCELED)
                .info(f"batch={js[0]['gen_batch']} jobs={','.join(str(j['id']) for j in js)} done={done}/{qty}"),
            )
    log.info("gen_cancel_requested", batch=js[0]["gen_batch"], pi=js[0]["pi_no"])
    return batch_progress(js[0]["gen_batch"])


# ================================================================== 撤销生成


def _job_pending(j: dict) -> int:
    """该任务写出的号中仍待分配的枚数（任务的流水区间在本 PI 内唯一，走 ux_item_seq）。"""
    return db.count(
        "SELECT COUNT(*) FROM sn_item WHERE seq_pi_no = %s AND seq_dec BETWEEN %s AND %s "
        "AND bill_no = %s AND status = 'PENDING_ALLOC'",
        j["pi_no"],
        j["start_seq_dec"],
        j["end_seq_dec"],
        j["bill_no"],
    )


def _revocable_batch(pi: str) -> dict | None:
    """这张 PI 当前可撤销的一次生成：流水终点即 PI 最大号、各任务都成功且号全部仍待分配；没有返回 None。"""
    last = _last_seq(pi)
    if last == 0:
        return None
    j = db.one(
        "SELECT gen_batch FROM sn_gen_job WHERE pi_no = %s AND status = %s AND end_seq_dec = %s "
        "ORDER BY id DESC LIMIT 1",
        pi,
        SUCCESS,
        last,
    )
    if j is None:
        return None
    js = _batch_jobs(j["gen_batch"])
    if any(x["status"] != SUCCESS for x in js) or any(_job_pending(x) != x["qty"] for x in js):
        return None
    return {
        "batch": j["gen_batch"],
        "qty": sum(x["qty"] for x in js),
        "bills": [x["bill_no"] for x in js],
        "start_sn": js[0]["start_sn"],
        "end_sn": js[-1]["end_sn"],
        "created_by": js[0]["created_by"],
        "created_at": util.fmt(js[0]["created_at"]),
    }


def revoke(cu: CurrentUser, job_id: int, reason_in: str | None) -> dict:
    """撤销该任务所属的整次生成（兼容按任务号调用）。"""
    j = db.one("SELECT gen_batch FROM sn_gen_job WHERE id = %s", job_id)
    if j is None:
        raise biz(ErrorCode.GEN_JOB_NOT_FOUND)
    return revoke_batch(cu, j["gen_batch"], reason_in)


def revoke_batch(cu: CurrentUser, batch_in: str | None, reason_in: str | None) -> dict:
    """撤销一次生成（点错了重来），整次撤销：删除这次各订单写出的号与查重护栏，退回 PI 最大号与各订单已生成数。

    只能撤销这张 PI 流水排在最后的一次生成，且各任务都已成功、号全部仍待分配；分配出去的号可能已领取、打印，
    不能撤销。流水退回后重新生成的号与撤销前相同，号段不留空洞。规则版本的「已使用」标记不退回。
    """
    reason = util.trim(reason_in)
    if not reason:
        raise biz(ErrorCode.GEN_REVOKE_REASON_REQUIRED)
    with db.tx():
        js = _batch_jobs(batch_in)
        pi = js[0]["pi_no"]
        batch = js[0]["gen_batch"]
        # 生成事务全程持有计数器行锁：有任务在跑时直接拒绝，不去排队等锁
        if db.one("SELECT id FROM sn_gen_job WHERE running_pi = %s", pi) is not None:
            raise biz(ErrorCode.GEN_BUSY, pi=pi)
        # 加锁顺序与生成一致：先 PI 计数器，再任务，再订单已生成数（按单据号）
        c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", pi)
        js = _batch_jobs(batch, lock=True)
        if any(j["status"] != SUCCESS for j in js):
            raise biz(ErrorCode.GEN_REVOKE_NOT_SUCCESS)
        end = max(j["end_seq_dec"] for j in js)
        if c is None or c["last_seq_dec"] != end:
            raise biz(ErrorCode.GEN_REVOKE_NOT_LAST, pi=pi)
        gens = {no: _bill_gen(no, lock=True) for no in sorted({j["bill_no"] for j in js})}
        allocated = sum(j["qty"] - _job_pending(j) for j in js)
        if allocated or any(g is None for g in gens.values()):
            raise biz(ErrorCode.GEN_REVOKE_ALLOCATED, count=allocated)
        for j in js:
            s, e = j["start_seq_dec"], j["end_seq_dec"]
            n_items = db.exec(
                "DELETE FROM sn_item WHERE seq_pi_no = %s AND seq_dec BETWEEN %s AND %s "
                "AND pi_no = %s AND bill_no = %s AND status = 'PENDING_ALLOC'",
                pi,
                s,
                e,
                pi,
                j["bill_no"],
            )
            if n_items != j["qty"]:
                raise biz(ErrorCode.CONFLICT_RETRY)
        # 退回到这张 PI 其余成功生成的最大号（计数器只由生成推进）
        prev = db.scalar(
            "SELECT COALESCE(MAX(end_seq_dec), 0) FROM sn_gen_job WHERE pi_no = %s AND status = %s AND gen_batch <> %s",
            pi,
            SUCCESS,
            batch,
        )
        qty = sum(j["qty"] for j in js)
        now = util.now()
        generated = c["generated_qty"] - qty
        sets = "last_seq_dec = %s, generated_qty = %s, updated_at = %s"
        args: list = [prev, generated, now]
        if generated == 0:
            # 一枚都不剩：回到从未生成的状态（规则指定保留，可在页面解绑重选）
            sets += ", start_locked = 0, locked_by = NULL, locked_at = NULL"
        db.exec(f"UPDATE sn_pi_counter SET {sets} WHERE pi_no = %s", *args, pi)
        by_bill: dict[str, int] = {}
        for j in js:
            by_bill[j["bill_no"]] = by_bill.get(j["bill_no"], 0) + j["qty"]
        for no, n in by_bill.items():
            left = gens[no]["generated_qty"] - n
            if left == 0:
                db.exec("DELETE FROM sn_bill_gen WHERE bill_no = %s", no)
            else:
                db.exec("UPDATE sn_bill_gen SET generated_qty = %s, updated_at = %s WHERE bill_no = %s", left, now, no)
        db.exec("UPDATE sn_gen_job SET status = %s WHERE gen_batch = %s", REVOKED, batch)
        for j in js:
            audit.record(
                cu,
                audit.entry(audit.SN_REVOKE)
                .factory(j["factory_code"])
                .pi(pi)
                .customer(j["customer_code"])
                .bill(j["bill_no"])
                .range(j["start_sn"], j["end_sn"])
                .count(j["qty"])
                .status(util.PENDING_ALLOC, None)
                .why(reason)
                .info(f"batch={batch} job={j['id']} last_seq {end} -> {prev}"),
            )
    log.info("gen_revoked", batch=batch, pi=pi, qty=qty)
    return {
        "batch": batch,
        "pi_no": pi,
        "qty": qty,
        "jobs": [j["id"] for j in js],
        "bills": list(by_bill),
        "start_sn": js[0]["start_sn"],
        "end_sn": js[-1]["end_sn"],
        "last_seq_dec": prev,
    }


# ================================================================== 分配


def allocate(cu: CurrentUser, bill_no: str | None, factory_code: str | None = None) -> dict:
    """整单分配：该订单全部待分配的号分到订单的生产组织（同一订单的各物料一起分，不拆数量）。"""
    no = util.trim(bill_no)
    with db.tx():
        g = _bill_gen(no, lock=True)
        if g is None:
            raise biz(ErrorCode.ALLOC_NOTHING, bill_no=no)
        if not util.blank(factory_code) and g["factory_code"] != factory_code.strip():
            raise biz(ErrorCode.ALLOC_FACTORY_MISMATCH, factory=g["factory_code"])
        factories.must_be_enabled(g["factory_code"])
        qty = _pending_alloc(no)
        if qty == 0:
            raise biz(ErrorCode.ALLOC_NOTHING, bill_no=no)
        first = db.scalar(
            "SELECT sn FROM sn_item WHERE bill_no = %s AND status = 'PENDING_ALLOC' ORDER BY seq_dec LIMIT 1", no
        )
        last = db.scalar(
            "SELECT sn FROM sn_item WHERE bill_no = %s AND status = 'PENDING_ALLOC' ORDER BY seq_dec LIMIT %s, 1",
            no,
            qty - 1,
        )
        now = util.now()
        n = db.exec(
            "UPDATE sn_item SET status = 'TO_ACQUIRE', factory_code = %s, allocated_at = %s, updated_at = %s "
            "WHERE bill_no = %s AND status = 'PENDING_ALLOC' ORDER BY seq_dec LIMIT %s",
            g["factory_code"],
            now,
            now,
            no,
            qty,
        )
        if n != qty:
            raise biz(ErrorCode.CONFLICT_RETRY)
        db.exec(
            "UPDATE sn_bill_gen SET allocated_qty = %s, updated_at = %s WHERE bill_no = %s",
            g["allocated_qty"] + qty,
            now,
            no,
        )
        db.insert(
            "INSERT INTO sn_allocation(bill_no, pi_no, factory_code, qty, start_sn, end_sn, created_by, "
            "created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
            no,
            g["pi_no"],
            g["factory_code"],
            qty,
            first,
            last,
            cu.emp_no,
            now,
        )
        audit.record(
            cu,
            audit.entry(audit.SN_ALLOCATE)
            .factory(g["factory_code"])
            .pi(g["pi_no"])
            .customer(g["customer_code"])
            .bill(no)
            .range(first, last)
            .count(qty)
            .status(util.PENDING_ALLOC, util.TO_ACQUIRE),
        )
        return {
            "bill_no": no,
            "pi_no": g["pi_no"],
            "factory_code": g["factory_code"],
            "qty": qty,
            "start_sn": first,
            "end_sn": last,
            "pending_left": 0,
        }


def allocate_pi(cu: CurrentUser, pi_in: str | None) -> dict:
    """整张 PI 分配：各订单的待分配号全部分到各自订单的生产组织，同一事务。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    with db.tx():
        nos = [no for no, n in sorted(_pending_by_bill(pi).items()) if n > 0]
        if not nos:
            raise biz(ErrorCode.ALLOC_PI_NOTHING, pi=pi)
        items = [allocate(cu, no) for no in nos]
    return {"pi_no": pi, "qty": sum(x["qty"] for x in items), "items": items}


def allocations(bill_nos: list[str]) -> list[dict]:
    nos = [n for n in (util.trim(x) for x in bill_nos) if n]
    if not nos:
        return []
    rows = db.all(f"SELECT * FROM sn_allocation WHERE bill_no IN ({marks(len(nos))}) ORDER BY id DESC LIMIT 100", *nos)
    return [util.jrow(a, ALLOCATION_FIELDS) for a in rows]
