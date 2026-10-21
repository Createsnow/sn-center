"""回执：核对（与批次逐枚对应）、存档、确认或记失败。推送应答、拉取回执、人工确认都在这里落到批次和号上；
先到的回执生效，后到的只存档。"""

from __future__ import annotations

from app.core import utils as util
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, mes_client
from app.services.delivery import push
from app.services.delivery.batches import (
    CONFIRMED,
    DELIVERY,
    FAILED,
    M_PULL,
    M_PUSH,
    RESULTS,
    SRC_PULL,
    SUCCESS,
    SUCCESS_CONFLICT,
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
    """核对：批次号一致、结果条数等于 count、每枚 SN 恰好一次且都属于本批、每枚 rev 与批次一致、summary 与明细一致。"""
    diffs: list[dict] = []
    if r.get("batchNo") != b["batch_no"]:
        diffs.append({"field": "batchNo", "reason": f"期望 {b['batch_no']}，收到 {r.get('batchNo')}"})
    results = r.get("results")
    if not isinstance(results, list):
        return diffs + [{"field": "results", "reason": "缺少逐枚结果"}]
    if len(results) != b["qty"]:
        diffs.append({"field": "results", "reason": f"期望 {b['qty']} 枚，收到 {len(results)} 枚"})
    expected = {x["sn"]: x["rev"] for x in db.all("SELECT sn, rev FROM sn_delivery_item WHERE batch_id = %s", b["id"])}
    seen: set[str] = set()
    counts = dict.fromkeys(RESULTS, 0)
    bad_sn = bad_rev = dup = bad_res = 0
    for x in results:
        sn = x.get("sn") if isinstance(x, dict) else None
        if sn in seen:
            dup += 1
            continue
        seen.add(sn)
        if sn not in expected:
            bad_sn += 1
            continue
        if x.get("rev") != expected[sn]:
            bad_rev += 1
        res = x.get("result")
        if res not in RESULTS:
            bad_res += 1
        else:
            counts[res] += 1
            if res in ("CONFLICT", "STALE") and (x.get("existingRev") is None or not x.get("existingBatchNo")):
                bad_res += 1
    missing = len(set(expected) - seen)
    if dup:
        diffs.append({"field": "results", "reason": f"{dup} 枚 SN 重复出现"})
    if bad_sn:
        diffs.append({"field": "results", "reason": f"{bad_sn} 枚 SN 不属于本批"})
    if missing:
        diffs.append({"field": "results", "reason": f"缺少 {missing} 枚 SN"})
    if bad_rev:
        diffs.append({"field": "results.rev", "reason": f"{bad_rev} 枚 rev 与批次不一致"})
    if bad_res:
        diffs.append(
            {
                "field": "results.result",
                "reason": f"{bad_res} 枚结果无效（冲突、过期须带回 existingRev、existingBatchNo）",
            }
        )
    s = r.get("summary")
    if isinstance(s, dict):
        want = {
            "total": len(results),
            "accepted": counts["ACCEPTED"],
            "duplicate": counts["DUPLICATE"],
            "conflict": counts["CONFLICT"],
            "stale": counts["STALE"],
        }
        if any(s.get(k) != v for k, v in want.items()):
            diffs.append({"field": "summary", "reason": f"与明细不一致：明细为 {want}"})
    else:
        diffs.append({"field": "summary", "reason": "缺少 summary"})
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


def apply_receipt(cu: CurrentUser, batch_no: str, r: dict, method: str) -> Applied:
    """处理一份回执（推送应答或拉取回执）。在独立事务里提交，返回结果；调用方据此应答。"""
    with db.tx():
        b = must_batch(batch_no, lock=True)
        outcome = r.get("outcome") if isinstance(r.get("outcome"), str) else ""
        if b["result"] == VOIDED:
            _archive(b, method, outcome, "VOIDED", "批次已作废", r, cu.emp_no)
            return Applied("VOIDED", b)
        if b["result"] in CONFIRMED:
            _archive(b, method, outcome, "ARCHIVED", "批次早已确认，只存档", r, cu.emp_no)
            return Applied("ARCHIVED", b)
        now = util.now()
        if outcome == "REJECTED":
            reason = f"{r.get('rejectCode') or ''} {r.get('rejectMessage') or ''}".strip() or "MES 整批拒收"
            mark_failed(b, "REJECTED", reason, True, now)
            _archive(b, method, outcome, "REJECTED", reason, r, cu.emp_no)
            audit.record(
                cu,
                audit.entry(audit.SN_RECEIPT)
                .factory(b["factory_code"])
                .pi(b["pi_no"])
                .count(b["qty"])
                .batch(b["batch_no"])
                .why(reason)
                .info(f"method={method} outcome=REJECTED"),
            )
            return Applied("REJECTED", must_batch(batch_no))
        diffs = (
            [{"field": "outcome", "reason": f"未知结果 {outcome or '(空)'}"}]
            if outcome != "PROCESSED"
            else _verify(b, r)
        )
        if diffs:
            text = "；".join(f"{d['field']}: {d['reason']}" for d in diffs)
            mark_failed(b, "RECEIPT_MISMATCH", "回执与批次不符：" + text, False, now)
            _archive(b, method, outcome, "MISMATCH", text, r, cu.emp_no)
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
        confirm(cu, b, method, r["results"], now)
        _archive(b, method, outcome, "CONFIRMED", None, r, cu.emp_no)
        b2 = must_batch(batch_no)
        audit.record(
            cu,
            audit.entry(audit.SN_RECEIPT)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .status(util.TO_PUSH, util.PUSHED if b["batch_type"] == DELIVERY else None)
            .info(
                f"method={method} result={b2['result']} accepted={b2['accepted_qty']} duplicate={b2['duplicate_qty']} "
                f"conflict={b2['conflict_qty']} stale={b2['stale_qty']}"
            ),
        )
    after_confirm(b)
    return Applied("CONFIRMED", b2)


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


def confirm(cu: CurrentUser, b: dict, method: str, results: list[dict] | None, now) -> None:
    """批次确认：交付批次的号改为已推送并记 MES 持有组织；转出批次只在持有组织仍是来源组织时清除。"""
    counts = dict.fromkeys(RESULTS, 0)
    if results is not None:
        rows = []
        for x in results:
            counts[x["result"]] += 1
            rows.append((x["result"], x.get("existingRev"), x.get("existingBatchNo"), b["id"], x["sn"]))
        db.exec_many(
            "UPDATE sn_delivery_item SET receipt_result = %s, existing_rev = %s, existing_batch_no = %s "
            "WHERE batch_id = %s AND sn = %s",
            rows,
        )
        flagged = [x for x in results if x["result"] in ("CONFLICT", "STALE")]
        if flagged:
            item_of = {
                r["sn"]: r["item_id"]
                for r in db.all(
                    f"SELECT sn, item_id FROM sn_delivery_item WHERE batch_id = %s AND sn IN ({marks(len(flagged))})",
                    b["id"],
                    *(x["sn"] for x in flagged),
                )
            }
            db.exec_many(
                "INSERT INTO sn_delivery_alert(alert_type, factory_code, batch_no, item_id, sn, rev, existing_rev, "
                "existing_batch_no, detail, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                [
                    (
                        x["result"],
                        b["factory_code"],
                        b["batch_no"],
                        item_of.get(x["sn"]),
                        x["sn"],
                        x["rev"],
                        x.get("existingRev"),
                        x.get("existingBatchNo"),
                        "疑似重复交付" if x["result"] == "CONFLICT" else "版本过期",
                        now,
                    )
                    for x in flagged
                ],
            )
    result = SUCCESS_CONFLICT if counts["CONFLICT"] else SUCCESS
    db.exec(
        "UPDATE sn_delivery_batch SET result = %s, confirm_method = %s, confirmed_at = %s, accepted_qty = %s, "
        "duplicate_qty = %s, conflict_qty = %s, stale_qty = %s, push_state = 'IDLE', next_retry_at = NULL, "
        "updated_at = %s WHERE id = %s",
        result,
        method,
        now,
        counts["ACCEPTED"],
        counts["DUPLICATE"],
        counts["CONFLICT"],
        counts["STALE"],
        now,
        b["id"],
    )
    if b["batch_type"] == DELIVERY:
        mode = {M_PUSH: util.MODE_PUSH, M_PULL: util.MODE_PULL}.get(method) or (
            util.MODE_PULL if b["source"] == SRC_PULL else util.MODE_PUSH
        )
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


def after_confirm(b: dict) -> None:
    """批次确认后：正在等这一批重试的推送任务接着推后续批次。"""
    key = XO_KEY if b["batch_type"] == TRANSFER_OUT else b["pi_no"]
    db.exec(
        "UPDATE sn_push_task SET state = 'QUEUED', run_after = NULL, updated_at = %s WHERE factory_code = %s "
        "AND task_key = %s AND state = 'WAITING' AND current_batch = %s",
        util.now(),
        b["factory_code"],
        key,
        b["batch_no"],
    )
    push.poke()
