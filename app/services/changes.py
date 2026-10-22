"""订单变更处理（F-06）与单据暂停交付（5.2.10）。

台账：单据第一次生成号时建立（sn_bill_ledger + _line），记最近一次在快照中时的 PI、客户、生产组织和各物料行数量；
表头只在总部「确认变更」时改为新值，所以反审核修改后再重新审核回来，比对仍能发现其间的变化。

每次同步后对变动的已出号单据比对：
- PI / 生产组织 / 客户变了、记录过已生成数的物料行失去对应（序号不在或物料变了）：提醒，该单未交付的号暂停
  （CHANGE）；PI 变了另冻结该单生成和分配；总部处理后解除。
- 物料行数量下调到本单生成数以下：只提示。页面填写过附件项次的物料行金蝶后来有值：只提示（不限已出号单据）。
- 单据离开快照：按单号查一次。查到、单据状态不是已审核 → 反审核中（REVERSAL），未交付的号暂停，
  之后每个比对周期复查；查无此单（已删除）→ 不暂停、已暂停的解除；查询失败按反审核中处理。
  重新审核回来：没有变化或只有数量变化时自动解除；有其他变化继续暂停（CHANGE）至总部确认。
  单据实际已作废：总部「确认已作废」解除。

暂停标记记在号上（sn_item.suspend），只作用于未交付的号（待分配、待推送未入批、已分配）；
已入批（批次未确认或失败）、已推送、已领取的号不暂停。号的单据或状态变化后用 refresh_items 按单据重算。
"""

from __future__ import annotations

import threading
from collections.abc import Iterable

import structlog

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, factories, k3

log = structlog.get_logger()

#: 提醒类型
PI = "PI"
ORG = "ORG"
CUSTOMER = "CUSTOMER"
QTY = "QTY"
MATERIAL = "MATERIAL"
ATTACH = "ATTACH"
REVERSAL = "REVERSAL"
DELETED = "DELETED"
#: 未处理时暂停交付的提醒类型
BLOCKING = (PI, ORG, CUSTOMER, MATERIAL)
#: 处理方式
CONFIRM = "CONFIRM"
MAP = "MAP"
UNRELATED = "UNRELATED"
VOID = "VOID"
ACK = "ACK"
AUTO = "AUTO"
_ALLOWED = {
    PI: (CONFIRM,),
    ORG: (CONFIRM,),
    CUSTOMER: (CONFIRM,),
    MATERIAL: (MAP, UNRELATED),
    QTY: (ACK,),
    ATTACH: (ACK,),
    REVERSAL: (VOID,),
    DELETED: (ACK,),
}

EMPTY_STATE = {"suspend": None, "leave_state": None, "pi_frozen": False, "open_alerts": 0, "issued": False}

#: 未交付且未入批（暂停交付作用的范围）
_UNDELIVERED = "(status IN ('PENDING_ALLOC','ALLOCATED') OR (status = 'TO_PUSH' AND delivery_batch_no IS NULL))"


# ================================================================== 计数 / 状态


def count_daily(kind: str, factory: str = "") -> None:
    db.exec(
        "INSERT INTO sn_daily_counter(day, kind, factory_code, n) VALUES (%s,%s,%s,1) "
        "ON DUPLICATE KEY UPDATE n = n + 1",
        util.today(),
        kind,
        factory,
    )


def bill_states(bills: list[str]) -> dict[str, dict]:
    if not bills:
        return {}
    ph = marks(len(bills))
    alerts = {
        r["bill_no"]: int(r["n"])
        for r in db.all(
            f"SELECT bill_no, COUNT(*) n FROM sn_order_alert WHERE status = 'OPEN' AND bill_no IN ({ph}) "
            "GROUP BY bill_no",
            *bills,
        )
    }
    out = {}
    for r in db.all(f"SELECT * FROM sn_bill_ledger WHERE bill_no IN ({ph})", *bills):
        out[r["bill_no"]] = {
            "suspend": r["suspend"],
            "leave_state": r["leave_state"],
            "pi_frozen": bool(r["pi_frozen"]),
            "open_alerts": alerts.get(r["bill_no"], 0),
            "issued": True,
        }
    for b, n in alerts.items():
        out.setdefault(b, {**EMPTY_STATE, "open_alerts": n})
    return out


