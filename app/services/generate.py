"""SN 生成与撤销（F-09）。分配见 allocation。

生成：在一张 PI 下整单（勾选单据，单据内按序号）或按物料行（按点选顺序）生成；最小单位是物料行——范围内每个
还有额度的物料行一次生成它的全部额度，不拆成两次。号都排在该 PI 的一套流水上，各物料行按自己的规则写出
（前缀 + 流水 + 分隔符 + 米数 + 分隔符 + 单位 + 后缀）。
必须先预演（只给出起止号、各物料行数量和号段，不落库），
预演只对发起人有效、默认 30 分钟、只能生成一次；范围或条件改变、所选规则版本被别的 PI 先用、PI 最大号变化、
同步改了范围内物料行数量时预演失效。
一次生成在一个事务里完成（共用一个生成批次），撞号整次回滚、不跳号；不超过 SN_GEN_SYNC_THRESHOLD 当场完成，
更多转后台任务（进度、可取消）；服务重启时没跑完的任务视为失败。起点 = 本 PI 自己生成的最大流水 + 1。
撤销：最小单位也是物料行——只能从这张 PI 流水最末的物料行号段往前撤（撤完一行，前一行成为最后，可接着撤）；
可一次撤一次生成中剩下的全部物料行，或撤到所选物料行为止（流水在其后的行一并撤，在一个事务里完成）；
号须全部仍待分配、都没转过单；号删除，最大号和计数一并退回，流水仍连续。
生成（含取消）、撤销与分配共用 PI 操作锁。
"""

from __future__ import annotations

import json
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
from app.services import audit, changes, locks, orders, rules

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
    "INSERT INTO sn_item(sn, pi_no, customer_code, bill_no, bill_seq, material_code, gen_org_code, gen_bill_no, "
    "gen_bill_seq, seq_pi_no, seq_dec, length_text, attach_no, rule_version_id, gen_batch, status, created_at, "
    "updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'PENDING_ALLOC',%s,%s)"
)

#: 大批量生成走后台线程
executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="sn-gen")


_LINE_FIELDS = (
    "bill_no",
    "seq",
    "material_code",
    "attach",
    "length",
    "unit",
    "qty",
    "start_sn",
    "end_sn",
    "rule_label",
)


def job_json(j: dict | None) -> dict | None:
    out = util.jrow(j, JOB_FIELDS)
    if out is not None and j is not None:
        plan = json.loads(j["plan_json"])
        # 早先的计划里没有附件项次、单位
        out["lines"] = [
            {k: x.get(k) for k in _LINE_FIELDS} | {"revoked": bool(x.get("revoked"))} for x in plan["items"]
        ]
        out["bills"] = sorted({x["bill_no"] for x in plan["items"]})
        out["revoked_qty"] = sum(x["qty"] for x in plan["items"] if x.get("revoked"))
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
    length: str
    length_source: str
    unit: str
    attach: str
    res: rules.Resolved | None
    block: str | None = None
    issues: list[str] = field(default_factory=list)

    @property
    def quota(self) -> int:
        return max(0, self.qty - self.own)


def _load_lines(pi: str, keys: list[tuple[str, int]]) -> list[Line]:
    """按给定顺序取物料行（快照 + 计数 + 附件项次 + 生效规则 + 单据冻结），并判断生成条件。"""
    if not keys:
        return []
    bills = sorted({k[0] for k in keys})
    snap = {
        (r["bill_no"], r["seq"]): r
        for r in db.all(f"SELECT * FROM sn_prd_mo WHERE bill_no IN ({marks(len(bills))})", *bills)
    }
    counts, fills, lens = orders.line_extras(keys)
    sels = rules.selections(keys)
    defaults: dict[str, rules.Resolved | None] = {}
    memo: dict[int, rules.Resolved | None] = {}
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
        length, length_src = orders.effective_length(r, lens.get(k))
        line = Line(
            r["bill_no"],
            r["seq"],
            r["material_code"],
            r["customer_code"],
            r["org_code"],
            r["pi"],
            orders.sn_qty(r),
            0 if c is None else int(c["own_generated"]),
            length,
            length_src,
            util.trim(r.get("unit")),
            attach,
            rules.effective(pi, r["customer_code"], sels.get(k), defaults[r["customer_code"]], memo=memo),
            blocks[r["bill_no"]],
        )
        _check(line)
        out.append(line)
    return out


