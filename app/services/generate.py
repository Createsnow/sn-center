"""生成与分配（同一页面两步）。

生成：预演（只算起止号与号段，记下 PI 当前最大号，不落库）→ 凭预演令牌生成。
同一 PI 同一时刻只有一个生成任务（sn_gen_job.running_pi 唯一）；生成在一个事务内完成，
失败整批回滚；遇到与本 PI 已有号（含转入号）相同的完整 SN 立即停止，不跳号。

分配：把该订单的待分配号整单分到订单的生产组织，状态变为待领取。

页面以 PI 为单位：按 PI 预演会给该 PI 的每张订单各记一份接续的预演；按 PI 生成把这些订单放进同一个事务依次生成
（每张一个任务，校验与写号同按单据生成），任何一张失败整张 PI 回滚；按 PI 分配同样在一个事务里分到各订单的生产组织。
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

JOB_FIELDS = (
    "id",
    "bill_no",
    "pi_no",
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
    "created_at",
    "finished_at",
)
ALLOCATION_FIELDS = ("id", "bill_no", "pi_no", "factory_code", "qty", "start_sn", "end_sn", "created_by", "created_at")

_INSERT_ITEM = (
    "INSERT INTO sn_item(gen_month, sn, pi_no, customer_code, material_code, factory_code, bill_no, rule_version_id, "
    "seq_pi_no, seq_dec, status, source, job_id, created_at, updated_at) "
    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
)
_INSERT_KEY = "INSERT INTO sn_key(pi_no, sn, seq_pi_no, seq_dec) VALUES (%s,%s,%s,%s)"

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
            "imported_qty": 0,
            "start_seq_dec": None,
            "start_locked": False,
        }
    return {
        "pi_no": pi,
        "last_seq_dec": c["last_seq_dec"],
        "generated_qty": c["generated_qty"],
        "imported_qty": c["imported_qty"],
        "start_seq_dec": c["start_seq_dec"],
        "start_locked": bool(c["start_locked"]),
    }


@dataclass(frozen=True)
class NextStart:
    """下一次生成的起点：本 PI 自己的最大号，与本 PI 下「流水不归属本 PI」的号（转入、未解析的历史导入）
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
    """本 PI 下流水不归属本 PI 的号（转入的号、未解析出流水的历史导入）按当前规则的最大流水。"""
    sns = db.column(
        "SELECT sn FROM sn_key WHERE pi_no = %s AND (seq_pi_no IS NULL OR seq_pi_no <> %s) AND CHAR_LENGTH(sn) = %s",
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
    g = _bill_gen(b.bill_no)
    generated = 0 if g is None else g["generated_qty"]
    quota = max(0, b.total_qty - generated)
    if quota == 0:
        issues.append(ErrorCode.GEN_QUOTA_EMPTY.name)
    # 有绑定或该 PI 已选定过：规则确定；否则列出规则让用户选（默认通用规则）
    r = None if util.blank(b.pi) else rules.fixed(b.pi, b.customer_code)
    rule_options = [] if r is not None else rules.options()
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
    bills, issues, customer = [], [], ""
    for b, generated, quota in _pi_quota(pi):
        customer = customer or b.customer_code
        g = gens.get(b.bill_no)
        if b.factory_code is None and quota > 0 and ErrorCode.FACTORY_UNMAPPED.name not in issues:
            issues.append(ErrorCode.FACTORY_UNMAPPED.name)
        bills.append(
            {
                "bill_no": b.bill_no,
                "po": b.po,
                "customer_code": b.customer_code,
                "prd_org_name": b.prd_org_name,
                "factory_code": b.factory_code,
                "factory_name": names.get(b.factory_code or ""),
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
    rule_options = [] if r is not None else rules.options()
    if r is None and not rule_options:
        issues.append(ErrorCode.RULE_MISSING.name)
    running = db.one("SELECT * FROM sn_gen_job WHERE running_pi = %s", pi)
    recent = db.all("SELECT * FROM sn_gen_job WHERE pi_no = %s ORDER BY id DESC LIMIT 20", pi)
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
        "jobs": [job_json(j) for j in recent],
        "allocations": allocations([x["bill_no"] for x in bills]),
    }


def _next_json(pi: str, spec: codec.Spec) -> dict:
    return next_start(pi, counter(pi)["last_seq_dec"], spec).as_json(spec)


# ================================================================== 预演


def _check_bill(bill_no: str | None, qty: int) -> tuple[orders.Bill, int]:
    """生成前校验订单，返回订单与已生成数。"""
    b = orders.bill(bill_no)
    if util.blank(b.customer_code):
        raise biz(ErrorCode.ORDER_NO_CUSTOMER, bill_no=b.bill_no)
    if util.blank(b.pi):
        raise biz(ErrorCode.ORDER_NO_PI, bill_no=b.bill_no)
    if b.factory_code is None:
        raise biz(ErrorCode.FACTORY_UNMAPPED, org=b.prd_org_name)
    if qty <= 0:
        raise biz(ErrorCode.GEN_QTY_INVALID)
    g = _bill_gen(b.bill_no)
    generated = 0 if g is None else g["generated_qty"]
    quota = max(0, b.total_qty - generated)
    if quota == 0:
        raise biz(ErrorCode.GEN_QUOTA_EMPTY, bill_no=b.bill_no)
    if qty > quota:
        raise biz(ErrorCode.GEN_QUOTA_EXCEEDED, qty=qty, quota=quota)
    return b, generated


def _start_of(pi: str, c: dict | None, override: int | None, spec: codec.Spec) -> tuple[int, NextStart]:
    """本次起始流水：指定了起始号用它（须越过已转入的号），否则按 NextStart。"""
    ns = next_start(pi, 0 if c is None else c["last_seq_dec"], spec)
    mx = codec.max_seq(spec)
    if override is not None:
        if c is not None and c["start_locked"]:
            raise biz(ErrorCode.GEN_START_NOT_ALLOWED, pi=pi)
        if override < 1 or override > mx:
            raise biz(ErrorCode.GEN_START_INVALID, max=mx)
        check_start_above_foreign(pi, override, ns)
        return override, ns
    return ns.start, ns


def check_start_above_foreign(pi: str, start: int, ns: NextStart) -> None:
    if ns.foreign_seq is not None and start <= ns.foreign_seq:
        raise biz(ErrorCode.GEN_START_TAKEN, pi=pi, sn=ns.foreign_sn, seq=ns.foreign_seq)


def _check_capacity(end: int, spec: codec.Spec) -> None:
    mx = codec.max_seq(spec)
    if end > mx:
        raise biz(ErrorCode.GEN_RULE_EXHAUSTED, max=mx, end=end)


def segments_json(segs: list[sn_items.Segment]) -> str:
    return json.dumps([s.as_camel() for s in segs], ensure_ascii=False, separators=(",", ":"))


def preview(
    cu: CurrentUser, bill_no: str | None, qty_in: int | None, start_override: int | None, rule_id: int | None = None
) -> dict:
    qty = 0 if qty_in is None else qty_in
    b, generated = _check_bill(bill_no, qty)
    r = rules.for_generation(b.pi, b.customer_code, rule_id)
    spec = rules.spec_of(r.version)
    c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s", b.pi)
    base = 0 if c is None else c["last_seq_dec"]
    start, ns = _start_of(b.pi, c, start_override, spec)
    _check_capacity(start + qty - 1, spec)
    out = _save_preview(cu, b, generated, qty, start, base, start_override, r, spec)
    out["next"] = ns.as_json(spec)
    out["rule"] = rules.rule_info(r)
    return out


def _save_preview(
    cu: CurrentUser,
    b: orders.Bill,
    generated: int,
    qty: int,
    start: int,
    base: int,
    start_override: int | None,
    r: rules.Resolved,
    spec: codec.Spec,
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
        "end_sn, rule_version_id, base_last_seq, start_override, segments_json, created_by, created_at, expires_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
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
        base,
        start_override,
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
        "start_override": start_override,
        "segments": [s.as_json() for s in segs],
        "expires_at": util.fmt(expires),
    }


def pi_preview(
    cu: CurrentUser, pi_in: str | None, qty_in: int | None, start_override: int | None, rule_id: int | None = None
) -> dict:
    """按 PI 预演：数量按单据号顺序依次占用各订单的额度，号段在 PI 的一套流水上接续。

    每张订单各记一份预演（base 为前一张生成后的最大号），凭这些令牌调用 generate_pi 在一个事务里一次生成。
    """
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    qty = 0 if qty_in is None else qty_in
    if qty <= 0:
        raise biz(ErrorCode.GEN_QTY_INVALID)
    with_quota = [(b, gen, quota) for b, gen, quota in _pi_quota(pi) if quota > 0]
    if not with_quota:
        raise biz(ErrorCode.GEN_PI_QUOTA_EMPTY, pi=pi)
    # 生产组织未建档的订单先跳过（页面会提示），不挡住同 PI 其他订单
    todo = [x for x in with_quota if x[0].factory_code is not None]
    if not todo:
        raise biz(ErrorCode.FACTORY_UNMAPPED, org=with_quota[0][0].prd_org_name)
    total = sum(q for _, _, q in todo)
    if qty > total:
        raise biz(ErrorCode.GEN_QUOTA_EXCEEDED, qty=qty, quota=total)
    r = rules.for_generation(pi, todo[0][0].customer_code, rule_id)
    spec = rules.spec_of(r.version)
    c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s", pi)
    base = 0 if c is None else c["last_seq_dec"]
    start, ns = _start_of(pi, c, start_override, spec)
    _check_capacity(start + qty - 1, spec)
    items = []
    cur, left, prev = start, qty, base
    for b, gen, quota in todo:
        if left == 0:
            break
        n = min(quota, left)
        items.append(_save_preview(cu, b, gen, n, cur, prev, start_override if not items else None, r, spec))
        prev, cur, left = cur + n - 1, cur + n, left - n
    return {
        "pi_no": pi,
        "qty": qty,
        "start_seq": start,
        "end_seq": start + qty - 1,
        "start_sn": items[0]["start_sn"],
        "end_sn": items[-1]["end_sn"],
        "base_last_seq": base,
        "start_override": start_override,
        "next": ns.as_json(spec),
        "rule": rules.rule_info(r),
        "items": items,
        "expires_at": items[0]["expires_at"],
    }


# ================================================================== 生成


def generate(
    cu: CurrentUser, token: str | None, bill_no: str | None, qty: int | None, start_override: int | None
) -> dict:
    p = _usable_preview(cu, token, bill_no, qty, start_override)
    _check_base(p, _last_seq(p["pi_no"]))
    try:
        job_id = _insert_job(cu, p, p["pi_no"])
    except pymysql.err.IntegrityError as e:
        _raise_job_conflict(e, p["pi_no"])
    db.exec("UPDATE sn_gen_preview SET used_at = %s WHERE token = %s", util.now(), p["token"])
    if p["qty"] <= settings.gen_sync_threshold:
        _run_job(cu, job_id, p, True)
    else:
        executor.submit(_run_job, cu, job_id, p, False)
    return job_json(db.one("SELECT * FROM sn_gen_job WHERE id = %s", job_id))


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
        p = _usable_preview(cu, it.get("preview_token"), it.get("bill_no"), it.get("qty"), it.get("start_seq"))
        # 须是同一次按 PI 预演、按原顺序：号段首尾相接，只有第一张可带起始号
        prev = ps[-1] if ps else None
        if (
            p["pi_no"] != pi
            or (prev is not None and (p["base_last_seq"] != prev["end_seq_dec"] or p["start_override"] is not None))
            or (prev is not None and p["rule_version_id"] != prev["rule_version_id"])
            or any(x["bill_no"] == p["bill_no"] for x in ps)
        ):
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
        ps.append(p)
    _check_base(ps[0], _last_seq(pi))
    try:
        with db.tx():
            ids = [_insert_job(cu, p, pi if i == 0 else None) for i, p in enumerate(ps)]
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
    return {"pi_no": pi, "qty": sum(p["qty"] for p in ps), "jobs": [job_json(j) for j in rows]}


def _usable_preview(
    cu: CurrentUser, token: str | None, bill_no: str | None, qty: int | None, start_override: int | None
) -> dict:
    """本人、未用、未过期，且订单 / 数量 / 起始号与预演一致的预演。"""
    p = None if util.blank(token) else db.one("SELECT * FROM sn_gen_preview WHERE token = %s", token.strip())
    if p is None or p["created_by"] != cu.emp_no:
        raise biz(ErrorCode.GEN_PREVIEW_REQUIRED)
    if p["used_at"] is not None:
        raise biz(ErrorCode.GEN_PREVIEW_USED)
    if util.now() > p["expires_at"]:
        raise biz(ErrorCode.GEN_PREVIEW_EXPIRED)
    if p["bill_no"] != util.trim(bill_no) or qty is None or qty != p["qty"] or p["start_override"] != start_override:
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    return p


def _last_seq(pi: str) -> int:
    c = db.one("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi)
    return 0 if c is None else c["last_seq_dec"]


def _check_base(p: dict, last: int) -> None:
    if last != p["base_last_seq"]:
        raise biz(ErrorCode.GEN_PREVIEW_STALE, pi=p["pi_no"], expected=p["base_last_seq"], actual=last)


def _insert_job(cu: CurrentUser, p: dict, running_pi: str | None) -> int:
    return db.insert(
        "INSERT INTO sn_gen_job(bill_no, pi_no, factory_code, customer_code, rule_version_id, qty, start_seq_dec, "
        "end_seq_dec, start_sn, end_sn, status, done_qty, running_pi, preview_token, segments_json, created_by, "
        "created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,0,%s,%s,%s,%s,%s)",
        p["bill_no"],
        p["pi_no"],
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


def _run_job(cu: CurrentUser, job_id: int, p: dict, rethrow: bool) -> None:
    _run_jobs(cu, [(job_id, p)], rethrow)


def _run_jobs(cu: CurrentUser, work: list[tuple[int, dict]], rethrow: bool) -> None:
    """按顺序执行一个或多个生成任务（同一 PI 的各订单），全部在一个事务里：任何一张失败，全部回滚、全部标为失败。"""
    current = work[0]
    try:
        # 号与任务状态同一事务提交：不会出现「号已写入、任务仍是 RUNNING」
        with db.tx():
            for item in work:
                current = item
                _generate_in_tx(cu, *item)
            for job_id, p in work:
                n = db.exec(
                    "UPDATE sn_gen_job SET status = %s, done_qty = %s, running_pi = NULL, finished_at = %s "
                    "WHERE id = %s AND status = %s",
                    SUCCESS,
                    p["qty"],
                    util.now(),
                    job_id,
                    RUNNING,
                )
                if n != 1:
                    raise biz(ErrorCode.CONFLICT_RETRY)
        for job_id, p in work:
            log.info("gen_done", job=job_id, pi=p["pi_no"], qty=p["qty"])
    except Exception as e:  # noqa: BLE001
        code = e.code if isinstance(e, BizError) else "INTERNAL"
        msg = str(e) or type(e).__name__
        if len(work) > 1:
            msg = f"{current[1]['bill_no']}: {msg}"
        msg = util.cut(msg, 1000)
        for job_id, p in work:
            try:
                db.exec(
                    "UPDATE sn_gen_job SET status = %s, done_qty = 0, error_code = %s, error_msg = %s, "
                    "running_pi = NULL, finished_at = %s WHERE id = %s AND status = %s",
                    FAILED,
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


def _generate_in_tx(cu: CurrentUser, job_id: int, p: dict) -> None:
    now = util.now()
    pi = p["pi_no"]
    # 1) 锁 PI 计数器（一张 PI 一行），核对预演时的最大号
    db.exec(
        "INSERT INTO sn_pi_counter(pi_no, last_seq_dec, generated_qty, imported_qty, start_locked, updated_at) "
        "VALUES (%s,0,0,0,0,%s) ON DUPLICATE KEY UPDATE pi_no = pi_no",
        pi,
        now,
    )
    c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", pi)
    # 已被启动回收标为失败的任务不再执行（回收要先拿到计数器锁，持锁期间不会再改任务状态）
    st = db.scalar("SELECT status FROM sn_gen_job WHERE id = %s", job_id)
    if st != RUNNING:
        raise biz(ErrorCode.CONFLICT_RETRY)
    if c["last_seq_dec"] != p["base_last_seq"]:
        raise biz(ErrorCode.GEN_PREVIEW_STALE, pi=pi, expected=p["base_last_seq"], actual=c["last_seq_dec"])
    if p["start_override"] is not None and c["start_locked"]:
        raise biz(ErrorCode.GEN_START_NOT_ALLOWED, pi=pi)
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
    # 3) 规则版本未变（锁版本并标记已使用，之后该版本只能另出新版）；
    #    没有绑定时须仍是预演所选的规则，且期间该 PI 没被别的生成选定成另一条
    v = rules.lock_for_generation(p["rule_version_id"])
    r = rules.for_generation(b.pi, b.customer_code, v["rule_id"], c["rule_id"])
    spec = rules.spec_of(v)
    if (
        r.version["id"] != v["id"]
        or codec.format_sn(p["start_seq_dec"], spec) != p["start_sn"]
        or b.pi != pi
        or p["factory_code"] != b.factory_code
    ):
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    segs = sn_items.split(b.lines, g["generated_qty"], p["qty"], p["start_seq_dec"], spec)
    if segments_json(segs) != p["segments_json"]:
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    _check_capacity(p["end_seq_dec"], spec)
    # 预演之后又有号转入本 PI：起点可能要后移，预演失效
    ns = next_start(pi, c["last_seq_dec"], spec)
    if p["start_override"] is None:
        if ns.start != p["start_seq_dec"]:
            raise biz(ErrorCode.GEN_PREVIEW_STALE, pi=pi, expected=p["start_seq_dec"] - 1, actual=ns.base)
    else:
        check_start_above_foreign(pi, p["start_override"], ns)
    # 4) 逐块写号：先查重，再写护栏与明细
    month = util.month_of(now)
    done = 0
    for seg in segs:
        s = seg.start_seq
        while s <= seg.end_seq:
            e = min(seg.end_seq, s + CHUNK - 1)
            sns = [codec.format_sn(q, spec) for q in range(s, e + 1)]
            dup = _first_existing(pi, sns)
            if dup is not None:
                raise biz(ErrorCode.GEN_DUPLICATE_SN, sn=dup, pi=pi)
            keys = [(pi, sn, pi, s + i) for i, sn in enumerate(sns)]
            items = [
                (
                    month,
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
                    "GEN",
                    job_id,
                    now,
                    now,
                )
                for i, sn in enumerate(sns)
            ]
            try:
                db.exec_many(_INSERT_KEY, keys)
            except pymysql.err.IntegrityError as ex:
                if not is_duplicate(ex):
                    raise
                again = _first_existing(pi, sns)
                raise biz(ErrorCode.GEN_DUPLICATE_SN, sn=again or sns[0], pi=pi) from None
            db.exec_many(_INSERT_ITEM, items)
            done += len(sns)
            _progress(job_id, done)
            s += CHUNK
    # 5) 推进计数器、订单已生成数；留痕
    sets = "last_seq_dec = %s, generated_qty = %s, start_locked = 1, updated_at = %s"
    args: list = [p["end_seq_dec"], c["generated_qty"] + p["qty"], now]
    if r.source == rules.SRC_PICKED:
        # 没有绑定：记下所选规则，这张 PI 之后一直沿用
        sets += ", rule_id = %s"
        args.append(r.rule["id"])
    if not c["start_locked"]:
        sets += ", locked_by = %s, locked_at = %s"
        args += [cu.emp_no, now]
    if p["start_override"] is not None:
        sets += ", start_seq_dec = %s"
        args.append(p["start_override"])
    db.exec(f"UPDATE sn_pi_counter SET {sets} WHERE pi_no = %s", *args, pi)
    db.exec(
        "UPDATE sn_bill_gen SET generated_qty = %s, factory_code = %s, updated_at = %s WHERE bill_no = %s",
        g["generated_qty"] + p["qty"],
        p["factory_code"],
        now,
        b.bill_no,
    )
    detail = f"job={job_id} rule_version={v['id']} rule_source={r.source} segments={len(segs)}"
    if p["start_override"] is not None:
        detail += f" start={p['start_override']}"
    if p["start_override"] is None and ns.adjusted:
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


def _first_existing(pi: str, sns: list[str]) -> str | None:
    """本 PI 下（含转入号）已存在的第一个 SN；没有返回 None。"""
    hit = db.column(f"SELECT sn FROM sn_key WHERE pi_no = %s AND sn IN ({marks(len(sns))}) LIMIT 1", pi, *sns)
    return hit[0] if hit else None


def _progress(job_id: int, done: int) -> None:
    try:
        with db.new_tx():
            db.exec("UPDATE sn_gen_job SET done_qty = %s WHERE id = %s", done, job_id)
    except Exception:  # noqa: BLE001
        log.debug("progress_update_failed", job=job_id)


# ================================================================== 分配


def allocate(cu: CurrentUser, bill_no: str | None, qty_in: int | None, factory_code: str | None) -> dict:
    no = util.trim(bill_no)
    with db.tx():
        g = _bill_gen(no, lock=True)
        if g is None:
            raise biz(ErrorCode.ALLOC_NOTHING, bill_no=no)
        if not util.blank(factory_code) and g["factory_code"] != factory_code.strip():
            raise biz(ErrorCode.ALLOC_FACTORY_MISMATCH, factory=g["factory_code"])
        pending = _pending_alloc(no)
        if pending == 0:
            raise biz(ErrorCode.ALLOC_NOTHING, bill_no=no)
        qty = pending if qty_in is None else qty_in
        if qty <= 0:
            raise biz(ErrorCode.GEN_QTY_INVALID)
        if qty > pending:
            raise biz(ErrorCode.ALLOC_QTY_EXCEEDED, qty=qty, pending=pending)
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
            "pending_left": pending - qty,
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
        items = [allocate(cu, no, None, None) for no in nos]
    return {"pi_no": pi, "qty": sum(x["qty"] for x in items), "items": items}


def allocations(bill_nos: list[str]) -> list[dict]:
    nos = [n for n in (util.trim(x) for x in bill_nos) if n]
    if not nos:
        return []
    rows = db.all(f"SELECT * FROM sn_allocation WHERE bill_no IN ({marks(len(nos))}) ORDER BY id DESC LIMIT 100", *nos)
    return [util.jrow(a, ALLOCATION_FIELDS) for a in rows]