def ledger(bill_no: str, lock: bool = False) -> dict | None:
    return db.one("SELECT * FROM sn_bill_ledger WHERE bill_no = %s" + (" FOR UPDATE" if lock else ""), bill_no)


def generation_block(bill_no: str) -> str | None:
    """该单不能生成的原因：PI 变更未确认（冻结）/ 有未处理的「失去对应」；没有返回 None。"""
    L = ledger(bill_no)
    if L is not None and L["pi_frozen"]:
        return "PI_FROZEN"
    if db.exists(
        "SELECT EXISTS(SELECT 1 FROM sn_order_alert WHERE bill_no = %s AND alert_type = %s AND status = 'OPEN')",
        bill_no,
        MATERIAL,
    ):
        return "MATERIAL_LOST"
    return None


def allocation_block(bill_no: str) -> str | None:
    """该单不能分配的原因：PI 冻结 / 反审核中 / 暂停交付。"""
    L = ledger(bill_no)
    if L is None:
        return None
    if L["pi_frozen"]:
        return "PI_FROZEN"
    if L["leave_state"] == REVERSAL:
        return "REVERSAL"
    if L["suspend"]:
        return "SUSPENDED"
    return None


def open_alert_count(cu: CurrentUser) -> int:
    fsql, fargs = factories.scope_sql(cu.factories or None, "l.org_code")
    return db.count(
        "SELECT COUNT(*) FROM sn_order_alert a LEFT JOIN sn_bill_ledger l ON l.bill_no = a.bill_no "
        "WHERE a.status = 'OPEN'" + fsql,
        *fargs,
    )


# ================================================================== 台账


def ensure_ledger(bill_no: str) -> None:
    """单据第一次生成号时建立台账（生成事务内调用）。"""
    if ledger(bill_no) is not None:
        return
    rows = db.all("SELECT * FROM sn_prd_mo WHERE bill_no = %s ORDER BY seq", bill_no)
    if not rows:
        return
    h = rows[0]
    now = util.now()
    db.exec(
        "INSERT IGNORE INTO sn_bill_ledger(bill_no, pi, customer_code, org_code, org_name, created_at, updated_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s)",
        bill_no,
        h["pi"],
        h["customer_code"],
        h["org_code"],
        h["org_name"],
        now,
        now,
    )
    db.exec_many(
        "INSERT IGNORE INTO sn_bill_ledger_line(bill_no, seq, qty) VALUES (%s,%s,%s)",
        [(bill_no, r["seq"], r["qty"]) for r in rows],
    )


# ================================================================== 暂停交付


def _set_items_suspend(bill_no: str, reason: str | None) -> int:
    """把该单未交付、未入批的号的暂停标记改为 reason。"""
    total = db.exec(
        f"UPDATE sn_item SET suspend = %s, updated_at = %s WHERE bill_no = %s AND {_UNDELIVERED} "
        "AND NOT (suspend <=> %s)",
        reason,
        util.now(),
        bill_no,
        reason,
    )
    # 已交付或已入批的号不暂停
    db.exec(
        f"UPDATE sn_item SET suspend = NULL WHERE bill_no = %s AND suspend IS NOT NULL AND NOT {_UNDELIVERED}",
        bill_no,
    )
    return total


def refresh_items(bill_nos: Iterable[str]) -> None:
    """号的单据或状态变化后（生成、作废批次退回、转单、驳回申请……），按单据台账重算号上的暂停标记。"""
    for no in sorted(set(bill_nos)):
        L = ledger(no)
        _set_items_suspend(no, None if L is None else L["suspend"])


