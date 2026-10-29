"""回执：核对、存档、确认或记失败。MES 回执（接口 ③）、人工确认都在这里落到批次和号上；先到的回执生效，后到的只存档。

推送与拉取的批次都由 MES 落库后调 ③ 回执确认（推送应答只表示已收到）。回执只有整批结果：code = OK 整批已处理
（逐枚按 rev 新增或覆盖），NG 整批退回（message 为原因）。回执生效后唤醒正在等这一批的推送任务：OK 接着推下一批，
NG 停止并提示。"""

from __future__ import annotations

from app.core import utils as util
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit, mes_client
from app.services.delivery import push
from app.services.delivery.batches import (
    FAILED,
    M_MANUAL,
    M_RECEIPT,
    RECEIPT_NG,
    RECEIPT_OK,
    SRC_PULL,
    SUCCESS,
    TRANSFER_OUT,
    VOIDED,
    XO_KEY,
    must_batch,
)


class Applied:
    """回执处理结果。verdict：CONFIRMED / REJECTED / MISMATCH / ARCHIVED / VOIDED。"""

    def __init__(self, verdict: str, batch: dict, details: list[dict] | None = None) -> None:
        self.verdict = verdict
        self.batch = batch
        self.details = details or []


def _verify(b: dict, r: dict) -> list[dict]:
    """核对：批次号一致、code 为 OK / NG。"""
    diffs: list[dict] = []
    if r.get("batchNo") != b["batch_no"]:
        diffs.append({"field": "batchNo", "reason": f"期望 {b['batch_no']}，收到 {r.get('batchNo')}"})
    code = r.get("code")
    if code not in (RECEIPT_OK, RECEIPT_NG):
        diffs.append({"field": "code", "reason": f"只能是 OK / NG，收到 {code if isinstance(code, str) else '(空)'}"})
    return diffs


def _archive(b: dict, method: str, outcome: str, verdict: str, detail: str | None, r: dict, by: str) -> None:
    db.exec(
        "INSERT INTO sn_delivery_receipt(batch_id, method, outcome, verdict, detail, body_gz, created_by, created_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
        b["id"],
        method,
        util.cut(outcome, 16) or "",
        verdict,
        util.cut(detail, 1000),
        mes_client.gzip_bytes(mes_client.canonical(r)),
        by,
        util.now(),
    )


def apply_receipt(cu: CurrentUser, batch_no: str, r: dict) -> Applied:
    """处理一份 MES 回执（接口 ③）。在独立事务里提交，返回结果；调用方据此应答。
    回执生效（确认或整批退回）后唤醒正在等这一批的推送任务。"""
    method = M_RECEIPT
    with db.tx():
        b = must_batch(batch_no, lock=True)
        code = r.get("code") if isinstance(r.get("code"), str) else ""
        if b["result"] == VOIDED:
            _archive(b, method, code, "VOIDED", "批次已作废", r, cu.emp_no)
            return Applied("VOIDED", b)
        if b["result"] == SUCCESS:
            _archive(b, method, code, "ARCHIVED", "批次早已确认，只存档", r, cu.emp_no)
            return Applied("ARCHIVED", b)
        now = util.now()
        diffs = _verify(b, r)
        if diffs:
            text = "；".join(f"{d['field']}: {d['reason']}" for d in diffs)
            mark_failed(b, "RECEIPT_MISMATCH", "回执与批次不符：" + text, False, now)
            _archive(b, method, code, "MISMATCH", text, r, cu.emp_no)
            audit.record(
                cu,
                audit.entry(audit.SN_RECEIPT)
                .fail("回执与批次不符：" + text)
                .factory(b["factory_code"])
                .pi(b["pi_no"])
                .count(b["qty"])
                .batch(b["batch_no"])
                .info(f"method={method}"),
            )
            return Applied("MISMATCH", must_batch(batch_no), diffs)
        if code == RECEIPT_NG:
            msg = r.get("message")
            reason = msg.strip() if isinstance(msg, str) and msg.strip() else "MES 整批退回"
            mark_failed(b, "REJECTED", reason, True, now)
            _archive(b, method, code, "REJECTED", reason, r, cu.emp_no)
            audit.record(
                cu,
                audit.entry(audit.SN_RECEIPT)
                .factory(b["factory_code"])
                .pi(b["pi_no"])
                .count(b["qty"])
                .batch(b["batch_no"])
                .why(reason)
                .info(f"method={method} code=NG"),
            )
            applied = Applied("REJECTED", must_batch(batch_no))
        else:
            confirm(cu, b, method, True, now)
            _archive(b, method, code, "CONFIRMED", None, r, cu.emp_no)
            b2 = must_batch(batch_no)
            audit.record(
                cu,
                audit.entry(audit.SN_RECEIPT)
                .factory(b["factory_code"])
                .pi(b["pi_no"])
                .count(b["qty"])
                .batch(b["batch_no"])
                .status(util.TO_PUSH, util.PUSHED if b["batch_type"] != TRANSFER_OUT else None)
                .info(f"method={method} result={b2['result']} accepted={b2['accepted_qty']}"),
            )
            applied = Applied("CONFIRMED", b2)
    wake_task(b, resume=applied.verdict == "CONFIRMED")
    return applied


