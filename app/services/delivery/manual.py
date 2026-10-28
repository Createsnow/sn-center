"""人工处理（5.2.5 第 7 条）：批次人工确认、作废；交付告警处理（5.2.5 第 8 条）。"""

from __future__ import annotations

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, changes, factories
from app.services.delivery.batches import (
    FAILED,
    M_MANUAL,
    NEW,
    TRANSFER_OUT,
    UNCONFIRMED,
    VOIDED,
    XO_KEY,
    batch_json,
    build_delivery_now,
    build_transfer_out,
    must_batch,
)
from app.services.delivery.push import kick_bill, start_task
from app.services.delivery.receipts import confirm, wake_task


def _check_manual(reason: str | None, basis: str | None) -> tuple[str, str]:
    r, b = util.trim(reason), util.trim(basis)
    if not r or len(r) > 500:
        raise biz(ErrorCode.REASON_REQUIRED)
    if not b or len(b) > 500:
        raise biz(ErrorCode.BATCH_BASIS_REQUIRED)
    return r, b


def manual_confirm(cu: CurrentUser, batch_no: str | None, reason: str | None, basis: str | None) -> dict:
    """人工确认：MES 方确认已全部入库但回执交不上来。批次成功（确认方式「人工」），推送不再重试这一批。"""
    r, bs = _check_manual(reason, basis)
    with db.tx():
        b = must_batch(batch_no, lock=True)
        cu.check_write(b["factory_code"])
        if b["result"] not in (UNCONFIRMED, FAILED):
            raise biz(ErrorCode.BATCH_NOT_PENDING, batch_no=b["batch_no"], result=b["result"])
        now = util.now()
        confirm(cu, b, M_MANUAL, False, now)
        db.exec(
            "UPDATE sn_delivery_batch SET manual_reason = %s, manual_basis = %s, manual_by = %s, manual_at = %s "
            "WHERE id = %s",
            r,
            bs,
            cu.emp_no,
            now,
            b["id"],
        )
        audit.record(
            cu,
            audit.entry(audit.SN_BATCH_MANUAL)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .why(r)
            .info(f"basis={bs} was={b['result']}"),
        )
    wake_task(b)
    return batch_json(must_batch(b["batch_no"]))


def void(cu: CurrentUser, batch_no: str | None, reason: str | None, basis: str | None) -> dict:
    """作废：MES 没有处理的批次（整批拒绝、报文过大、认证或地址错误，或书面确认未处理的未确认批次）。
    交付类批次的号清除批次号、rev 不变：首次下发（NEW）的回到待推送（未入批）；其余类型（转移、改挂）按原号当场重建
    同类型的新批次（暂停交付的号留在号池）；转出批次按原明细重建新的转出批次。重建的都自动推送。"""
    r, bs = _check_manual(reason, basis)
    rebuilt: list[dict] = []
    with db.tx():
        b = must_batch(batch_no, lock=True)
        cu.check_write(b["factory_code"])
        if b["result"] not in (UNCONFIRMED, FAILED):
            raise biz(ErrorCode.BATCH_NOT_PENDING, batch_no=b["batch_no"], result=b["result"])
        if b["result"] == FAILED and not b["voidable"]:
            raise biz(ErrorCode.BATCH_NOT_VOIDABLE, batch_no=b["batch_no"])
        now = util.now()
        db.exec(
            "UPDATE sn_delivery_batch SET result = %s, push_state = 'IDLE', next_retry_at = NULL, manual_reason = %s, "
            "manual_basis = %s, manual_by = %s, manual_at = %s, updated_at = %s WHERE id = %s",
            VOIDED,
            r,
            bs,
            cu.emp_no,
            now,
            now,
            b["id"],
        )
        bills: set[str] = set()
        if b["batch_type"] != TRANSFER_OUT:
            bills = set(db.column("SELECT DISTINCT bill_no FROM sn_item WHERE delivery_batch_no = %s", b["batch_no"]))
            ids = db.column(
                "SELECT id FROM sn_item WHERE delivery_batch_no = %s AND status = 'TO_PUSH' FOR UPDATE", b["batch_no"]
            )
            db.exec(
                "UPDATE sn_item SET delivery_batch_no = NULL, updated_at = %s WHERE delivery_batch_no = %s "
                "AND status = 'TO_PUSH'",
                now,
                b["batch_no"],
            )
            # 先重算暂停标记（入批期间不暂停），暂停交付的号不进重建的批次
            changes.refresh_items(bills)
            if b["batch_type"] != NEW and ids and factories.is_mes(b["factory_code"]):
                rebuilt = build_delivery_now(
                    cu,
                    b["factory_code"],
                    b["pi_no"],
                    ids,
                    (b["transfer_kind"], b["transfer_doc_no"]),
                    rebuilt_from=b["batch_no"],
                )
                if rebuilt:
                    db.exec(
                        "UPDATE sn_delivery_batch SET rebuilt_to = %s WHERE id = %s",
                        ",".join(x["batch_no"] for x in rebuilt)[:40],
                        b["id"],
                    )
        else:
            entries = [
                {**e, "transfer_kind": b["transfer_kind"], "transfer_doc_no": b["transfer_doc_no"]}
                for e in db.all(
                    "SELECT d.item_id, d.sn, d.rev, d.pi_no, d.bill_no, d.bill_seq, d.material_code, d.customer_code, "
                    "d.seq_pi_no, d.seq_dec, d.to_factory, i.length_text, i.attach_no, i.rule_version_id "
                    "FROM sn_delivery_item d JOIN sn_item i ON i.id = d.item_id "
                    "WHERE d.batch_id = %s ORDER BY d.line_no",
                    b["id"],
                )
            ]
            rebuilt = build_transfer_out(cu, b["factory_code"], entries, rebuilt_from=b["batch_no"])
            db.exec(
                "UPDATE sn_delivery_batch SET rebuilt_to = %s WHERE id = %s",
                ",".join(x["batch_no"] for x in rebuilt)[:40],
                b["id"],
            )
            db.exec(
                "UPDATE sn_transfer_item SET out_batch_no = %s WHERE out_batch_no = %s",
                rebuilt[0]["batch_no"] if rebuilt else None,
                b["batch_no"],
            )
        audit.record(
            cu,
            audit.entry(audit.SN_BATCH_VOID)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .why(r)
            .info(
                f"basis={bs} was={b['result']} type={b['batch_type']}"
                + (f" rebuilt={','.join(x['batch_no'] for x in rebuilt)}" if rebuilt else "")
            ),
        )
    # 推送任务正在等这一批（等回执、等重试）的，不用等到截止时间，马上接着推
    wake_task(b)
    if b["batch_type"] != TRANSFER_OUT:
        for no in bills:
            kick_bill(no)
        if b["pi_no"] and factories.is_mes(b["factory_code"]):
            start_task(cu, b["factory_code"], b["pi_no"])
    else:
        start_task(cu, b["factory_code"], XO_KEY)
    out = batch_json(must_batch(b["batch_no"]))
    out["rebuilt"] = [x["batch_no"] for x in rebuilt]
    return out