def _apply_suspend(cu: CurrentUser, bill_no: str, reason: str | None) -> None:
    L = ledger(bill_no, lock=True)
    if L is None or L["suspend"] == reason:
        if L is not None:
            _set_items_suspend(bill_no, reason)
        return
    db.exec("UPDATE sn_bill_ledger SET suspend = %s, updated_at = %s WHERE bill_no = %s", reason, util.now(), bill_no)
    n = _set_items_suspend(bill_no, reason)
    audit.record(
        cu,
        audit.entry(audit.ORDER_RESUME if reason is None else audit.ORDER_SUSPEND)
        .bill(bill_no)
        .pi(L["pi"])
        .factory(L["org_code"])
        .count(n)
        .info(f"suspend {L['suspend'] or '-'} -> {reason or '-'}"),
    )
    if reason is None:
        _pending_kicks().add(bill_no)


#: 本线程处理中解除暂停的单据：事务提交后启动推送
_local = threading.local()


def _pending_kicks() -> set[str]:
    if not hasattr(_local, "kicks"):
        _local.kicks = set()
    return _local.kicks


def kick_resumed() -> None:
    """事务提交后：本线程处理中解除暂停的单据启动推送。"""
    from app.services import delivery

    bills = sorted(_pending_kicks())
    _pending_kicks().clear()
    for no in bills:
        try:
            delivery.kick_bill(no)
        except Exception:  # noqa: BLE001
            log.exception("kick_after_resume_failed", bill=no)


def _reevaluate(cu: CurrentUser, bill_no: str) -> None:
    """按未处理提醒重算该单的暂停与 PI 冻结。"""
    L = ledger(bill_no, lock=True)
    if L is None:
        return
    open_types = set(db.column("SELECT alert_type FROM sn_order_alert WHERE bill_no = %s AND status = 'OPEN'", bill_no))
    frozen = 1 if PI in open_types else 0
    if frozen != L["pi_frozen"]:
        db.exec("UPDATE sn_bill_ledger SET pi_frozen = %s WHERE bill_no = %s", frozen, bill_no)
    if L["leave_state"] == REVERSAL:
        reason = util.SUSPEND_REVERSAL
    elif open_types & set(BLOCKING):
        reason = util.SUSPEND_CHANGE
    else:
        reason = None
    _apply_suspend(cu, bill_no, reason)


# ================================================================== 提醒


def _alert(cu: CurrentUser, bill_no: str, typ: str, seq: int | None, before: str, after: str) -> None:
    """同一单据、类型、物料行只保留一条未处理提醒（变化后的值更新）。"""
    cur = db.one(
        "SELECT id, after_value FROM sn_order_alert WHERE bill_no = %s AND alert_type = %s AND seq <=> %s "
        "AND status = 'OPEN' FOR UPDATE",
        bill_no,
        typ,
        seq,
    )
    now = util.now()
    if cur is not None:
        if cur["after_value"] != after:
            db.exec(
                "UPDATE sn_order_alert SET after_value = %s, updated_at = %s WHERE id = %s",
                util.cut(after, 255),
                now,
                cur["id"],
            )
        return
    db.insert(
        "INSERT INTO sn_order_alert(bill_no, alert_type, seq, before_value, after_value, status, created_at, "
        "updated_at) VALUES (%s,%s,%s,%s,%s,'OPEN',%s,%s)",
        bill_no,
        typ,
        seq,
        util.cut(before, 255),
        util.cut(after, 255),
        now,
        now,
    )
    L = ledger(bill_no)
    audit.record(
        cu,
        audit.entry(audit.ORDER_ALERT)
        .bill(bill_no)
        .pi(None if L is None else L["pi"])
        .factory(None if L is None else L["org_code"])
        .info(f"type={typ} seq={seq if seq is not None else '-'} {before} -> {after}"),
    )


