"""MES 拉取（F-12）与只读核对（F-19）：MES 接口 ② 取批、③ 回执、④ 已交付查询、⑤ 批次明细。

③ 回执对推送（① 应答「已收到」后）与拉取（② 取走后）的批次都适用，是批次确认的唯一途径（人工确认除外）。"""

from __future__ import annotations

from app.core import utils as util
from app.core.config import settings
from app.core.exceptions import MesError, mes_body
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit, changes, factories, locks, rules
from app.services.delivery.batches import (
    FAILED,
    RECEIPT_NG,
    RECEIPT_OK,
    SRC_PULL,
    SUCCESS,
    VOIDED,
    body_obj,
    build_delivery,
    find_batch,
    must_batch,
    str_or_none,
)
from app.services.delivery.receipts import apply_receipt


def _rate_limit(org: str) -> None:
    now = util.now()
    win = now.replace(second=0)
    key = f"mes:{org}"
    with db.new_tx():
        db.exec(
            "INSERT INTO sn_rate_counter(rkey, win, n) VALUES (%s,%s,1) ON DUPLICATE KEY UPDATE n = n + 1", key, win
        )
        n = int(db.scalar("SELECT n FROM sn_rate_counter WHERE rkey = %s AND win = %s", key, win))
    if n > settings.mes_pull_rate_per_minute:
        raise MesError(429, "RATE_LIMITED", "请求过于频繁", retry_after=max(1, 60 - now.second))


def _scope_conds(pi: str | None, bill: str | None) -> tuple[str, list, str, list]:
    """（交付批次条件，参数，转出批次条件，参数）；转出批次按每枚的转出前 PI、单据判断。"""
    dc, da, xc, xa = "", [], "", []
    if pi:
        dc += " AND b.pi_no = %s"
        da.append(pi)
    if bill:
        dc += " AND EXISTS(SELECT 1 FROM sn_delivery_item d WHERE d.batch_id = b.id AND d.bill_no = %s)"
        da.append(bill)
    if pi and bill:
        xc = " AND EXISTS(SELECT 1 FROM sn_delivery_item d WHERE d.batch_id = b.id AND d.pi_no = %s AND d.bill_no = %s)"
        xa = [pi, bill]
    elif pi:
        xc = " AND EXISTS(SELECT 1 FROM sn_delivery_item d WHERE d.batch_id = b.id AND d.pi_no = %s)"
        xa = [pi]
    elif bill:
        xc = " AND EXISTS(SELECT 1 FROM sn_delivery_item d WHERE d.batch_id = b.id AND d.bill_no = %s)"
        xa = [bill]
    return dc, da, xc, xa


def _earliest_unconfirmed(
    org: str, mes: bool, pi: str | None, bill: str | None, exclude: int | None = None, result: str = "UNCONFIRMED"
):
    """范围内结果为 result（默认未确认）的交付批次与转出批次（UNION ALL 的各段 SQL 与参数）。"""
    dc, da, xc, xa = _scope_conds(pi, bill)
    ex = "" if exclude is None else " AND b.id <> %s"
    exa = [] if exclude is None else [exclude]
    parts, args = [], []
    if mes:
        parts.append(
            "SELECT b.* FROM sn_delivery_batch b WHERE b.factory_code = %s AND b.batch_type <> 'TRANSFER_OUT' "
            "AND b.result = %s" + dc + ex
        )
        args += [org, result, *da, *exa]
    parts.append(
        "SELECT b.* FROM sn_delivery_batch b WHERE b.factory_code = %s AND b.batch_type = 'TRANSFER_OUT' "
        "AND b.result = %s" + xc + ex
    )
    args += [org, result, *xa, *exa]
    return parts, args


