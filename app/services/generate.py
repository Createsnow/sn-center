"""SN 生成（F-09）与分配（F-11 分配部分）。

生成：在一张 PI 下整单（勾选单据，单据内按序号）或按物料行（按点选顺序）生成；号都排在该 PI 的一套流水上，
各物料行按自己的规则写出（前缀 + 长度 + 流水 + 后缀）。必须先预演（只给出起止号、各物料行数量和号段，不落库），
预演只对发起人有效、默认 30 分钟、只能生成一次；范围或条件改变、所选规则版本被别的 PI 先用、PI 最大号变化、
同步改了范围内物料行数量时预演失效。
一次生成在一个事务里完成（共用一个生成批次），撞号整次回滚、不跳号；不超过 SN_GEN_SYNC_THRESHOLD 当场完成，
更多转后台任务（进度、可取消）；服务重启时没跑完的任务视为失败。起点 = 本 PI 自己生成的最大流水 + 1。
撤销：只能撤这张 PI 流水排在最后的一次生成，且号全部仍待分配、都没转过单；号删除，最大号和计数一并退回。

分配：号只能分到订单的生产组织（快照中订单的生产组织；订单已不在快照时取号上的生成时订单生产组织；
反审核中、PI 变更待确认、暂停交付的单据跳过）。对接 MES 的改为待推送（未入批）并自动开始推送；未对接的改为待领取。
生成（含取消）、撤销、分配共用 PI 操作锁。
"""

from __future__ import annotations

import json
import math
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import timedelta

import pymysql
import structlog

from app.core import encoding as codec
from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError
from app.core.security import CurrentUser
from app.db.session import ER_LOCK_NOWAIT, db, error_no, is_duplicate, marks
from app.services import audit, changes, factories, locks, orders, rules

log = structlog.get_logger()

CHUNK = 2000
RUNNING = "RUNNING"
SUCCESS = "SUCCESS"
FAILED = "FAILED"
REVOKED = "REVOKED"
CANCELED = "CANCELED"
SCOPE_BILLS = "BILLS"
SCOPE_LINES = "LINES"

JOB_FIELDS = (
    "id",
    "gen_batch",
    "pi_no",
    "scope",
    "qty",
    "start_seq_dec",
    "end_seq_dec",
    "start_sn",
    "end_sn",
    "status",
    "done_qty",
    "current_sn",
    "error_code",
    "error_msg",
    "created_by",
    "cancel_by",
    "cancel_at",
    "revoke_reason",
    "created_at",
    "started_at",
    "finished_at",
)

_INSERT_ITEM = (
    "INSERT INTO sn_item(sn, factory_code, pi_no, customer_code, bill_no, bill_seq, material_code, rev, gen_org_code, "
    "gen_bill_no, gen_bill_seq, gen_material_code, seq_pi_no, seq_dec, length_text, rule_version_id, gen_batch, status, "
    "push_type, created_at, updated_at) VALUES (%s,NULL,%s,%s,%s,%s,%s,1,%s,%s,%s,%s,%s,%s,%s,%s,%s,'PENDING_ALLOC',"
    "'NEW',%s,%s)"
)

#: 大批量生成走后台线程
executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="sn-gen")


def job_json(j: dict | None) -> dict | None:
    out = util.jrow(j, JOB_FIELDS)
    if out is not None and j is not None:
        plan = json.loads(j["plan_json"])
        out["lines"] = [
            {k: x[k] for k in ("bill_no", "seq", "material_code", "length", "qty", "start_sn", "end_sn", "rule_label")}
            for x in plan["items"]
        ]
        out["bills"] = sorted({x["bill_no"] for x in plan["items"]})
    return out


def limits() -> dict:
    return {
        "default": settings.gen_default_limit,
        "max": settings.gen_max_per_run,
        "sync_threshold": settings.gen_sync_threshold,
    }


def last_seq(pi: str) -> int:
    return int(db.scalar("SELECT COALESCE(MAX(last_seq_dec), 0) FROM sn_pi_counter WHERE pi_no = %s", pi) or 0)


# ================================================================== 物料行与生成条件


@dataclass
class Line:
    """一条参与生成的物料行。"""

    bill_no: str
    seq: int
    material_code: str
    customer_code: str
    org_code: str
    pi: str
    qty: int
    own: int
    length_raw: str
    attach: str
    res: rules.Resolved | None
    block: str | None = None
    issues: list[str] = field(default_factory=list)

    @property
    def quota(self) -> int:
        return max(0, self.qty - self.own)

    @property
    def length(self) -> str | None:
        return codec.normalize_length(self.length_raw)


