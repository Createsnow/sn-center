"""转厂（只在转厂页，没有对外接口）。

可转：待领取 / 待打印 / 已打印；来源限同一张 PI，范围 = 整张 PI、PI + 物料、单枚 SN。
工厂申请 → 号变申请中 → 总部确认（改挂转入目标，转入厂待领取）/ 驳回或申请厂撤回（恢复申请前状态）；
总部也可不经申请直接转。转入 PI 已有相同完整 SN 时拒绝整次转移。
流水归属（seq_pi_no）不变，不抬高转入 PI 的计数器；转入 PI 之后从「自己的最大号与转入号按其规则解析出的
最大流水」两者取大再加 1 生成（见 generate.NextStart），转厂时提示转入后的下一个号。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.core import encoding as codec
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit, factories, generate, orders, rules

MODE_APPLY = "APPLY"
MODE_DIRECT = "DIRECT"
PENDING = "PENDING"
APPROVED = "APPROVED"
REJECTED = "REJECTED"
WITHDRAWN = "WITHDRAWN"
SCOPE_PI = "PI"
SCOPE_MATERIAL = "MATERIAL"
SCOPE_SINGLE = "SINGLE"
SCOPES = frozenset((SCOPE_PI, SCOPE_MATERIAL, SCOPE_SINGLE))
TARGET_SNAPSHOT = "SNAPSHOT"
TARGET_MANUAL = "MANUAL"
ELIGIBLE = "('TO_ACQUIRE','TO_PRINT','PRINTED')"

FIELDS = (
    "id",
    "transfer_no",
    "mode",
    "status",
    "scope",
    "from_factory",
    "from_customer",
    "from_pi",
    "from_material",
    "from_sn",
    "qty",
    "to_factory",
    "to_customer",
    "to_pi",
    "to_material",
    "target_source",
    "reason",
    "applied_by",
    "applied_at",
    "decided_by",
    "decided_at",
    "decide_note",
)
_JOIN = "sn_item i JOIN sn_transfer_item t ON t.item_id = i.id AND t.gen_month = i.gen_month"


def transfer_json(t: dict | None) -> dict | None:
    return util.jrow(t, FIELDS)


@dataclass(frozen=True)
class Request:
    """转移请求。to_customer / to_material 为空 = 沿用原值。"""

    factory_code: str | None = None
    pi_no: str | None = None
    scope: str | None = None
    material_code: str | None = None
    sn: str | None = None
    to_factory: str | None = None
    to_customer: str | None = None
    to_pi: str | None = None
    to_material: str | None = None
    target_source: str | None = None
    reason: str | None = None


@dataclass(frozen=True)
class _Source:
    """已校验的来源范围。"""

    factory: str
    pi: str
    scope: str
    material: str
    sn: str

    def where(self) -> str:
        return {SCOPE_MATERIAL: " AND material_code = %s", SCOPE_SINGLE: " AND sn = %s"}.get(self.scope, "")

    def args(self) -> list:
        a: list = [self.factory, self.pi]
        if self.scope == SCOPE_MATERIAL:
            a.append(self.material)
        elif self.scope == SCOPE_SINGLE:
            a.append(self.sn)
        return a


def _source(factory: str, r: Request) -> _Source:
    pi = util.trim(r.pi_no)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    scope = util.trim(r.scope)
    if scope not in SCOPES:
        raise biz(ErrorCode.TR_SCOPE_INVALID)
    if scope == SCOPE_MATERIAL and r.material_code is None:
        raise biz(ErrorCode.TR_MATERIAL_REQUIRED)
    if scope == SCOPE_SINGLE and util.blank(r.sn):
        raise biz(ErrorCode.TR_SN_REQUIRED)
    return _Source(
        factory,
        pi,
        scope,
        r.material_code.strip() if scope == SCOPE_MATERIAL else "",
        r.sn.strip() if scope == SCOPE_SINGLE else "",
    )


# ================================================================== 预览


def candidates(cu: CurrentUser, r: Request) -> dict:
    """选定范围内各状态枚数（页面提交前展示）；填了转入 PI 时另给重复的号与转入后下一个号的提示。"""
    factory = factories.must_get(r.factory_code)["factory_code"] if cu.is_admin else cu.read_factory(r.factory_code)
    if factory is None:
        raise biz(ErrorCode.FACTORY_NOT_FOUND, factory="")
    s = _source(factory, r)
    by = {
        row["status"]: int(row["c"])
        for row in db.all(
            "SELECT status, COUNT(*) c FROM sn_item WHERE factory_code = %s AND pi_no = %s"
            + s.where()
            + " GROUP BY status",
            *s.args(),
        )
    }
    total = sum(by.values())
    ok = sum(v for k, v in by.items() if k in util.TRANSFERABLE)
    materials = db.column(
        "SELECT DISTINCT material_code FROM sn_item WHERE factory_code = %s AND pi_no = %s ORDER BY material_code",
        factory,
        s.pi,
    )
    out: dict = {"total": total, "transferable": ok, "by_status": by, "materials": materials}
    out.update(duplicates=None, target=None)
    to_pi = util.trim(r.to_pi)
    if to_pi and to_pi != s.pi:
        # 与 _create 锁定的范围一致：可转状态的号
        where = (
            " WHERE i.factory_code = %s AND i.pi_no = %s AND i.status IN "
            + ELIGIBLE
            + s.where().replace(" AND ", " AND i.")
        )
        dup_from = " FROM sn_item i JOIN sn_key k ON k.pi_no = %s AND k.sn = i.sn" + where
        n = db.count("SELECT COUNT(*)" + dup_from, to_pi, *s.args())
        if n:
            samples = db.column(f"SELECT i.sn{dup_from} ORDER BY i.sn LIMIT {DUP_SAMPLES}", to_pi, *s.args())
            out["duplicates"] = {"count": n, "samples": samples}
        sns = db.column("SELECT i.sn FROM sn_item i" + where, *s.args())
        out["target"] = target_hint(to_pi, util.trim(r.to_customer) or None, sns)
    return out


DUP_SAMPLES = 5


def target_hint(to_pi: str, customer: str | None, sns: list[str]) -> dict | None:
    """这批号转入后，转入 PI 下一次生成的起点会不会后移：会则返回前后的下一个号与不再生成的号数。

    按转入 PI 当前生效的规则解析；没有规则、或这批号按该规则解析不出（格式不同，不会重复）时返回 None。
    """
    r = rules.resolve(to_pi, orders.customer_of_pi(to_pi) or customer)
    if r is None:
        return None
    spec = rules.spec_of(r.version)
    seqs = {q for q in (codec.parse(sn, spec) for sn in sns) if q is not None}
    before = generate.next_start(to_pi, generate.counter(to_pi)["last_seq_dec"], spec)
    top = max(seqs, default=None)
    if top is None or top <= before.base:
        return None
    top_sn = codec.format_sn(top, spec)
    after = generate.NextStart(before.own_last, top, top_sn)
    unused = (top - before.base) - sum(1 for q in seqs if q > before.base)
    return {
        "to_pi": to_pi,
        "before": before.as_json(spec),
        "after": after.as_json(spec),
        "unused": unused,
    }


def _hint_of(t: dict) -> dict | None:
    if t["to_pi"] == t["from_pi"]:
        return None
    sns = db.column("SELECT sn FROM sn_transfer_item WHERE transfer_id = %s", t["id"])
    return target_hint(t["to_pi"], t["to_customer"] or t["from_customer"] or None, sns)


def _hint_text(h: dict | None) -> str:
    if h is None:
        return ""
    return f" to_next={h['before']['start_sn']}->{h['after']['start_sn']} unused={h['unused']}"


# ================================================================== 申请 / 直接转


def apply(cu: CurrentUser, r: Request) -> dict:
    with db.tx():
        if not cu.is_factory:
            raise biz(ErrorCode.FORBIDDEN)
        return _create(cu, cu.write_factory(r.factory_code), r, MODE_APPLY)


def direct(cu: CurrentUser, r: Request) -> dict:
    with db.tx():
        if not cu.is_admin:
            raise biz(ErrorCode.FORBIDDEN)
        return _create(cu, factories.must_get(r.factory_code)["factory_code"], r, MODE_DIRECT)


def _create(cu: CurrentUser, factory: str, r: Request, mode: str) -> dict:
    s = _source(factory, r)
    reason = util.trim(r.reason)
    if not reason:
        raise biz(ErrorCode.TR_REASON_REQUIRED)
    to_pi = util.trim(r.to_pi)
    if not to_pi:
        raise biz(ErrorCode.TR_TO_PI_REQUIRED)
    to_factory = factories.must_get(r.to_factory)["factory_code"]
    to_customer = util.trim(r.to_customer)
    to_material = util.trim(r.to_material)
    target = (
        TARGET_SNAPSHOT
        if util.trim(r.target_source) == TARGET_SNAPSHOT and _in_snapshot(to_factory, to_customer, to_pi, to_material)
        else TARGET_MANUAL
    )
    now = util.now()
    t = {
        "transfer_no": util.next_id("T"),
        "mode": mode,
        "status": PENDING,
        "scope": s.scope,
        "from_factory": factory,
        "from_customer": "",
        "from_pi": s.pi,
        "from_material": s.material,
        "from_sn": s.sn,
        "qty": 0,
        "to_factory": to_factory,
        "to_customer": to_customer,
        "to_pi": to_pi,
        "to_material": to_material,
        "target_source": target,
        "reason": util.cut(reason, 500),
        "applied_by": cu.emp_no,
        "applied_at": now,
    }
    cols = list(t)
    t["id"] = db.insert(
        f"INSERT INTO sn_transfer({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))})", *[t[c] for c in cols]
    )
    # 锁定范围内可转的号，记下原状态 / 原批次（回调据此识别已转走的号）
    n = db.exec(
        "INSERT INTO sn_transfer_item(transfer_id, item_id, gen_month, sn, seq_pi_no, seq_dec, from_status, "
        "from_customer, from_material, from_batch_no) SELECT %s, id, gen_month, sn, seq_pi_no, seq_dec, status, "
        "customer_code, material_code, batch_no FROM sn_item WHERE factory_code = %s AND pi_no = %s AND status IN "
        + ELIGIBLE
        + s.where()
        + " FOR UPDATE",
        t["id"],
        *s.args(),
    )
    if n == 0:
        if s.scope == SCOPE_SINGLE and db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_item WHERE factory_code = %s AND pi_no = %s AND sn = %s)",
            factory,
            s.pi,
            s.sn,
        ):
            raise biz(ErrorCode.TR_SN_NOT_TRANSFERABLE, sn=s.sn)
        raise biz(ErrorCode.TR_NOTHING)
    _check_same_target(t)
    _check_duplicates(t)
    from_customer = db.scalar("SELECT MIN(from_customer) FROM sn_transfer_item WHERE transfer_id = %s", t["id"])
    t["qty"] = n
    t["from_customer"] = from_customer or ""
    hint = _hint_of(t)
    if mode == MODE_APPLY:
        m = db.exec(
            f"UPDATE {_JOIN} SET i.prev_status = i.status, i.status = 'APPLYING', i.transfer_id = %s, "
            f"i.updated_at = %s WHERE t.transfer_id = %s AND i.status IN {ELIGIBLE}",
            t["id"],
            now,
            t["id"],
        )
        if m != n:
            raise biz(ErrorCode.TR_CONCURRENT)
        db.exec("UPDATE sn_transfer SET qty = %s, from_customer = %s WHERE id = %s", n, t["from_customer"], t["id"])
        audit.record(cu, _entry(audit.TRANSFER_APPLY, t, hint).status(None, util.APPLYING))
    else:
        _move(t, False, now)
        db.exec(
            "UPDATE sn_transfer SET qty = %s, from_customer = %s, status = %s, decided_by = %s, decided_at = %s "
            "WHERE id = %s",
            n,
            t["from_customer"],
            APPROVED,
            cu.emp_no,
            now,
            t["id"],
        )
        audit.record(cu, _entry(audit.TRANSFER_DIRECT, t, hint).status(None, util.TO_ACQUIRE))
    return _with_hint(db.one("SELECT * FROM sn_transfer WHERE id = %s", t["id"]), hint)


def _with_hint(t: dict, hint: dict | None) -> dict:
    out = transfer_json(t)
    out["target_hint"] = hint
    return out


def _in_snapshot(factory: str, customer: str, pi: str, material: str) -> bool:
    sql = (
        "SELECT EXISTS(SELECT 1 FROM sn_prd_mo m JOIN sn_factory f ON f.factory_name = m.prd_org_name "
        "WHERE f.factory_code = %s AND m.pi = %s"
    )
    args = [factory, pi]
    if customer:
        sql += " AND m.customer_number = %s"
        args.append(customer)
    if material:
        sql += " AND m.material_number = %s"
        args.append(material)
    return db.exists(sql + ")", *args)


def _check_same_target(t: dict) -> None:
    if t["to_factory"] != t["from_factory"] or t["to_pi"] != t["from_pi"]:
        return
    if not t["to_customer"] and not t["to_material"]:
        raise biz(ErrorCode.TR_SAME_TARGET)
    args: list = [t["id"]]
    diffs = []
    if t["to_customer"]:
        diffs.append("from_customer <> %s")
        args.append(t["to_customer"])
    if t["to_material"]:
        diffs.append("from_material <> %s")
        args.append(t["to_material"])
    changes = db.exists(
        "SELECT EXISTS(SELECT 1 FROM sn_transfer_item WHERE transfer_id = %s AND (" + " OR ".join(diffs) + "))", *args
    )
    if not changes:
        raise biz(ErrorCode.TR_SAME_TARGET)


def _check_duplicates(t: dict) -> None:
    """转入 PI 下已有相同完整 SN（不含本次这批自身）→ 拒绝整次转移。"""
    if t["to_pi"] == t["from_pi"]:
        return
    join = "FROM sn_transfer_item t JOIN sn_key k ON k.pi_no = %s AND k.sn = t.sn WHERE t.transfer_id = %s"
    n = db.count("SELECT COUNT(*) " + join, t["to_pi"], t["id"])
    if n:
        dup = db.column(f"SELECT t.sn {join} ORDER BY t.sn LIMIT {DUP_SAMPLES}", t["to_pi"], t["id"])
        raise biz(ErrorCode.TR_DUPLICATE, pi=t["to_pi"], count=n, sn="、".join(dup))


def _move(t: dict, from_applying: bool, now) -> None:
    """改挂到转入目标：查重护栏换 PI；号变为转入厂的待领取；批次 / 打印单清空。"""
    if t["to_pi"] != t["from_pi"]:
        # 与转入 PI 的生成互斥：生成持有该 PI 计数器行锁期间，不会有号转入
        db.exec(
            "INSERT INTO sn_pi_counter(pi_no, last_seq_dec, generated_qty, imported_qty, start_locked, updated_at) "
            "VALUES (%s,0,0,0,0,%s) ON DUPLICATE KEY UPDATE pi_no = pi_no",
            t["to_pi"],
            now,
        )
        db.all("SELECT pi_no FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", t["to_pi"])
    _check_duplicates(t)
    if t["to_pi"] != t["from_pi"]:
        db.exec(
            "DELETE k FROM sn_key k JOIN sn_transfer_item t ON k.pi_no = %s AND k.sn = t.sn WHERE t.transfer_id = %s",
            t["from_pi"],
            t["id"],
        )
        db.exec(
            "INSERT INTO sn_key(pi_no, sn, seq_pi_no, seq_dec) SELECT %s, sn, "
            "CASE WHEN seq_dec IS NULL THEN NULL ELSE seq_pi_no END, seq_dec FROM sn_transfer_item "
            "WHERE transfer_id = %s",
            t["to_pi"],
            t["id"],
        )
    guard = "i.status = 'APPLYING' AND i.transfer_id = %s" if from_applying else f"i.status IN {ELIGIBLE}"
    args = [
        t["to_pi"],
        t["to_factory"],
        t["to_customer"],
        t["to_customer"],
        t["to_material"],
        t["to_material"],
        now,
        now,
        t["id"],
    ]
    if from_applying:
        args.append(t["id"])
    n = db.exec(
        f"UPDATE {_JOIN} SET i.pi_no = %s, i.factory_code = %s, "
        "i.customer_code = IF(%s = '', i.customer_code, %s), i.material_code = IF(%s = '', i.material_code, %s), "
        "i.status = 'TO_ACQUIRE', i.prev_status = NULL, i.batch_no = NULL, i.print_no = NULL, i.transfer_id = NULL, "
        "i.acquired_at = NULL, i.printed_at = NULL, i.allocated_at = %s, i.updated_at = %s "
        "WHERE t.transfer_id = %s AND " + guard,
        *args,
    )
    if n != t["qty"]:
        raise biz(ErrorCode.TR_CONCURRENT)


def _entry(action: str, t: dict, hint: dict | None = None) -> audit.Entry:
    from_sn = t["from_sn"] or None
    detail = (
        f"scope={t['scope']} to={t['to_factory']}/{t['to_pi']}/{t['to_customer'] or '(原客户)'}/"
        f"{t['to_material'] or '(原物料)'} target={t['target_source']}" + _hint_text(hint)
    )
    return (
        audit.entry(action)
        .factory(t["from_factory"])
        .pi(t["from_pi"])
        .customer(t["from_customer"])
        .material(t["from_material"])
        .count(t["qty"])
        .transfer(t["transfer_no"])
        .why(t["reason"])
        .range(from_sn, from_sn)
        .info(detail)
    )


# ================================================================== 确认 / 驳回 / 撤回


def _lock_pending(tid: int) -> dict:
    t = db.one("SELECT * FROM sn_transfer WHERE id = %s FOR UPDATE", tid)
    if t is None:
        raise biz(ErrorCode.TR_NOT_FOUND)
    if t["status"] != PENDING:
        raise biz(ErrorCode.TR_NOT_PENDING, transfer_no=t["transfer_no"])
    return t


def approve(cu: CurrentUser, tid: int, note: str | None) -> dict:
    with db.tx():
        t = _lock_pending(tid)
        now = util.now()
        hint = _hint_of(t)
        _move(t, True, now)
        db.exec(
            "UPDATE sn_transfer SET status = %s, decided_by = %s, decided_at = %s, decide_note = %s WHERE id = %s",
            APPROVED,
            cu.emp_no,
            now,
            util.cut(util.trim_or_none(note), 500),
            tid,
        )
        audit.record(cu, _entry(audit.TRANSFER_APPROVE, t, hint).status(util.APPLYING, util.TO_ACQUIRE))
        return _with_hint(db.one("SELECT * FROM sn_transfer WHERE id = %s", tid), hint)


def reject(cu: CurrentUser, tid: int, note: str | None) -> dict:
    with db.tx():
        t = _lock_pending(tid)
        _restore(t)
        db.exec(
            "UPDATE sn_transfer SET status = %s, decided_by = %s, decided_at = %s, decide_note = %s WHERE id = %s",
            REJECTED,
            cu.emp_no,
            util.now(),
            util.cut(util.trim_or_none(note), 500),
            tid,
        )
        audit.record(cu, _entry(audit.TRANSFER_REJECT, t).status(util.APPLYING, "PREVIOUS"))
        return transfer_json(db.one("SELECT * FROM sn_transfer WHERE id = %s", tid))


def withdraw(cu: CurrentUser, tid: int) -> dict:
    with db.tx():
        t = _lock_pending(tid)
        if not cu.is_factory or cu.factory_code != t["from_factory"]:
            raise biz(ErrorCode.TR_WITHDRAW_FORBIDDEN)
        _restore(t)
        db.exec(
            "UPDATE sn_transfer SET status = %s, decided_by = %s, decided_at = %s WHERE id = %s",
            WITHDRAWN,
            cu.emp_no,
            util.now(),
            tid,
        )
        audit.record(cu, _entry(audit.TRANSFER_WITHDRAW, t).status(util.APPLYING, "PREVIOUS"))
        return transfer_json(db.one("SELECT * FROM sn_transfer WHERE id = %s", tid))


def _restore(t: dict) -> None:
    """驳回 / 撤回：恢复为申请前的状态。"""
    db.exec(
        f"UPDATE {_JOIN} SET i.status = i.prev_status, i.prev_status = NULL, i.transfer_id = NULL, "
        "i.updated_at = %s WHERE t.transfer_id = %s AND i.status = 'APPLYING' AND i.transfer_id = %s",
        util.now(),
        t["id"],
        t["id"],
    )


# ================================================================== 查询


def _direction(cu: CurrentUser, t: dict) -> str:
    """相对当前账户的方向：OUT 转出 / IN 转入 / 空 = 总部视角。"""
    if not cu.bound:
        return ""
    return "OUT" if cu.factory_code == t["from_factory"] else "IN"


def list_transfers(cu: CurrentUser, status: str | None, factory: str | None, pi: str | None, page: util.Page) -> dict:
    where = " WHERE 1=1"
    args: list = []
    if not util.blank(status):
        where += " AND status = %s"
        args.append(util.trim(status))
    if not util.blank(pi):
        where += " AND (from_pi = %s OR to_pi = %s)"
        args += [pi.strip(), pi.strip()]
    f = cu.read_factory(factory)
    if f is not None:
        where += " AND (from_factory = %s OR to_factory = %s)"
        args += [f, f]
    total = db.count("SELECT COUNT(*) FROM sn_transfer" + where, *args)
    rows = db.all(f"SELECT * FROM sn_transfer{where} ORDER BY id DESC LIMIT %s,%s", *args, page.offset, page.size)
    return util.page_result([{"transfer": transfer_json(t), "direction": _direction(cu, t)} for t in rows], total, page)


def _get(cu: CurrentUser, tid: int) -> dict:
    t = db.one("SELECT * FROM sn_transfer WHERE id = %s", tid)
    if t is None:
        raise biz(ErrorCode.TR_NOT_FOUND)
    if cu.bound and cu.factory_code not in (t["from_factory"], t["to_factory"]):
        raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=cu.factory_code)
    return t


def get(cu: CurrentUser, tid: int) -> dict:
    """转厂记录；待确认的附带转入后下一个号的提示（确认前展示）。"""
    t = _get(cu, tid)
    return _with_hint(t, _hint_of(t) if t["status"] == PENDING else None)


def items(cu: CurrentUser, tid: int, page: util.Page) -> dict:
    """转厂明细：转出厂也能查到这些号（已确认的标「已转出」）。"""
    t = _get(cu, tid)
    total = db.count("SELECT COUNT(*) FROM sn_transfer_item WHERE transfer_id = %s", tid)
    rows = db.all(
        "SELECT t.sn, t.seq_dec, t.seq_pi_no, t.from_status, t.from_customer, t.from_material, t.from_batch_no, "
        "i.status, i.factory_code, i.pi_no, i.rule_version_id FROM sn_transfer_item t "
        "LEFT JOIN sn_item i ON i.id = t.item_id AND i.gen_month = t.gen_month "
        "WHERE t.transfer_id = %s ORDER BY t.seq_pi_no, t.seq_dec, t.item_id LIMIT %s, %s",
        tid,
        page.offset,
        page.size,
    )
    out = []
    for r in rows:
        cur = r["status"]
        # 转出厂视角：确认后这些号已不属于本厂
        if (
            t["status"] == APPROVED
            and cu.bound
            and cu.factory_code == t["from_factory"]
            and cu.factory_code != r["factory_code"]
        ):
            cur = "TRANSFERRED_OUT"
        out.append(
            {
                "sn": r["sn"],
                "seq_text": rules.seq_text(r["rule_version_id"], r["seq_dec"]),
                "seq_dec": r["seq_dec"],
                "seq_pi_no": r["seq_pi_no"],
                "from_status": r["from_status"],
                "from_customer": r["from_customer"],
                "from_material": r["from_material"],
                "from_batch_no": r["from_batch_no"],
                "current_status": cur,
                "current_factory": r["factory_code"],
                "current_pi": r["pi_no"],
            }
        )
    return util.page_result(out, total, page)