def mark_failed(b: dict, code: str, reason: str, voidable: bool, now) -> None:
    db.exec(
        "UPDATE sn_delivery_batch SET result = %s, fail_code = %s, fail_reason = %s, voidable = %s, push_state = "
        "'IDLE', "
        "next_retry_at = NULL, updated_at = %s WHERE id = %s",
        FAILED,
        code,
        util.cut(reason, 1000),
        1 if voidable else 0,
        now,
        b["id"],
    )


def confirm(cu: CurrentUser, b: dict, method: str, by_mes: bool, now) -> None:
    """批次确认：交付批次的号改为已推送并记 MES 持有组织；转出批次只在持有组织仍是来源组织时清除。
    by_mes：MES 回执 OK（整批已处理），逐枚记为接收；人工确认没有逐枚结果。"""
    accepted = 0
    if by_mes:
        db.exec("UPDATE sn_delivery_item SET receipt_result = 'ACCEPTED' WHERE batch_id = %s", b["id"])
        accepted = b["qty"]
    db.exec(
        "UPDATE sn_delivery_batch SET result = %s, confirm_method = %s, confirmed_at = %s, accepted_qty = %s, "
        "push_state = 'IDLE', next_retry_at = NULL, updated_at = %s WHERE id = %s",
        SUCCESS,
        method,
        now,
        accepted,
        now,
        b["id"],
    )
    if b["batch_type"] != TRANSFER_OUT:
        # 交付方式按 MES 怎么拿到这一批（回执都经 ③）：推送应答过已收到的为推送；没有、且是 MES 取走的为拉取；
        # 人工确认按批次怎么建立的
        if method == M_MANUAL:
            pulled = b["source"] == SRC_PULL
        else:
            pulled = b["received_at"] is None and (b["source"] == SRC_PULL or b["pulled_at"] is not None)
        mode = util.MODE_PULL if pulled else util.MODE_PUSH
        db.exec(
            "UPDATE sn_item SET status = 'PUSHED', mes_holder = %s, delivery_mode = %s, push_type = 'NEW', "
            "delivered_at = %s, updated_at = %s WHERE delivery_batch_no = %s AND status = 'TO_PUSH'",
            b["factory_code"],
            mode,
            now,
            now,
            b["batch_no"],
        )
    else:
        # 只在持有组织仍是来源组织时清除（转入方的交付批次先确认的不受影响）
        db.exec(
            "UPDATE sn_item i JOIN sn_delivery_item d ON d.item_id = i.id SET i.mes_holder = NULL, i.updated_at = %s "
            "WHERE d.batch_id = %s AND i.mes_holder = %s",
            now,
            b["id"],
            b["factory_code"],
        )


def wake_task(b: dict, resume: bool = False) -> None:
    """批次确认、整批退回或作废后：正在等这一批（等回执、等重试）的推送任务马上再跑一次——确认的接着推后续批次，
    退回的停止并提示原因。resume：批次已确认（MES 回执 OK 或人工确认），已停止的推送任务也从这里接着推
    （推送从失败处开始：失败或重试用尽的批次经拉取回执、人工确认成功后不用再点「继续推送」；仍有失败批次的会再停下）。"""
    key = XO_KEY if b["batch_type"] == TRANSFER_OUT else b["pi_no"]
    cond = "state = 'WAITING' AND current_batch = %s"
    if resume:
        cond = f"({cond} OR state = 'STOPPED')"
    db.exec(
        "UPDATE sn_push_task SET state = 'QUEUED', run_after = NULL, updated_at = %s WHERE factory_code = %s "
        f"AND task_key = %s AND {cond}",
        util.now(),
        b["factory_code"],
        key,
        b["batch_no"],
    )
    push.poke()