def _load_lines(pi: str, keys: list[tuple[str, int]]) -> list[Line]:
    """按给定顺序取物料行（快照 + 计数 + 附件项次 + 生效规则 + 单据冻结），并判断生成条件。"""
    if not keys:
        return []
    bills = sorted({k[0] for k in keys})
    snap = {
        (r["bill_no"], r["seq"]): r
        for r in db.all(f"SELECT * FROM sn_prd_mo WHERE bill_no IN ({marks(len(bills))})", *bills)
    }
    counts, fills = orders.line_extras(keys)
    sels = rules.selections(keys)
    defaults: dict[str, rules.Resolved | None] = {}
    blocks = {b: changes.generation_block(b) for b in bills}
    out = []
    for k in keys:
        r = snap.get(k)
        if r is None:
            raise biz(ErrorCode.ORDER_LINE_NOT_FOUND, bill_no=k[0], seq=k[1])
        if r["pi"] != pi:
            raise biz(ErrorCode.GEN_SCOPE_INVALID)
        if r["customer_code"] not in defaults:
            defaults[r["customer_code"]] = rules.pi_default(pi, r["customer_code"])
        c = counts.get(k)
        attach, _src = orders.effective_attach(r, fills.get(k))
        line = Line(
            r["bill_no"],
            r["seq"],
            r["material_code"],
            r["customer_code"],
            r["org_code"],
            r["pi"],
            orders.sn_qty(r),
            0 if c is None else int(c["own_generated"]),
            r["length_raw"],
            attach,
            rules.effective(pi, r["customer_code"], sels.get(k), defaults[r["customer_code"]]),
            blocks[r["bill_no"]],
        )
        _check(line)
        out.append(line)
    return out


def _check(line: Line) -> None:
    """生成条件（只对还有额度的物料行）。"""
    if line.quota <= 0:
        return
    if not line.customer_code:
        line.issues.append("NO_CUSTOMER")
    if line.block:
        line.issues.append(line.block)
    if line.res is None:
        line.issues.append("NO_RULE")
    elif not line.res.ready:
        line.issues.append("RULE_PREFIX")
    elif line.res.spec().with_length:
        if util.blank(line.length_raw):
            line.issues.append("LENGTH_EMPTY")
        elif line.length is None:
            line.issues.append("LENGTH_INVALID")
    if not line.attach:
        line.issues.append("ATTACH_EMPTY")


def _scope_keys(pi: str, scope: str, bills_in: list[str] | None, lines_in: list[dict] | None) -> list[tuple[str, int]]:
    if scope == SCOPE_BILLS:
        nos = list(dict.fromkeys(util.trim(b) for b in bills_in or [] if util.trim(b)))
        if not nos:
            raise biz(ErrorCode.GEN_SCOPE_INVALID)
        keys = []
        for no in nos:
            b = orders.must_bill(no)
            if b.pi != pi:
                raise biz(ErrorCode.GEN_SCOPE_INVALID)
            keys += [(no, x["seq"]) for x in b.lines]
        return keys
    if scope == SCOPE_LINES:
        keys = list(dict.fromkeys((util.trim(x.get("bill_no")), int(x.get("seq") or 0)) for x in lines_in or []))
        if not keys:
            raise biz(ErrorCode.GEN_SCOPE_INVALID)
        return keys
    raise biz(ErrorCode.GEN_SCOPE_INVALID)


# ================================================================== 预演


def _plan(pi: str, scope: str, keys: list[tuple[str, int]], qty_in: int | None, base: int) -> dict:
    """按排号顺序依次占满各物料行额度、切出号段。返回计划（不落库）；不能生成时 issues 非空。"""
    ls = _load_lines(pi, keys)
    todo = [x for x in ls if x.quota > 0]
    quota = sum(x.quota for x in todo)
    issues = [
        {"bill_no": x.bill_no, "seq": x.seq, "material_code": x.material_code, "issues": x.issues}
        for x in todo
        if x.issues
    ]
    out = {"pi_no": pi, "scope": scope, "quota": quota, "issues": issues, "items": [], "base_last_seq": base}
    if not todo:
        out["issues"] = [{"bill_no": None, "seq": None, "material_code": None, "issues": ["QUOTA_EMPTY"]}]
        return out
    if issues:
        return out
    qty = min(quota, settings.gen_default_limit) if qty_in is None else qty_in
    if qty <= 0:
        raise biz(ErrorCode.GEN_QTY_INVALID)
    if qty > quota:
        raise biz(ErrorCode.GEN_QUOTA_EXCEEDED, qty=qty, quota=quota)
    if qty > settings.gen_max_per_run:
        raise biz(ErrorCode.GEN_QTY_OVER_MAX, qty=qty, max=settings.gen_max_per_run)
    cur, left = base + 1, qty
    for x in todo:
        if left == 0:
            break
        n = min(x.quota, left)
        spec = x.res.spec()
        end = cur + n - 1
        mx = codec.max_seq(spec)
        if end > mx:
            raise biz(ErrorCode.GEN_RULE_EXHAUSTED, max=mx, end=end)
        length = x.length if spec.with_length else None
        start_sn = codec.format_sn(cur, spec, length)
        if len(start_sn) > codec.SN_MAX:
            raise biz(ErrorCode.RULE_SN_TOO_LONG)
        res = x.res
        out["items"].append(
            {
                "bill_no": x.bill_no,
                "seq": x.seq,
                "material_code": x.material_code,
                "customer_code": x.customer_code,
                "org_code": x.org_code,
                "length": length,
                "line_qty": x.qty,
                "own_before": x.own,
                "qty": n,
                "start_seq": cur,
                "end_seq": end,
                "start_sn": start_sn,
                "end_sn": codec.format_sn(end, spec, length),
                "rule_key": res.key(),
                "rule_plan": res.plan,
                "rule_id": None if res.rule is None else res.rule["id"],
                "version_id": res.version["id"],
                "custom": res.custom,
                "rule_label": (
                    f"{res.rule['rule_code']} v{res.version['version']}" if res.rule is not None else "自定义"
                )
                + f"（{rules.format_text(spec)}）",
            }
        )
        cur, left = end + 1, left - n
    out["qty"] = qty
    out["start_seq"] = base + 1
    out["end_seq"] = base + qty
    out["start_sn"] = out["items"][0]["start_sn"]
    out["end_sn"] = out["items"][-1]["end_sn"]
    return out