def _close_auto(cu: CurrentUser, bill_no: str, types: Iterable[str], note: str, keep: set | None = None) -> None:
    """关闭不再适用的提醒；keep = 仍适用的 (类型, 序号)。"""
    for a in db.all(
        f"SELECT id, alert_type, seq FROM sn_order_alert WHERE bill_no = %s AND status = 'OPEN' "
        f"AND alert_type IN ({marks(len(tuple(types)))})",
        bill_no,
        *types,
    ):
        if keep is not None and (a["alert_type"], a["seq"]) in keep:
            continue
        _close(cu, a["id"], AUTO, note)


def _close(cu: CurrentUser, alert_id: int, action: str, note: str | None) -> None:
    db.exec(
        "UPDATE sn_order_alert SET status = 'CLOSED', closed_by = %s, closed_at = %s, close_action = %s, note = %s, "
        "updated_at = %s WHERE id = %s",
        cu.emp_no,
        util.now(),
        action,
        util.cut(note, 500),
        util.now(),
        alert_id,
    )


# ================================================================== 同步后的比对


def after_sync(cu: CurrentUser, present: list[str], removed: list[str]) -> list[str]:
    """同步替换 / 移出单据后：附件项次金蝶已有值提示；已出号单据的变更比对。返回提示类型列表（附件项次计数用）。"""
    notes: list[str] = []
    for no in present:
        try:
            with db.tx():
                notes += _attach_k3(cu, no)
                if ledger(no) is not None:
                    _on_present(cu, no)
            kick_resumed()
        except Exception:  # noqa: BLE001
            log.exception("order_change_compare_failed", bill=no)
    for no in removed:
        try:
            after_removed(cu, no, None)
        except Exception:  # noqa: BLE001
            log.exception("order_change_remove_failed", bill=no)
    return notes


def _attach_k3(cu: CurrentUser, bill_no: str) -> list[str]:
    """页面填写过附件项次的行金蝶后来返回非空值：标「金蝶已有值」并提醒（之后生成以金蝶值为准，填写值保留）。"""
    out = []
    for r in db.all(
        "SELECT m.seq, m.attach_no, a.manual_value FROM sn_line_attach a "
        "JOIN sn_prd_mo m ON m.bill_no = a.bill_no AND m.seq = a.seq "
        "WHERE a.bill_no = %s AND m.attach_no <> '' AND a.k3_value <> m.attach_no",
        bill_no,
    ):
        db.exec(
            "UPDATE sn_line_attach SET k3_value = %s, updated_at = %s WHERE bill_no = %s AND seq = %s",
            r["attach_no"],
            util.now(),
            bill_no,
            r["seq"],
        )
        _alert(cu, bill_no, ATTACH, r["seq"], r["manual_value"], r["attach_no"])
        out.append(ATTACH)
    return out