# ================================================================== 交付告警处理（5.2.5 第 8 条）


def handle_alert(cu: CurrentUser, alert_id: int, note: str | None) -> dict:
    """处理交付告警：填写核查结论后关闭。"""
    why = util.trim(note)
    if not why or len(why) > 500:
        raise biz(ErrorCode.REASON_REQUIRED)
    with db.tx():
        a = db.one("SELECT * FROM sn_delivery_alert WHERE id = %s FOR UPDATE", alert_id)
        if a is None or a["status"] != "OPEN":
            raise biz(ErrorCode.DALERT_NOT_FOUND)
        # 不属于任何生产组织的告警（factory_code 为空）只有系统管理员能处理
        cu.check_write(a["factory_code"] or "-")
        db.exec(
            "UPDATE sn_delivery_alert SET status = 'CLOSED', closed_by = %s, closed_at = %s, note = %s WHERE id = %s",
            cu.emp_no,
            util.now(),
            why,
            alert_id,
        )
        audit.record(
            cu,
            audit.entry(audit.SN_ALERT_HANDLE)
            .factory(a["factory_code"])
            .batch(a["batch_no"])
            .range(a["sn"], a["sn"])
            .why(why)
            .info(f"alert={alert_id} type={a['alert_type']}"),
        )
    return {"id": alert_id}


def frozen_by_transfer_out(item_ids: list[int]) -> set[int]:
    """号所在转出批次未确认或失败（5.2.8 第 7 条）。"""
    if not item_ids:
        return set()
    out: set[int] = set()
    for i in range(0, len(item_ids), 1000):
        part = item_ids[i : i + 1000]
        out |= set(
            db.column(
                f"SELECT d.item_id FROM sn_delivery_item d JOIN sn_delivery_batch b ON b.id = d.batch_id "
                f"WHERE d.item_id IN ({marks(len(part))}) AND b.batch_type = 'TRANSFER_OUT' "
                "AND b.result IN ('UNCONFIRMED','FAILED')",
                *part,
            )
        )
    return out


def list_alerts(cu: CurrentUser, status: str | None, page: util.Page) -> dict:
    where = " WHERE 1=1"
    args: list = []
    st = util.trim(status) or "OPEN"
    if st != "ALL":
        where += " AND status = %s"
        args.append(st)
    fsql, fargs = factories.scope_sql(cu.read_factories(None))
    where += fsql
    args += fargs
    total = db.count("SELECT COUNT(*) FROM sn_delivery_alert" + where, *args)
    rows = db.all(
        f"SELECT * FROM sn_delivery_alert{where} ORDER BY id DESC LIMIT %s, %s", *args, page.offset, page.size
    )
    return util.page_result([{k: util.jv(v) for k, v in r.items()} for r in rows], total, page)