def preview(
    cu: CurrentUser,
    pi_in: str | None,
    scope: str | None,
    bills: list[str] | None,
    lines: list[dict] | None,
    qty: int | None,
) -> dict:
    """预演：可生成时返回令牌与号段；不能生成时 generatable = false 并逐行列出原因（可缩小范围后再预演）。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    sc = util.trim(scope) or SCOPE_BILLS
    keys = _scope_keys(pi, sc, bills, lines)
    plan = _plan(pi, sc, keys, qty, last_seq(pi))
    if plan["issues"]:
        return {**plan, "generatable": False, "limits": limits()}
    now = util.now()
    token = util.token_hex()
    expires = now + timedelta(minutes=settings.preview_ttl_minutes)
    stored = {**plan, "keys": [list(k) for k in keys]}
    db.exec(
        "INSERT INTO sn_gen_preview(token, pi_no, qty, base_last_seq, plan_json, created_by, created_at, expires_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
        token,
        pi,
        plan["qty"],
        plan["base_last_seq"],
        json.dumps(stored, ensure_ascii=False, separators=(",", ":")),
        cu.emp_no,
        now,
        expires,
    )
    return {**plan, "generatable": True, "token": token, "expires_at": util.fmt(expires), "limits": limits()}


_KEY_FIELDS = (
    "bill_no",
    "seq",
    "qty",
    "start_seq",
    "end_seq",
    "start_sn",
    "end_sn",
    "rule_key",
    "rule_plan",
    "line_qty",
    "own_before",
)


def _same_plan(a: dict, b: dict) -> bool:
    if len(a["items"]) != len(b["items"]):
        return False
    return all(all(x[k] == y[k] for k in _KEY_FIELDS) for x, y in zip(a["items"], b["items"], strict=True))


# ================================================================== 生成


def generate(cu: CurrentUser, token: str | None) -> dict:
    """凭预演令牌生成。占用 PI 操作锁（后台任务结束时释放）。"""
    p = None if util.blank(token) else db.one("SELECT * FROM sn_gen_preview WHERE token = %s", util.trim(token))
    if p is None or p["created_by"] != cu.emp_no:
        raise biz(ErrorCode.GEN_PREVIEW_REQUIRED)
    if db.exists("SELECT EXISTS(SELECT 1 FROM sn_gen_job WHERE preview_token = %s)", p["token"]):
        raise biz(ErrorCode.GEN_PREVIEW_USED)
    if util.now() > p["expires_at"]:
        raise biz(ErrorCode.GEN_PREVIEW_EXPIRED)
    pi = p["pi_no"]
    held = locks.pi_lock(pi)
    try:
        stored = json.loads(p["plan_json"])
        actual = last_seq(pi)
        if actual != p["base_last_seq"]:
            raise biz(ErrorCode.GEN_PREVIEW_STALE, pi=pi, expected=p["base_last_seq"], actual=actual)
        keys = [tuple(k) for k in stored["keys"]]
        fresh = _plan(pi, stored["scope"], keys, stored["qty"], actual)
        if fresh["issues"] or not _same_plan(stored, fresh):
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
        batch = util.next_id("G") + util.token_hex()[:4]
        try:
            job_id = db.insert(
                "INSERT INTO sn_gen_job(gen_batch, pi_no, scope, qty, start_seq_dec, end_seq_dec, start_sn, end_sn, "
                "status, done_qty, running_pi, preview_token, plan_json, created_by, created_at) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,0,%s,%s,%s,%s,%s)",
                batch,
                pi,
                stored["scope"],
                fresh["qty"],
                fresh["start_seq"],
                fresh["end_seq"],
                fresh["start_sn"],
                fresh["end_sn"],
                RUNNING,
                pi,
                p["token"],
                json.dumps(fresh, ensure_ascii=False, separators=(",", ":")),
                cu.emp_no,
                util.now(),
            )
        except pymysql.err.IntegrityError as e:
            if not is_duplicate(e):
                raise
            if "ux_job_preview" in str(e):
                raise biz(ErrorCode.GEN_PREVIEW_USED) from None
            raise biz(ErrorCode.GEN_BUSY, pi=pi) from None
    except BaseException:
        held.release()
        raise
    if fresh["qty"] <= settings.gen_sync_threshold:
        try:
            _run(cu, job_id, True)
        finally:
            held.release()
    else:

        def background() -> None:
            try:
                _run(cu, job_id, False)
            finally:
                held.release()

        executor.submit(background)
    return job_json(db.one("SELECT * FROM sn_gen_job WHERE id = %s", job_id))


def _run(cu: CurrentUser, job_id: int, rethrow: bool) -> None:
    j = db.one("SELECT * FROM sn_gen_job WHERE id = %s", job_id)
    plan = json.loads(j["plan_json"])
    db.exec("UPDATE sn_gen_job SET started_at = %s WHERE id = %s", util.now(), job_id)
    try:
        with db.tx():
            _generate_in_tx(cu, j, plan)
            n = db.exec(
                "UPDATE sn_gen_job SET status = %s, done_qty = qty, current_sn = end_sn, running_pi = NULL, "
                "finished_at = %s WHERE id = %s AND status = %s AND cancel_at IS NULL",
                SUCCESS,
                util.now(),
                job_id,
                RUNNING,
            )
            if n != 1:
                if _cancel_requested(job_id):
                    raise biz(ErrorCode.GEN_CANCELED)
                raise biz(ErrorCode.CONFLICT_RETRY)
        log.info("gen_done", job=job_id, pi=j["pi_no"], qty=j["qty"])
    except Exception as e:  # noqa: BLE001
        code = e.code if isinstance(e, BizError) else "INTERNAL"
        canceled = code == ErrorCode.GEN_CANCELED.name
        msg = util.cut(str(e) or type(e).__name__, 1000)
        try:
            db.exec(
                "UPDATE sn_gen_job SET status = %s, done_qty = 0, error_code = %s, error_msg = %s, running_pi = NULL, "
                "finished_at = %s WHERE id = %s AND status = %s",
                CANCELED if canceled else FAILED,
                code,
                msg,
                util.now(),
                job_id,
                RUNNING,
            )
        except Exception:  # noqa: BLE001
            log.exception("gen_job_status_update_failed", job=job_id)
        # 取消、后台失败：涉及的单据各留一条失败痕迹（同步执行的失败由全局异常处理记）
        if canceled or not rethrow:
            for no, items in _by_bill(plan["items"]).items():
                audit.record_isolated(
                    cu,
                    audit.entry(audit.SN_GENERATE)
                    .fail(msg)
                    .pi(j["pi_no"])
                    .bill(no)
                    .factory(items[0]["org_code"])
                    .count(sum(x["qty"] for x in items))
                    .range(items[0]["start_sn"], items[-1]["end_sn"])
                    .batch(j["gen_batch"])
                    .info(f"job={job_id}"),
                )
        if not isinstance(e, BizError):
            log.error("gen_failed", job=job_id, pi=j["pi_no"], exc_info=e)
        if rethrow:
            raise


def _by_bill(items: list[dict]) -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {}
    for x in items:
        out.setdefault(x["bill_no"], []).append(x)
    return out


def _generate_in_tx(cu: CurrentUser, j: dict, plan: dict) -> None:
    now = util.now()
    pi = j["pi_no"]
    db.exec(
        "INSERT INTO sn_pi_counter(pi_no, last_seq_dec, generated_qty, updated_at) VALUES (%s,0,0,%s) "
        "ON DUPLICATE KEY UPDATE pi_no = pi_no",
        pi,
        now,
    )
    c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", pi)
    if db.scalar("SELECT status FROM sn_gen_job WHERE id = %s", j["id"]) != RUNNING:
        raise biz(ErrorCode.CONFLICT_RETRY)
    if _cancel_requested(j["id"]):
        raise biz(ErrorCode.GEN_CANCELED)
    if c["last_seq_dec"] != plan["base_last_seq"]:
        raise biz(ErrorCode.GEN_PREVIEW_STALE, pi=pi, expected=plan["base_last_seq"], actual=c["last_seq_dec"])
    # 事务内按最新快照和计数表重新校验额度（锁计数行）
    for x in plan["items"]:
        db.exec(
            "INSERT INTO sn_line_count(bill_no, seq, material_code, own_generated, gen_count, alloc_count, updated_at) "
            "VALUES (%s,%s,%s,0,0,0,%s) ON DUPLICATE KEY UPDATE bill_no = bill_no",
            x["bill_no"],
            x["seq"],
            x["material_code"],
            now,
        )
        lc = db.one("SELECT * FROM sn_line_count WHERE bill_no = %s AND seq = %s FOR UPDATE", x["bill_no"], x["seq"])
        line = db.one(
            "SELECT qty, material_code FROM sn_prd_mo WHERE bill_no = %s AND seq = %s", x["bill_no"], x["seq"]
        )
        if line is None or orders.sn_qty(line) - int(lc["own_generated"]) < x["qty"]:
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    # 规则版本落库并归属本 PI；同一次生成里规则相同的物料行共用同一版
    versions: dict[str, dict] = {}
    for x in plan["items"]:
        if x["rule_key"] in versions:
            continue
        same = [(y["bill_no"], y["seq"]) for y in plan["items"] if y["rule_key"] == x["rule_key"]]
        res = _resolved_of(pi, x)
        versions[x["rule_key"]] = rules.realize(cu, pi, res, x["material_code"], same)
    for no in {x["bill_no"] for x in plan["items"]}:
        changes.ensure_ledger(no)
    done = 0
    for x in plan["items"]:
        v = versions[x["rule_key"]]
        spec = rules.spec_of(v)
        s = x["start_seq"]
        while s <= x["end_seq"]:
            e = min(x["end_seq"], s + CHUNK - 1)
            sns = [codec.format_sn(q, spec, x["length"]) for q in range(s, e + 1)]
            rows = [
                (
                    sn,
                    pi,
                    x["customer_code"],
                    x["bill_no"],
                    x["seq"],
                    x["material_code"],
                    x["org_code"],
                    x["bill_no"],
                    x["seq"],
                    x["material_code"],
                    pi,
                    s + i,
                    x["length"],
                    v["id"],
                    j["gen_batch"],
                    now,
                    now,
                )
                for i, sn in enumerate(sns)
            ]
            try:
                db.exec_many(_INSERT_ITEM, rows)
            except pymysql.err.IntegrityError as ex:
                if not is_duplicate(ex):
                    raise
                hit = db.one(f"SELECT sn, pi_no FROM sn_item WHERE sn IN ({marks(len(sns))}) LIMIT 1", *sns)
                if hit is None:
                    raise biz(ErrorCode.CONFLICT_RETRY) from None
                raise biz(ErrorCode.GEN_DUPLICATE_SN, sn=hit["sn"], pi=hit["pi_no"]) from None
            done += len(sns)
            if _progress(j["id"], done, sns[-1]):
                raise biz(ErrorCode.GEN_CANCELED)
            s = e + 1
        db.exec(
            "UPDATE sn_line_count SET material_code = %s, own_generated = own_generated + %s, "
            "gen_count = gen_count + %s, updated_at = %s WHERE bill_no = %s AND seq = %s",
            x["material_code"],
            x["qty"],
            x["qty"],
            now,
            x["bill_no"],
            x["seq"],
        )
    db.exec(
        "UPDATE sn_pi_counter SET last_seq_dec = %s, generated_qty = generated_qty + %s, updated_at = %s WHERE pi_no = %s",
        plan["end_seq"],
        plan["qty"],
        now,
        pi,
    )
    changes.refresh_items({x["bill_no"] for x in plan["items"]})
    for no, items in _by_bill(plan["items"]).items():
        audit.record(
            cu,
            audit.entry(audit.SN_GENERATE)
            .factory(items[0]["org_code"])
            .pi(pi)
            .customer(items[0]["customer_code"])
            .bill(no)
            .range(items[0]["start_sn"], items[-1]["end_sn"])
            .count(sum(x["qty"] for x in items))
            .status(None, util.PENDING_ALLOC)
            .batch(j["gen_batch"])
            .info(
                f"job={j['id']} lines="
                + ",".join(
                    f"{x['seq']}:{x['qty']}@{x['start_sn']}~{x['end_sn']}[{versions[x['rule_key']]['id']}]"
                    for x in items
                )
            ),
        )


def _resolved_of(pi: str, x: dict) -> rules.Resolved:
    """生成事务内重新解析该行规则，须与预演时一致。"""
    if x["rule_plan"] == rules.PLAN_NEW:
        return rules._custom_resolved(rules.SRC_LINE, x["custom"])
    rule = db.one("SELECT * FROM sn_rule WHERE id = %s FOR UPDATE", x["rule_id"])
    if rule is None:
        raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
    res = rules.resolve(rule, pi, rules.SRC_LINE)
    if res.version is None or res.version["id"] != x["version_id"]:
        # 客户模板解析时的来源不同，但版本必须一致
        res2 = rules.resolve(rule, pi, rules.SRC_CUSTOMER)
        if res2.version is None or res2.version["id"] != x["version_id"]:
            raise biz(ErrorCode.GEN_PREVIEW_CHANGED)
        return res2
    return res


INTERRUPTED = "INTERRUPTED"


def recover_interrupted() -> int:
    """启动时：仍是 RUNNING 的任务（事务已随进程退出回滚）标为失败并释放 running_pi。"""
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
                    "服务重启时任务未完成，已整批回滚，请重新预演后生成",
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


def _progress(job_id: int, done: int, current: str) -> bool:
    """独立事务写进度（页面实时可见），并返回是否已请求取消。"""
    try:
        with db.new_tx():
            db.exec("UPDATE sn_gen_job SET done_qty = %s, current_sn = %s WHERE id = %s", done, current, job_id)
            return db.scalar("SELECT cancel_at FROM sn_gen_job WHERE id = %s", job_id) is not None
    except Exception:  # noqa: BLE001
        log.debug("progress_update_failed", job=job_id)
        return False


def _cancel_requested(job_id: int) -> bool:
    with db.new_tx():
        return db.scalar("SELECT cancel_at FROM sn_gen_job WHERE id = %s", job_id) is not None


# ================================================================== 进度 / 取消 / 撤销


def _job_by_batch(batch: str | None, lock: bool = False) -> dict:
    j = (
        None
        if util.blank(batch)
        else db.one("SELECT * FROM sn_gen_job WHERE gen_batch = %s" + (" FOR UPDATE" if lock else ""), util.trim(batch))
    )
    if j is None:
        raise biz(ErrorCode.GEN_JOB_NOT_FOUND)
    return j


def progress(batch: str | None) -> dict:
    """进度：百分比、已生成 / 总数、已写到的号、速度、预计剩余时间。"""
    j = _job_by_batch(batch)
    out = job_json(j)
    elapsed = None
    if j["started_at"] is not None:
        end = j["finished_at"] or util.now()
        elapsed = max(1, int((end - j["started_at"]).total_seconds()))
    speed = None if not elapsed or not j["done_qty"] else j["done_qty"] / elapsed
    out["percent"] = 0 if not j["qty"] else min(100, int(j["done_qty"] * 100 / j["qty"]))
    out["speed_per_sec"] = None if speed is None else round(speed, 1)
    out["eta_seconds"] = (
        None if speed is None or j["status"] != RUNNING else int(max(0, j["qty"] - j["done_qty"]) / speed)
    )
    return out


def cancel(cu: CurrentUser, batch: str | None) -> dict:
    """取消生成中的任务：生成线程看到请求后整批回滚，任务记为已取消；取消留痕。"""
    with db.tx():
        j = _job_by_batch(batch, lock=True)
        if j["status"] != RUNNING:
            raise biz(ErrorCode.GEN_CANCEL_NOT_RUNNING)
        if j["cancel_at"] is None:
            db.exec(
                "UPDATE sn_gen_job SET cancel_at = %s, cancel_by = %s WHERE id = %s", util.now(), cu.emp_no, j["id"]
            )
            audit.record(
                cu,
                audit.entry(audit.SN_GEN_CANCEL)
                .pi(j["pi_no"])
                .range(j["start_sn"], j["end_sn"])
                .count(j["qty"])
                .batch(j["gen_batch"])
                .status(RUNNING, CANCELED)
                .info(f"job={j['id']} done={j['done_qty']}/{j['qty']}"),
            )
    return progress(j["gen_batch"])


def _revocable(pi: str) -> dict | None:
    """这张 PI 当前可撤销的一次生成：流水排在最后、成功，号全部仍待分配且都没转过单。"""
    last = last_seq(pi)
    if last == 0:
        return None
    j = db.one(
        "SELECT * FROM sn_gen_job WHERE pi_no = %s AND status = %s AND end_seq_dec = %s ORDER BY id DESC LIMIT 1",
        pi,
        SUCCESS,
        last,
    )
    if j is None or db.exists("SELECT EXISTS(SELECT 1 FROM sn_gen_job WHERE running_pi = %s)", pi):
        return None
    if _not_revocable_count(j):
        return None
    return {"gen_batch": j["gen_batch"], "qty": j["qty"], "start_sn": j["start_sn"], "end_sn": j["end_sn"]}


def _not_revocable_count(j: dict) -> int:
    """这次生成的号中已分配、或转过单（挂靠变了、版本号加过）的枚数；号已不在号池也算。"""
    ok = db.count(
        "SELECT COUNT(*) FROM sn_item WHERE seq_pi_no = %s AND seq_dec BETWEEN %s AND %s AND gen_batch = %s "
        "AND status = 'PENDING_ALLOC' AND rev = 1 AND bill_no = gen_bill_no AND bill_seq = gen_bill_seq "
        "AND pi_no = seq_pi_no",
        j["pi_no"],
        j["start_seq_dec"],
        j["end_seq_dec"],
        j["gen_batch"],
    )
    return j["qty"] - ok


def revoke(cu: CurrentUser, batch: str | None, reason_in: str | None) -> dict:
    reason = util.trim(reason_in)
    if not reason or len(reason) > 500:
        raise biz(ErrorCode.REASON_REQUIRED)
    j0 = _job_by_batch(batch)
    pi = j0["pi_no"]
    with locks.with_pi_lock(pi), db.tx():
        c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", pi)
        j = _job_by_batch(batch, lock=True)
        if j["status"] != SUCCESS:
            raise biz(ErrorCode.GEN_REVOKE_NOT_SUCCESS)
        if c is None or c["last_seq_dec"] != j["end_seq_dec"]:
            raise biz(ErrorCode.GEN_REVOKE_NOT_LAST, pi=pi)
        bad = _not_revocable_count(j)
        if bad:
            raise biz(ErrorCode.GEN_REVOKE_ALLOCATED, count=bad)
        plan = json.loads(j["plan_json"])
        n = db.exec(
            "DELETE FROM sn_item WHERE seq_pi_no = %s AND seq_dec BETWEEN %s AND %s AND gen_batch = %s "
            "AND status = 'PENDING_ALLOC'",
            pi,
            j["start_seq_dec"],
            j["end_seq_dec"],
            j["gen_batch"],
        )
        if n != j["qty"]:
            raise biz(ErrorCode.CONFLICT_RETRY)
        prev = db.scalar(
            "SELECT COALESCE(MAX(end_seq_dec), 0) FROM sn_gen_job WHERE pi_no = %s AND status = %s AND id <> %s",
            pi,
            SUCCESS,
            j["id"],
        )
        now = util.now()
        db.exec(
            "UPDATE sn_pi_counter SET last_seq_dec = %s, generated_qty = generated_qty - %s, updated_at = %s "
            "WHERE pi_no = %s",
            prev,
            j["qty"],
            now,
            pi,
        )
        for x in plan["items"]:
            db.exec(
                "UPDATE sn_line_count SET own_generated = own_generated - %s, gen_count = gen_count - %s, "
                "updated_at = %s WHERE bill_no = %s AND seq = %s",
                x["qty"],
                x["qty"],
                now,
                x["bill_no"],
                x["seq"],
            )
        db.exec(
            "UPDATE sn_gen_job SET status = %s, revoke_reason = %s WHERE id = %s",
            REVOKED,
            util.cut(reason, 500),
            j["id"],
        )
        for no, items in _by_bill(plan["items"]).items():
            audit.record(
                cu,
                audit.entry(audit.SN_REVOKE)
                .factory(items[0]["org_code"])
                .pi(pi)
                .bill(no)
                .range(items[0]["start_sn"], items[-1]["end_sn"])
                .count(sum(x["qty"] for x in items))
                .status(util.PENDING_ALLOC, None)
                .batch(j["gen_batch"])
                .why(reason)
                .info(f"job={j['id']} last_seq {j['end_seq_dec']} -> {prev}"),
            )
    return {"gen_batch": j["gen_batch"], "pi_no": pi, "qty": j["qty"], "last_seq_dec": prev}


def jobs(pi: str | None, limit: int = 20) -> list[dict]:
    rows = db.all(
        "SELECT * FROM sn_gen_job WHERE pi_no = %s ORDER BY id DESC LIMIT %s", util.trim(pi), min(max(limit, 1), 200)
    )
    rb = _revocable(util.trim(pi))
    out = []
    for j in rows:
        v = job_json(j)
        v["revocable"] = rb is not None and rb["gen_batch"] == j["gen_batch"]
        out.append(v)
    return out


# ================================================================== 分配


def _target_org(bill_no: str) -> tuple[str | None, str | None]:
    """分配到哪个生产组织：快照中订单的生产组织；订单已不在快照时为 None（取号上的生成时订单生产组织）。
    返回（组织，不能分配的原因）。"""
    block = changes.allocation_block(bill_no)
    if block:
        return None, block
    so = orders.snapshot_org(bill_no)
    return (so[0] if so else None), None


def _alloc_scope(pi: str, bills_in: list[str] | None, lines_in: list[dict] | None) -> tuple[str, list]:
    """分配范围（整张 PI / 单据 / 物料行）的 SQL 条件。"""
    if lines_in:
        keys = [(util.trim(x.get("bill_no")), int(x.get("seq") or 0)) for x in lines_in]
        cond = " OR ".join(["(bill_no = %s AND bill_seq = %s)"] * len(keys))
        return f" AND ({cond})", [v for k in keys for v in k]
    nos = [util.trim(b) for b in bills_in or [] if util.trim(b)]
    if nos:
        return f" AND bill_no IN ({marks(len(nos))})", nos
    return "", []


def _alloc_groups(pi: str, bills_in, lines_in) -> tuple[list[dict], list[dict]]:
    """待分配号按（单据、目标组织）分组；返回（可分配组，跳过的单据及原因）。"""
    cond, args = _alloc_scope(pi, bills_in, lines_in)
    rows = db.all(
        "SELECT bill_no, gen_org_code, COUNT(*) n, MIN(seq_dec) s, MAX(seq_dec) e FROM sn_item "
        "WHERE pi_no = %s AND status = 'PENDING_ALLOC'" + cond + " GROUP BY bill_no, gen_org_code ORDER BY bill_no",
        pi,
        *args,
    )
    groups, skipped = [], []
    for r in rows:
        org, block = _target_org(r["bill_no"])
        if block:
            skipped.append({"bill_no": r["bill_no"], "qty": int(r["n"]), "reason": block})
            continue
        groups.append(
            {
                "bill_no": r["bill_no"],
                "org": org or r["gen_org_code"],
                "gen_org": r["gen_org_code"],
                "qty": int(r["n"]),
                "snapshot": org is not None,
            }
        )
    return groups, skipped


def alloc_preview(pi_in: str | None, bills: list[str] | None, lines: list[dict] | None) -> dict:
    """分配面板：按生产组织汇总枚数、单据和物料，标出是否对接 MES；按钮写「分配 N 枚（推送 M 次）」。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    groups, skipped = _alloc_groups(pi, bills, lines)
    by_org: dict[str, dict] = {}
    for g in groups:
        o = by_org.setdefault(g["org"], {"org_code": g["org"], "qty": 0, "bills": [], "materials": set()})
        o["qty"] += g["qty"]
        o["bills"].append(g["bill_no"])
    cond, args = _alloc_scope(pi, bills, lines)
    for r in db.all(
        "SELECT bill_no, GROUP_CONCAT(DISTINCT material_code) m FROM sn_item WHERE pi_no = %s AND status = 'PENDING_ALLOC'"
        + cond
        + " GROUP BY bill_no",
        pi,
        *args,
    ):
        for o in by_org.values():
            if r["bill_no"] in o["bills"]:
                o["materials"].update(x for x in (r["m"] or "").split(",") if x is not None)
    names = factories.names()
    out_orgs, pushes = [], 0
    for o in by_org.values():
        f = factories.get(o["org_code"])
        mes = bool(f and f["mes_enabled"])
        n_push = math.ceil(o["qty"] / max(1, int(f["batch_size"]))) if mes else 0
        pushes += n_push
        out_orgs.append(
            {
                "org_code": o["org_code"],
                "org_name": names.get(o["org_code"], ""),
                "mes_enabled": mes,
                "qty": o["qty"],
                "pushes": n_push,
                "bills": sorted(set(o["bills"])),
                "materials": sorted(o["materials"]),
            }
        )
    return {
        "pi_no": pi,
        "qty": sum(o["qty"] for o in out_orgs),
        "pushes": pushes,
        "orgs": sorted(out_orgs, key=lambda x: x["org_code"]),
        "skipped": skipped,
    }