def _on_present(cu: CurrentUser, bill_no: str) -> None:
    """单据在快照中：与台账比对，生成 / 更新 / 关闭提醒，重算暂停。"""
    from app.services.orders import sn_qty

    L = ledger(bill_no, lock=True)
    rows = db.all("SELECT * FROM sn_prd_mo WHERE bill_no = %s ORDER BY seq", bill_no)
    if L is None or not rows:
        return
    h = rows[0]
    keep: set = set()
    for typ, before, after in (
        (PI, L["pi"], h["pi"]),
        (ORG, L["org_code"], h["org_code"]),
        (CUSTOMER, L["customer_code"], h["customer_code"]),
    ):
        if before != after:
            label = after if typ != ORG else f"{after} {h['org_name']}".strip()
            _alert(cu, bill_no, typ, None, before if typ != ORG else f"{before} {L['org_name']}".strip(), label)
            keep.add((typ, None))
    by_seq = {r["seq"]: r for r in rows}
    counts = db.all(
        "SELECT seq, material_code, own_generated, gen_count FROM sn_line_count WHERE bill_no = %s", bill_no
    )
    for c in counts:
        if not (c["gen_count"] > 0 or c["own_generated"] > 0):
            continue
        line = by_seq.get(c["seq"])
        if line is None or line["material_code"] != c["material_code"]:
            _alert(
                cu,
                bill_no,
                MATERIAL,
                c["seq"],
                c["material_code"] or "(空)",
                "(删除)" if line is None else (line["material_code"] or "(空)"),
            )
            keep.add((MATERIAL, c["seq"]))
    # 不再成立的变化（如又改回去）自动关闭
    _close_auto(cu, bill_no, BLOCKING, "比对已无差异", keep)
    # 数量下调到本单生成数以下：只提示
    old_qty = {
        r["seq"]: r["qty"] for r in db.all("SELECT seq, qty FROM sn_bill_ledger_line WHERE bill_no = %s", bill_no)
    }
    own = {c["seq"]: int(c["own_generated"]) for c in counts}
    for r in rows:
        q = sn_qty(r)
        if r["seq"] in old_qty and old_qty[r["seq"]] != r["qty"] and q < own.get(r["seq"], 0):
            _alert(
                cu,
                bill_no,
                QTY,
                r["seq"],
                str(int(old_qty[r["seq"]])),
                f"{q}（本单已生成 {own[r['seq']]}，多出 {own[r['seq']] - q}）",
            )
    db.exec_many(
        "INSERT INTO sn_bill_ledger_line(bill_no, seq, qty) VALUES (%s,%s,%s) "
        "ON DUPLICATE KEY UPDATE qty = VALUES(qty)",
        [(bill_no, r["seq"], r["qty"]) for r in rows],
    )
    if L["leave_state"] is not None or L["left_at"] is not None:
        if L["leave_state"] is not None:
            _close_auto(cu, bill_no, (REVERSAL,), "重新审核回到快照")
        db.exec(
            "UPDATE sn_bill_ledger SET leave_state = NULL, left_at = NULL, updated_at = %s WHERE bill_no = %s",
            util.now(),
            bill_no,
        )
    _reevaluate(cu, bill_no)


def after_removed(cu: CurrentUser, bill_no: str, view: k3.BillView | None) -> None:
    """已出号单据离开快照：按单号查一次（view 已查过时直接用），按结果暂停或解除。"""
    if ledger(bill_no) is None:
        return
    if view is None:
        try:
            view = k3.view_bill(bill_no)
        except BizError as e:
            log.warning("bill_view_failed", bill=bill_no, err=e.message)
            view = None
    with db.tx():
        _on_absent(cu, bill_no, view)
    kick_resumed()


def _on_absent(cu: CurrentUser, bill_no: str, view: k3.BillView | None) -> None:
    L = ledger(bill_no, lock=True)
    if L is None:
        return
    now = util.now()
    if view is not None and view.found and view.doc_status == k3.APPROVED:
        # 期间又审核回来：按单号写回快照后照常比对
        from app.services import orders

        orders._replace_bills({bill_no: view.rows}, now)
        _on_present(cu, bill_no)
        return
    db.exec(
        "UPDATE sn_bill_ledger SET left_at = COALESCE(left_at, %s), updated_at = %s WHERE bill_no = %s",
        now,
        now,
        bill_no,
    )
    if view is not None and not view.found:
        # 已删除：不暂停（已暂停的解除），之后处理同已确认作废
        if L["leave_state"] == DELETED:
            return
        db.exec("UPDATE sn_bill_ledger SET leave_state = %s WHERE bill_no = %s", DELETED, bill_no)
        _close_auto(cu, bill_no, (*BLOCKING, REVERSAL), "单据已在金蝶删除")
        _alert(cu, bill_no, DELETED, None, "", "查无此单")
        _reevaluate(cu, bill_no)
        return
    if L["leave_state"] == "VOID_CONFIRMED":
        return
    # 查到、不是已审核（含已作废，不区分）；查询失败也按反审核中，下个周期再查
    status = "?" if view is None else (view.doc_status or "?")
    if L["leave_state"] != REVERSAL:
        db.exec("UPDATE sn_bill_ledger SET leave_state = %s WHERE bill_no = %s", REVERSAL, bill_no)
    _alert(cu, bill_no, REVERSAL, None, "C", status)
    _reevaluate(cu, bill_no)