def _check(line: Line) -> None:
    """生成条件（只对还有额度的物料行）。"""
    if line.quota <= 0:
        return
    if line.block:
        line.issues.append(line.block)
    if line.res is None:
        line.issues.append("NO_RULE")
    elif not line.res.ready:
        line.issues.append("RULE_PREFIX")
    spec = line.res.spec() if line.res is not None and line.res.ready else None
    # 米数选填（为空取默认 000），不挡生成；但金蝶米数含中文时须先在页面手动改过（规则不带米数时不影响）
    if line.length_source == orders.LENGTH_CHINESE and (spec is None or spec.with_length):
        line.issues.append("LENGTH_CHINESE")
    # 规则带单位时物料行须有产品单位（在金蝶补填后同步）
    if spec is not None and spec.with_unit and not line.unit:
        line.issues.append("UNIT_EMPTY")
    # 附件项次必填
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


def _plan(pi: str, scope: str, keys: list[tuple[str, int]], base: int) -> dict:
    """按排号顺序给每个还有额度的物料行切出一段号（整行额度，不拆行）。返回计划（不落库）；不能生成时 issues 非空。"""
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
    qty = quota
    if qty > settings.gen_max_per_run:
        raise biz(ErrorCode.GEN_QTY_OVER_MAX, qty=qty, max=settings.gen_max_per_run)
    cur = base + 1
    for x in todo:
        n = x.quota
        spec = x.res.spec()
        end = cur + n - 1
        mx = codec.max_seq(spec)
        if end > mx:
            raise biz(ErrorCode.GEN_RULE_EXHAUSTED, max=mx, end=end)
        length = x.length if spec.with_length else None
        unit = x.unit if spec.with_unit else None
        start_sn = codec.format_sn(cur, spec, length, unit)
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
                "unit": unit,
                "attach": x.attach,
                "line_qty": x.qty,
                "own_before": x.own,
                "qty": n,
                "start_seq": cur,
                "end_seq": end,
                "start_sn": start_sn,
                "end_sn": codec.format_sn(end, spec, length, unit),
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
        cur = end + 1
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
) -> dict:
    """预演：范围内每个还有额度的物料行整行生成。可生成时返回令牌与号段；不能生成时 generatable = false 并逐行列出原因
    （可缩小范围后再预演）。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    sc = util.trim(scope) or SCOPE_BILLS
    keys = _scope_keys(pi, sc, bills, lines)
    plan = _plan(pi, sc, keys, last_seq(pi))
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
        fresh = _plan(pi, stored["scope"], keys, actual)
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
        versions[x["rule_key"]] = rules.realize(cu, pi, res, same)
    for no in {x["bill_no"] for x in plan["items"]}:
        changes.ensure_ledger(no)
    done = 0
    for x in plan["items"]:
        v = versions[x["rule_key"]]
        spec = rules.spec_of(v)
        s = x["start_seq"]
        while s <= x["end_seq"]:
            e = min(x["end_seq"], s + CHUNK - 1)
            sns = [codec.format_sn(q, spec, x["length"], x.get("unit")) for q in range(s, e + 1)]
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
                    pi,
                    s + i,
                    x["length"],
                    x["attach"],
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
        "UPDATE sn_pi_counter SET last_seq_dec = %s, generated_qty = generated_qty + %s, updated_at = %s WHERE pi_no "
        "= %s",
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


def _tail(pi: str) -> tuple[dict, dict] | None:
    """这张 PI 流水排在最后的物料行号段：（所在的成功生成任务，号段）；没有时 None。"""
    last = last_seq(pi)
    if last == 0:
        return None
    for j in db.all(
        "SELECT * FROM sn_gen_job WHERE pi_no = %s AND status = %s AND start_seq_dec <= %s AND end_seq_dec >= %s "
        "ORDER BY id DESC",
        pi,
        SUCCESS,
        last,
        last,
    ):
        for x in json.loads(j["plan_json"])["items"]:
            if not x.get("revoked") and x["end_seq"] == last:
                return j, x
    return None


def _not_revocable_count(j: dict, x: dict) -> int:
    """一个物料行号段中已分配、或转过单（挂靠变了、版本号加过）的枚数；号已不在号池也算。"""
    ok = db.count(
        "SELECT COUNT(*) FROM sn_item WHERE seq_pi_no = %s AND seq_dec BETWEEN %s AND %s AND gen_batch = %s "
        "AND status = 'PENDING_ALLOC' AND rev = 1 AND bill_no = gen_bill_no AND bill_seq = gen_bill_seq "
        "AND pi_no = seq_pi_no",
        j["pi_no"],
        x["start_seq"],
        x["end_seq"],
        j["gen_batch"],
    )
    return x["qty"] - ok


def revocable(pi: str) -> dict | None:
    """当前可撤销的物料行（流水排在最后、号全部仍待分配且没转过单）；生成中或没有时 None。"""
    if db.exists("SELECT EXISTS(SELECT 1 FROM sn_gen_job WHERE running_pi = %s)", pi):
        return None
    t = _tail(pi)
    if t is None or _not_revocable_count(*t):
        return None
    j, x = t
    return {k: x[k] for k in ("bill_no", "seq", "qty", "start_sn", "end_sn")} | {"gen_batch": j["gen_batch"]}


def revoke_chain(pi_in: str | None) -> list[dict]:
    """PI 流水上还没撤销的物料行号段，从流水最末往前排。撤某一段须连同流水在它之后的段一起撤（从后往前），
    ok 标出撤到这一段是否可行：这一段及之后的号全部仍待分配、没转过单，且没有生成中的任务；
    bad 为这一段自己不可撤的枚数。"""
    pi = util.trim(pi_in)
    segs = sorted(
        (
            (j, x)
            for j in db.all("SELECT * FROM sn_gen_job WHERE pi_no = %s AND status = %s", pi, SUCCESS)
            for x in json.loads(j["plan_json"])["items"]
            if not x.get("revoked")
        ),
        key=lambda t: t[1]["end_seq"],
        reverse=True,
    )
    if not segs:
        return []
    ok_n = {
        (r["gen_batch"], r["gen_bill_no"], r["gen_bill_seq"]): int(r["n"])
        for r in db.all(
            "SELECT gen_batch, gen_bill_no, gen_bill_seq, COUNT(*) n FROM sn_item WHERE seq_pi_no = %s "
            "AND status = 'PENDING_ALLOC' AND rev = 1 AND bill_no = gen_bill_no AND bill_seq = gen_bill_seq "
            "AND pi_no = seq_pi_no GROUP BY gen_batch, gen_bill_no, gen_bill_seq",
            pi,
        )
    }
    good = not db.exists("SELECT EXISTS(SELECT 1 FROM sn_gen_job WHERE running_pi = %s)", pi)
    last = last_seq(pi)
    out = []
    for j, x in segs:
        bad = x["qty"] - ok_n.get((j["gen_batch"], x["bill_no"], x["seq"]), 0)
        good = good and bad == 0 and x["end_seq"] == last
        last = x["start_seq"] - 1
        out.append(
            {k: x[k] for k in ("bill_no", "seq", "qty", "start_sn", "end_sn")}
            | {"gen_batch": j["gen_batch"], "bad": bad, "ok": good}
        )
    return out


def _check_chain(pi: str, c: dict | None, targets: list[tuple[dict, dict, dict]]) -> None:
    """要撤的号段（已按流水从后往前排）须从 PI 流水末尾起依次相接，号全部仍待分配且没转过单。"""
    last = 0 if c is None else c["last_seq_dec"]
    for _, _, x in targets:
        if x["end_seq"] != last:
            raise biz(ErrorCode.GEN_REVOKE_NOT_LAST, pi=pi)
        last = x["start_seq"] - 1
    bad = sum(_not_revocable_count(j, x) for j, _, x in targets)
    if bad:
        raise biz(ErrorCode.GEN_REVOKE_ALLOCATED, count=bad)


def _revoke_in_tx(cu: CurrentUser, pi: str, targets: list[tuple[dict, dict, dict]], reason: str) -> tuple[int, int]:
    """在事务内撤销号段（任务、该任务的计划、号段；已按流水从后往前排好并核对过）：删号、退回计数和 PI 最大号，
    在各任务计划上标记已撤，任务的物料行全部撤完记为已撤销；每个任务按单据各留一条痕迹。返回（枚数，撤后的最大流水）。"""
    now = util.now()
    mark = {"at": util.fmt(now), "by": cu.emp_no, "reason": util.cut(reason, 500)}
    for j, _, x in targets:
        n = db.exec(
            "DELETE FROM sn_item WHERE seq_pi_no = %s AND seq_dec BETWEEN %s AND %s AND gen_batch = %s "
            "AND status = 'PENDING_ALLOC'",
            pi,
            x["start_seq"],
            x["end_seq"],
            j["gen_batch"],
        )
        if n != x["qty"]:
            raise biz(ErrorCode.CONFLICT_RETRY)
        db.exec(
            "UPDATE sn_line_count SET own_generated = own_generated - %s, gen_count = gen_count - %s, "
            "updated_at = %s WHERE bill_no = %s AND seq = %s",
            x["qty"],
            x["qty"],
            now,
            x["bill_no"],
            x["seq"],
        )
        x["revoked"] = mark
    qty = sum(x["qty"] for _, _, x in targets)
    last = targets[-1][2]["start_seq"] - 1
    db.exec(
        "UPDATE sn_pi_counter SET last_seq_dec = %s, generated_qty = generated_qty - %s, updated_at = %s "
        "WHERE pi_no = %s",
        last,
        qty,
        now,
        pi,
    )
    jobs_of: dict[int, tuple[dict, dict, list[dict]]] = {}
    for j, plan, x in reversed(targets):
        jobs_of.setdefault(j["id"], (j, plan, []))[2].append(x)
    for j, plan, items in jobs_of.values():
        whole = all(x.get("revoked") for x in plan["items"])
        db.exec(
            "UPDATE sn_gen_job SET status = %s, plan_json = %s, revoke_reason = %s WHERE id = %s",
            REVOKED if whole else SUCCESS,
            json.dumps(plan, ensure_ascii=False, separators=(",", ":")),
            util.cut(reason, 500),
            j["id"],
        )
        for no, xs in _by_bill(items).items():
            audit.record(
                cu,
                audit.entry(audit.SN_REVOKE)
                .factory(xs[0]["org_code"])
                .pi(pi)
                .bill(no)
                .range(xs[0]["start_sn"], xs[-1]["end_sn"])
                .count(sum(x["qty"] for x in xs))
                .status(util.PENDING_ALLOC, None)
                .batch(j["gen_batch"])
                .why(reason)
                .info(
                    f"job={j['id']} lines="
                    + ",".join(f"{x['seq']}:{x['qty']}@{x['start_sn']}~{x['end_sn']}" for x in xs)
                    + f" last_seq -> {last}"
                ),
            )
    return qty, last


def _reason(reason_in: str | None) -> str:
    reason = util.trim(reason_in)
    if not reason or len(reason) > 500:
        raise biz(ErrorCode.REASON_REQUIRED)
    return reason


def revoke(
    cu: CurrentUser,
    batch: str | None,
    reason_in: str | None,
    bill_no: str | None = None,
    seq: int | None = None,
) -> dict:
    """撤销生成，最小单位物料行：给了单据和序号时只撤这一行，否则撤这次生成中剩下的全部物料行。
    要撤的号段须从 PI 流水末尾起依次相接（即只能从后往前撤），号全部仍待分配且没转过单。"""
    reason = _reason(reason_in)
    j0 = _job_by_batch(batch)
    pi = j0["pi_no"]
    with locks.with_pi_lock(pi), db.tx():
        c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", pi)
        j = _job_by_batch(batch, lock=True)
        if j["status"] != SUCCESS:
            raise biz(ErrorCode.GEN_REVOKE_NOT_SUCCESS)
        plan = json.loads(j["plan_json"])
        left = [x for x in plan["items"] if not x.get("revoked")]
        if bill_no is not None:
            no = util.trim(bill_no)
            left = [x for x in left if x["bill_no"] == no and x["seq"] == seq]
            if not left:
                raise biz(ErrorCode.GEN_REVOKE_LINE_INVALID)
        targets = [(j, plan, x) for x in sorted(left, key=lambda x: x["end_seq"], reverse=True)]
        _check_chain(pi, c, targets)
        qty, last = _revoke_in_tx(cu, pi, targets, reason)
        whole = all(x.get("revoked") for x in plan["items"])
    return {
        "gen_batch": j["gen_batch"],
        "pi_no": pi,
        "qty": qty,
        "lines": [{"bill_no": x["bill_no"], "seq": x["seq"], "qty": x["qty"]} for _, _, x in reversed(targets)],
        "last_seq_dec": last,
        "job_status": REVOKED if whole else SUCCESS,
    }


def revoke_lines(cu: CurrentUser, pi_in: str | None, lines: list[dict] | None, reason_in: str | None) -> dict:
    """按物料行撤销（可多行，跨多次生成）：从 PI 流水末尾往前，一直撤到所选物料行中流水最靠前的那一段为止，
    流水排在其后的物料行一并撤销；在一个事务里完成，号须全部仍待分配且没转过单，否则整次不撤。"""
    reason = _reason(reason_in)
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    keys = {(util.trim(x.get("bill_no")), int(x.get("seq") or 0)) for x in lines or []}
    if not keys:
        raise biz(ErrorCode.RULE_LINE_REQUIRED)
    with locks.with_pi_lock(pi), db.tx():
        if db.exists("SELECT EXISTS(SELECT 1 FROM sn_gen_job WHERE running_pi = %s)", pi):
            raise biz(ErrorCode.GEN_BUSY, pi=pi)
        c = db.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", pi)
        segs = []
        for j in db.all("SELECT * FROM sn_gen_job WHERE pi_no = %s AND status = %s FOR UPDATE", pi, SUCCESS):
            plan = json.loads(j["plan_json"])
            segs += [(j, plan, x) for x in plan["items"] if not x.get("revoked")]
        picked = [x for _, _, x in segs if (x["bill_no"], x["seq"]) in keys]
        if not picked:
            raise biz(ErrorCode.GEN_REVOKE_LINE_INVALID)
        start = min(x["start_seq"] for x in picked)
        targets = sorted((t for t in segs if t[2]["start_seq"] >= start), key=lambda t: t[2]["end_seq"], reverse=True)
        _check_chain(pi, c, targets)
        qty, last = _revoke_in_tx(cu, pi, targets, reason)
    return {
        "pi_no": pi,
        "qty": qty,
        "lines": [
            {"gen_batch": j["gen_batch"], "bill_no": x["bill_no"], "seq": x["seq"], "qty": x["qty"]}
            for j, _, x in reversed(targets)
        ],
        "last_seq_dec": last,
    }


def jobs(pi: str | None, limit: int = 20) -> list[dict]:
    """最近的生成任务；revocable 标出可整次撤销（剩下的物料行都能从流水末尾依次撤掉）的那一次，
    各物料行的 revocable 标出当前可单独撤销的那一行。"""
    pi = util.trim(pi)
    return jobs_with(pi, limit, revocable(pi))


def jobs_with(pi: str, limit: int, rb: dict | None) -> list[dict]:
    """同 jobs，可撤销的物料行由调用方算好传入（生成详情里另要单独返回它）。"""
    rows = db.all("SELECT * FROM sn_gen_job WHERE pi_no = %s ORDER BY id DESC LIMIT %s", pi, min(max(limit, 1), 200))
    out = []
    for j in rows:
        v = job_json(j)
        mine = rb is not None and rb["gen_batch"] == j["gen_batch"]
        for line in v["lines"]:
            line["revocable"] = mine and (line["bill_no"], line["seq"]) == (rb["bill_no"], rb["seq"])
        v["revocable"] = mine and not any(
            _not_revocable_count(j, x) for x in json.loads(j["plan_json"])["items"] if not x.get("revoked")
        )
        out.append(v)
    return out