def allocate(
    cu: CurrentUser, pi_in: str | None, bills: list[str] | None = None, lines: list[dict] | None = None
) -> dict:
    """分配：范围内待分配的号一次全部挂到生产组织；对接 MES 的改为待推送（未入批）并自动推送，未对接的改为待领取。"""
    from app.services import delivery

    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    with locks.with_pi_lock(pi):
        with db.tx():
            groups, skipped = _alloc_groups(pi, bills, lines)
            if not groups:
                raise biz(ErrorCode.ALLOC_NOTHING)
            cond, args = _alloc_scope(pi, bills, lines)
            now = util.now()
            total = 0
            started: set[str] = set()
            for g in groups:
                mes = factories.is_mes(g["org"])
                status = util.TO_PUSH if mes else util.TO_ACQUIRE
                gen_cond = "" if g["snapshot"] else " AND gen_org_code = %s"
                gen_args = [] if g["snapshot"] else [g["gen_org"]]
                per_line = db.all(
                    "SELECT bill_seq, COUNT(*) n, MIN(sn) a FROM sn_item WHERE pi_no = %s AND bill_no = %s "
                    "AND status = 'PENDING_ALLOC'" + gen_cond + cond + " GROUP BY bill_seq",
                    pi,
                    g["bill_no"],
                    *gen_args,
                    *args,
                )
                n = 0
                while True:
                    k = db.exec(
                        "UPDATE sn_item SET status = %s, factory_code = %s, push_type = 'NEW', allocated_at = %s, "
                        "updated_at = %s WHERE pi_no = %s AND bill_no = %s AND status = 'PENDING_ALLOC'"
                        + gen_cond
                        + cond
                        + " ORDER BY seq_dec LIMIT 5000",
                        status,
                        g["org"],
                        now,
                        now,
                        pi,
                        g["bill_no"],
                        *gen_args,
                        *args,
                    )
                    n += k
                    if k < 5000:
                        break
                for pl in per_line:
                    db.exec(
                        "UPDATE sn_line_count SET alloc_count = alloc_count + %s, updated_at = %s WHERE bill_no = %s AND seq = %s",
                        int(pl["n"]),
                        now,
                        g["bill_no"],
                        pl["bill_seq"],
                    )
                first, last = _range_of(pi, g["bill_no"], g["org"], now)
                audit.record(
                    cu,
                    audit.entry(audit.SN_ALLOCATE)
                    .factory(g["org"])
                    .pi(pi)
                    .bill(g["bill_no"])
                    .range(first, last)
                    .count(n)
                    .status(util.PENDING_ALLOC, status)
                    .info(f"mes={int(mes)}"),
                )
                total += n
                if mes:
                    started.add(g["org"])
            changes.refresh_items({g["bill_no"] for g in groups})
        for org in sorted(started):
            delivery.start_task(cu, org, pi)
    return {"pi_no": pi, "qty": total, "skipped": skipped, "orgs": sorted({g["org"] for g in groups})}


def _range_of(pi: str, bill: str, org: str, at) -> tuple[str | None, str | None]:
    rows = db.all(
        "SELECT MIN(sn) a, MAX(sn) b FROM (SELECT sn FROM sn_item WHERE pi_no = %s AND bill_no = %s AND factory_code = %s "
        "AND allocated_at = %s) t",
        pi,
        bill,
        org,
        at,
    )
    return (rows[0]["a"], rows[0]["b"]) if rows else (None, None)