def recheck_reversed(cu: CurrentUser, approved: set[str]) -> None:
    """比对同步周期内：反审核中的已出号单据按单号复查（重新审核回来的已在比对中处理，这里只查仍不在的）。"""
    for no in db.column("SELECT bill_no FROM sn_bill_ledger WHERE leave_state = %s", REVERSAL):
        if no in approved:
            continue
        try:
            after_removed(cu, no, None)
        except Exception:  # noqa: BLE001
            log.exception("reversal_recheck_failed", bill=no)


def after_rebuild(cu: CurrentUser) -> None:
    """全量重建后对全部已出号单据比对。"""
    in_snap = set(db.column("SELECT DISTINCT bill_no FROM sn_prd_mo"))
    present, absent = [], []
    for L in db.all("SELECT bill_no, leave_state FROM sn_bill_ledger"):
        if L["bill_no"] in in_snap:
            present.append(L["bill_no"])
        elif L["leave_state"] not in ("VOID_CONFIRMED", DELETED):
            absent.append(L["bill_no"])
    after_sync(cu, present, absent)


# ================================================================== 提醒列表与处理


def list_alerts(cu: CurrentUser, status: str | None, bill_no: str | None, typ: str | None, page: util.Page) -> dict:
    where = " WHERE 1=1"
    args: list = []
    st = util.trim(status) or "OPEN"
    if st != "ALL":
        where += " AND a.status = %s"
        args.append(st)
    if not util.blank(bill_no):
        where += " AND a.bill_no = %s"
        args.append(util.trim(bill_no))
    if not util.blank(typ):
        where += " AND a.alert_type = %s"
        args.append(util.trim(typ))
    fsql, fargs = factories.scope_sql(cu.factories or None, "l.org_code")
    where += fsql
    args += fargs
    src = " FROM sn_order_alert a LEFT JOIN sn_bill_ledger l ON l.bill_no = a.bill_no" + where
    total = db.count("SELECT COUNT(*)" + src, *args)
    rows = db.all(
        "SELECT a.*, l.pi, l.org_code, l.org_name, l.suspend, l.leave_state" + src + " ORDER BY a.id DESC LIMIT %s, %s",
        *args,
        page.offset,
        page.size,
    )
    counts = _bill_counts([r["bill_no"] for r in rows])
    items = []
    for r in rows:
        c = counts.get(r["bill_no"], {})
        items.append(
            {
                "id": r["id"],
                "bill_no": r["bill_no"],
                "alert_type": r["alert_type"],
                "seq": r["seq"],
                "before_value": r["before_value"],
                "after_value": r["after_value"],
                "status": r["status"],
                "created_at": util.fmt(r["created_at"]),
                "closed_by": r["closed_by"],
                "closed_at": util.fmt(r["closed_at"]),
                "close_action": r["close_action"],
                "note": r["note"],
                "pi": r["pi"],
                "org_code": r["org_code"],
                "org_name": r["org_name"],
                "suspend": r["suspend"],
                "leave_state": r["leave_state"],
                "suspended_qty": c.get("suspended", 0),
                "delivered_qty": c.get("delivered", 0),
                "in_org_qty": c.get("allocated", 0),
                "actions": list(_ALLOWED.get(r["alert_type"], ())) if r["status"] == "OPEN" else [],
            }
        )
    return util.page_result(items, total, page)