def _remaining(org: str, mes: bool, pi: str | None, bill: str | None, exclude: int | None) -> tuple[int, int]:
    """（范围内剩余可拉枚数，暂停枚数）：未入批且未暂停的号 + 其余未确认批次（含转出批次）的枚数。"""
    parts, args = _earliest_unconfirmed(org, mes, pi, bill, exclude)
    batched = int(db.scalar(f"SELECT COALESCE(SUM(qty), 0) FROM ({' UNION ALL '.join(parts)}) t", *args) or 0)
    cond, cargs = "", []
    if pi:
        cond += " AND pi_no = %s"
        cargs.append(pi)
    if bill:
        cond += " AND bill_no = %s"
        cargs.append(bill)
    unb = 0
    if mes:
        unb = db.count(
            "SELECT COUNT(*) FROM sn_item WHERE factory_code = %s AND status = 'TO_PUSH' AND suspend IS NULL "
            "AND delivery_batch_no IS NULL" + cond,
            org,
            *cargs,
        )
    paused = db.count(
        "SELECT COUNT(*) FROM sn_item WHERE factory_code = %s AND status = 'TO_PUSH' AND suspend IS NOT NULL "
        "AND delivery_batch_no IS NULL" + cond,
        org,
        *cargs,
    )
    return unb + batched, paused


def next_batch(cu: CurrentUser, pi_in: str | None, bill_in: str | None, batch_no_in: str | None) -> dict:
    """接口 ② 取下一批。带 batchNo 时只读取回本组织该未确认或失败批次；否则先给范围内最早的未确认批次，没有才建批。
    按 PI、单据限定范围时，范围内既没有未确认批次也建不出新批，再给范围内最早的失败批次：首次推送就失败（如跨 PI
    转移后 MES 还没有目标 PI 而整批退回）的批次 MES 没有批次号，按订单条件也要能取到；放在最后，不挡住后续的号。"""
    org = cu.factory_code
    _rate_limit(org)
    f = factories.must_get(org)
    mes = bool(f["mes_enabled"])
    pi, bill = util.trim(pi_in) or None, util.trim(bill_in) or None
    batch_no = util.trim(batch_no_in) or None
    if batch_no:
        b = find_batch(batch_no)
        if b is None or b["factory_code"] != org:
            raise MesError(404, "BATCH_NOT_FOUND", "批次号不存在，或不属于本组织", batch_no)
        if b["result"] == VOIDED:
            raise MesError(409, "BATCH_VOIDED", "该批已作废，丢弃这批内容", batch_no)
        if b["result"] == SUCCESS:
            raise MesError(409, "BATCH_CONFIRMED", "该批已确认，不用再处理", batch_no)
        return _next_out(b, *_remaining(org, mes, None, None, b["id"]))
    if pi and not (
        db.exists("SELECT EXISTS(SELECT 1 FROM sn_item WHERE pi_no = %s AND factory_code = %s)", pi, org)
        or db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_delivery_batch b JOIN sn_delivery_item d ON d.batch_id = b.id "
            "WHERE b.factory_code = %s AND b.batch_type = 'TRANSFER_OUT' AND d.pi_no = %s)",
            org,
            pi,
        )
    ):
        raise MesError(403, "ORG_NOT_ALLOWED", f"PI {pi} 下没有号分到本组织，也没有本组织的转出批次")
    created = False
    with locks.with_org_lock(org):
        parts, args = _earliest_unconfirmed(org, mes, pi, bill)
        b = db.one(f"SELECT * FROM ({' UNION ALL '.join(parts)}) t ORDER BY created_at, id LIMIT 1", *args)
        if b is None and mes:
            target_pi = pi
            if target_pi is None:
                cond, cargs = ("", []) if not bill else (" AND bill_no = %s", [bill])
                target_pi = db.scalar(
                    "SELECT pi_no FROM sn_item WHERE factory_code = %s AND status = 'TO_PUSH' AND suspend IS NULL "
                    "AND delivery_batch_no IS NULL" + cond + " ORDER BY seq_pi_no, seq_dec LIMIT 1",
                    org,
                    *cargs,
                )
            if target_pi is not None:
                b = build_delivery(cu, org, target_pi, SRC_PULL, bill)
                created = b is not None
        if b is None and (pi or bill):
            parts, args = _earliest_unconfirmed(org, mes, pi, bill, result=FAILED)
            b = db.one(f"SELECT * FROM ({' UNION ALL '.join(parts)}) t ORDER BY created_at, id LIMIT 1", *args)
        if b is not None:
            db.exec("UPDATE sn_delivery_batch SET pulled_at = %s WHERE id = %s", util.now(), b["id"])
    remaining, paused = _remaining(org, mes, pi, bill, None if b is None else b["id"])
    if b is None:
        changes.count_daily("MES_PULL_EMPTY", org)
        return _next_out(None, remaining, paused)
    audit.record_isolated(
        cu,
        audit.entry(audit.SN_PULL)
        .factory(org)
        .pi(pi or b["pi_no"])
        .bill(bill)
        .count(b["qty"])
        .batch(b["batch_no"])
        .info(f"scope=pi:{pi or '-'} bill:{bill or '-'} new={int(created)} type={b['batch_type']}"),
    )
    return _next_out(must_batch(b["batch_no"]), remaining, paused)