def _bill_counts(bills: list[str]) -> dict[str, dict]:
    """涉及枚数：暂停、已交付（已推送、已领取）、已分配仍在原生产组织（按号当前组织）。"""
    if not bills:
        return {}
    ub = sorted(set(bills))
    out: dict[str, dict] = {}
    for r in db.all(
        "SELECT bill_no, SUM(suspend IS NOT NULL) s, SUM(status IN ('PUSHED','ACQUIRED')) d, "
        f"SUM(factory_code IS NOT NULL) a FROM sn_item WHERE bill_no IN ({marks(len(ub))}) GROUP BY bill_no",
        *ub,
    ):
        out[r["bill_no"]] = {
            "suspended": int(r["s"] or 0),
            "delivered": int(r["d"] or 0),
            "allocated": int(r["a"] or 0),
        }
    return out


def handle(cu: CurrentUser, alert_id: int, action: str | None, note: str | None, target_seq: int | None) -> dict:
    """总部处理提醒：确认变更 / 确认对应 / 确认无关 / 确认已作废（须填原因）/ 知道了。"""
    act = util.trim(action)
    a = db.one("SELECT * FROM sn_order_alert WHERE id = %s", alert_id)
    if a is None or a["status"] != "OPEN":
        raise biz(ErrorCode.ALERT_NOT_FOUND)
    if act not in _ALLOWED.get(a["alert_type"], ()):
        raise biz(ErrorCode.ALERT_ACTION_INVALID)
    why = util.trim(note)
    if act == VOID and (not why or len(why) > 500):
        raise biz(ErrorCode.REASON_REQUIRED)
    if act == MAP:
        from app.services import transfers

        transfers.map_material(cu, a, target_seq, why)
    else:
        with db.tx():
            a = db.one("SELECT * FROM sn_order_alert WHERE id = %s FOR UPDATE", alert_id)
            if a is None or a["status"] != "OPEN":
                raise biz(ErrorCode.ALERT_NOT_FOUND)
            no = a["bill_no"]
            if act == CONFIRM:
                col = {PI: "pi", CUSTOMER: "customer_code"}.get(a["alert_type"])
                snap = db.one("SELECT * FROM sn_prd_mo WHERE bill_no = %s LIMIT 1", no)
                if col is not None:
                    db.exec(
                        f"UPDATE sn_bill_ledger SET {col} = %s, updated_at = %s WHERE bill_no = %s",
                        snap[col] if snap else a["after_value"],
                        util.now(),
                        no,
                    )
                elif snap is not None:
                    db.exec(
                        "UPDATE sn_bill_ledger SET org_code = %s, org_name = %s, updated_at = %s WHERE bill_no = %s",
                        snap["org_code"],
                        snap["org_name"],
                        util.now(),
                        no,
                    )
            elif act == VOID:
                db.exec(
                    "UPDATE sn_bill_ledger SET leave_state = 'VOID_CONFIRMED', updated_at = %s WHERE bill_no = %s",
                    util.now(),
                    no,
                )
                _close_auto(cu, no, BLOCKING, "单据已确认作废")
            _close(cu, a["id"], act, why or None)
            L = ledger(no)
            audit.record(
                cu,
                audit.entry(audit.ORDER_ALERT_HANDLE)
                .bill(no)
                .pi(None if L is None else L["pi"])
                .factory(None if L is None else L["org_code"])
                .why(why or None)
                .info(f"alert={a['id']} type={a['alert_type']} action={act}"),
            )
            _reevaluate(cu, no)
        kick_resumed()
    return {"id": alert_id, "action": act}


def close_material_alert(cu: CurrentUser, alert_id: int, note: str | None) -> None:
    """确认对应完成后（在 transfers.map_material 的事务内）关闭提醒并重算。"""
    a = db.one("SELECT bill_no FROM sn_order_alert WHERE id = %s", alert_id)
    _close(cu, alert_id, MAP, note)
    _reevaluate(cu, a["bill_no"])