def _next_out(b: dict | None, remaining: int, paused: int) -> dict:
    """② 应答：批次（推送、拉取建立的结构相同，处理相同）、剩余枚数、暂停枚数。"""
    if b is None:
        msg = f"暂无可拉取批次，{paused} 枚暂停下发" if paused else "暂无可拉取批次"
        return mes_body("OK", msg, batch=None, remaining=remaining, paused=paused)
    return mes_body("OK", "成功", b["batch_no"], batch=body_obj(b), remaining=remaining, paused=paused)


#: ③ 受理结果的说明
_RECEIPT_MSG = {
    "CONFIRMED": "回执已确认，批次成功",
    "REJECTED": "整批退回已登记，批次失败",
    "ARCHIVED": "批次此前已确认，本次回执只存档",
}


def submit_receipt(cu: CurrentUser, batch_no: str, body: dict) -> dict:
    """接口 ③ 提交回执（或整批拒收）。批次号取自请求体，按批次号幂等：已确认的只存档；已作废的拒绝。
    code 不是 OK / NG 时 400 拒收、批次不变，MES 改正后重交。应答回传本系统的处理结果：批次结果、本次回执是否生效
    （首次确认或整批退回已登记为 true，此前已确认、只存档为 false）。"""
    org = cu.factory_code
    _rate_limit(org)
    if body.get("code") not in (RECEIPT_OK, RECEIPT_NG):
        raise MesError(
            400, "INVALID_REQUEST", "参数不合法：code", batch_no, [{"field": "code", "reason": "只能是 OK / NG"}]
        )
    b = find_batch(batch_no)
    if b is None or b["batch_no"] != batch_no or b["factory_code"] != org:
        raise MesError(404, "BATCH_NOT_FOUND", "批次号不存在，或不属于本组织", batch_no)
    if b["result"] == VOIDED:
        raise MesError(409, "BATCH_VOIDED", "批次已作废，不得再处理这批内容", batch_no)
    applied = apply_receipt(cu, b["batch_no"], body)
    if applied.verdict == "VOIDED":
        raise MesError(409, "BATCH_VOIDED", "批次已作废，不得再处理这批内容", batch_no)
    return mes_body(
        "OK",
        _RECEIPT_MSG[applied.verdict],
        b["batch_no"],
        batchResult=applied.batch["result"],
        firstConfirmation=applied.verdict != "ARCHIVED",
    )


# ================================================================== 只读接口（F-19）


def _cursor(v: str | None) -> int:
    """分页游标：不填为首页，否则须为上一页返回的 nextCursor；不合法的 400，不当首页（避免从头重查）。"""
    t = util.trim(v)
    if not t:
        return 0
    if not t.isascii() or not t.isdigit():
        raise MesError(
            400,
            "INVALID_REQUEST",
            "参数不合法：cursor",
            details=[{"field": "cursor", "reason": "须为上一页返回的 nextCursor"}],
        )
    return int(t)


def _page_size(v: int | None) -> int:
    return 1000 if v is None or v < 1 else min(v, 5000)


_READ_COLS = (
    "id, sn, rev, status, factory_code, pi_no, bill_no, bill_seq, material_code, customer_code, seq_pi_no, seq_dec, "
    "length_text, attach_no, rule_version_id, delivery_batch_no, delivered_at"
)


def _read_item(r: dict) -> dict:
    return {
        "sn": r["sn"],
        "rev": int(r["rev"]),
        "serial": rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        "serialDec": str(r["seq_dec"]),
        "serialPi": r["seq_pi_no"],
        "pi": r["pi_no"],
        "billNo": r["bill_no"],
        "billSeq": int(r["bill_seq"]),
        "attachNo": str_or_none(r["attach_no"]),
        "materialCode": str_or_none(r["material_code"]),
        "length": r["length_text"],
        "customerCode": r["customer_code"],
        "status": r["status"],
        "batchNo": r["delivery_batch_no"],
        "deliveredAt": util.mes_fmt(r["delivered_at"]),
    }


def read_delivered(
    cu: CurrentUser, org_in: str | None, pi_in: str | None, cursor: str | None, size: int | None
) -> dict:
    """接口 ④：MES 接口账户只得到已交付给本组织 MES 的号（已推送，含拉取）；其他账户按可看范围、按生产组织和 PI。
    已转到别的组织的号不再返回。只读，不得据此入库、打标或删除。"""
    if cu.is_mes:
        _rate_limit(cu.factory_code)
        org = cu.factory_code
        cond, args = " AND mes_holder = %s", [org]
    else:
        org = util.trim(org_in)
        if not org:
            raise MesError(400, "INVALID_REQUEST", "orgCode 必填")
        if not cu.can_read(org):
            raise MesError(403, "ORG_NOT_ALLOWED", "无权查看该生产组织")
        cond, args = " AND status <> 'PENDING_ALLOC'", []
    pi = util.trim(pi_in)
    if pi:
        cond += " AND pi_no = %s"
        args.append(pi)
    n = _page_size(size)
    rows = db.all(
        f"SELECT {_READ_COLS} FROM sn_item WHERE factory_code = %s{cond} AND id > %s ORDER BY id LIMIT %s",
        org,
        *args,
        _cursor(cursor),
        n + 1,
    )
    more = len(rows) > n
    rows = rows[:n]
    return mes_body("OK", "成功", items=[_read_item(r) for r in rows], nextCursor=str(rows[-1]["id"]) if more else None)


def read_batch_items(cu: CurrentUser, batch_no: str, cursor: str | None, size: int | None) -> dict:
    """接口 ⑤：按批次号查询仍在该批次中的号（已转到别的组织的不返回，整批移出时为 0 枚）。只读。"""
    if cu.is_mes:
        _rate_limit(cu.factory_code)
    b = find_batch(batch_no)
    if b is None or not (cu.factory_code == b["factory_code"] if cu.is_mes else cu.can_read(b["factory_code"])):
        raise MesError(404, "BATCH_NOT_FOUND", "批次号不存在，或不属于本组织", batch_no)
    n = _page_size(size)
    rows = db.all(
        f"SELECT {_READ_COLS} FROM sn_item WHERE delivery_batch_no = %s AND factory_code = %s AND id > %s "
        "ORDER BY id LIMIT %s",
        b["batch_no"],
        b["factory_code"],
        _cursor(cursor),
        n + 1,
    )
    more = len(rows) > n
    rows = rows[:n]
    return mes_body(
        "OK",
        "成功",
        b["batch_no"],
        items=[_read_item(r) for r in rows],
        nextCursor=str(rows[-1]["id"]) if more else None,
    )
