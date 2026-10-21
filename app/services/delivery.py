"""交付批次（5.2.5 第 2–8 条、F-11 推送、F-12 拉取、F-19 只读）。

统一批次：交付批次（含转出批次）只由本系统建立——推送任务建批，或 MES 取批时范围内没有未确认批次而建批；
批次号、所含 SN 及其挂靠和 rev 建立后都不再改变（请求体 body_gz 写定，推送与拉取送达的内容相同）。
批次以 MES 回执确认（推送时在应答中，拉取时另行提交）；回执须与批次逐枚对应，不符时批次记为失败；
先到的回执生效，后到的只存档。

认领：推送建批、MES 拉取建批、对接 MES 组织的页面领取，都从「待推送、未入批、未暂停」的号池用
SELECT … FOR UPDATE SKIP LOCKED 认领，短事务内完成，互不阻塞、互不重复。

推送任务：同组织 + 同一 PI 一个任务（转出批次用 #XO）；批次顺序执行，一批成功再发下一批；
没有可用回执时用原批次号、原内容自动重试（默认 1、5、15 分钟），期间后续批次等待；重试用尽或被拒绝即停，
「继续推送」先重推未确认或失败的批次（含拉取建立的），再认领后续的号。同组织同时运行的任务数不超过推送并发上限，
同一 PI 同一时刻只运行一个任务；租约过期（实例宕机）的任务由其他实例接手。
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import socket
import threading
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import structlog

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.exceptions import MesError
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, changes, factories, locks, mes_client, rules

log = structlog.get_logger()

UNCONFIRMED = "UNCONFIRMED"
SUCCESS = "SUCCESS"
SUCCESS_CONFLICT = "SUCCESS_CONFLICT"
FAILED = "FAILED"
VOIDED = "VOIDED"
CONFIRMED = (SUCCESS, SUCCESS_CONFLICT)
SRC_PUSH = "PUSH"
SRC_PULL = "PULL"
DELIVERY = "DELIVERY"
TRANSFER_OUT = "TRANSFER_OUT"
M_PUSH = "PUSH_RESPONSE"
M_PULL = "PULL_RECEIPT"
M_MANUAL = "MANUAL"
XO_KEY = "#XO"
RESULTS = ("ACCEPTED", "DUPLICATE", "CONFLICT", "STALE")
#: 推送任务租约（秒）；执行中每批续租
LEASE_SECONDS = 300
RUNNER = f"{socket.gethostname()}:{os.getpid()}"

BATCH_FIELDS = (
    "id",
    "batch_no",
    "source",
    "batch_type",
    "factory_code",
    "pi_no",
    "qty",
    "items_digest",
    "result",
    "confirm_method",
    "confirmed_at",
    "fail_code",
    "fail_reason",
    "voidable",
    "accepted_qty",
    "duplicate_qty",
    "conflict_qty",
    "stale_qty",
    "push_state",
    "retry_count",
    "next_retry_at",
    "push_count",
    "repush_count",
    "last_push_at",
    "pulled_at",
    "transfer_kind",
    "transfer_doc_no",
    "rebuilt_from",
    "rebuilt_to",
    "manual_reason",
    "manual_basis",
    "manual_by",
    "manual_at",
    "created_by",
    "created_at",
)


def batch_json(b: dict | None) -> dict | None:
    return util.jrow(b, BATCH_FIELDS, ("voidable",))


def _batch(batch_no: str | None, lock: bool = False) -> dict | None:
    if util.blank(batch_no):
        return None
    return db.one(
        "SELECT * FROM sn_delivery_batch WHERE batch_no = %s" + (" FOR UPDATE" if lock else ""), util.trim(batch_no)
    )


def must_batch(batch_no: str | None, lock: bool = False) -> dict:
    b = _batch(batch_no, lock)
    if b is None:
        raise biz(ErrorCode.BATCH_NOT_FOUND, batch_no=util.trim(batch_no))
    return b


# ================================================================== 建批


def _batch_no(prefix: str, org: str) -> str:
    """PS- / PL- / XO- + 组织 + yyyymmdd + 当天序号（≤ 40 位，全局唯一）。"""
    day = util.now().strftime("%Y%m%d")
    name = f"{prefix}-{org}-{day}"
    with db.new_tx():
        db.exec("INSERT INTO sn_seq(name, val) VALUES (%s, 0) ON DUPLICATE KEY UPDATE name = name", name)
        db.exec("UPDATE sn_seq SET val = val + 1 WHERE name = %s", name)
        n = int(db.scalar("SELECT val FROM sn_seq WHERE name = %s", name))
    return f"{prefix}-{org[:16]}-{day}-{n:06d}"


def _digest(items: list[dict]) -> str:
    return "sha256:" + hashlib.sha256(mes_client.canonical(items)).hexdigest()


def _str_or_none(v: str | None) -> str | None:
    return v if v else None


def _delivery_payload(r: dict) -> dict:
    """交付项（6.3）。十进制流水、长度按字符串；物料为空、规则不带长度时为 null；转移追溯字段选填。"""
    return {
        "pushType": r["push_type"],
        "sn": r["sn"],
        "rev": int(r["rev"]),
        "serial": rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        "serialDec": str(r["seq_dec"]),
        "serialPi": r["seq_pi_no"],
        "pi": r["pi_no"],
        "billNo": r["bill_no"],
        "billSeq": int(r["bill_seq"]),
        "materialCode": _str_or_none(r["material_code"]),
        "length": r["length_text"],
        "customerCode": r["customer_code"],
        "transferKind": r["transfer_kind"],
        "transferDocNo": r["transfer_doc_no"],
        "fromOrgCode": r["transfer_from_org"],
    }


def _transfer_out_payload(e: dict) -> dict:
    """转出项（6.4）：转出前的挂靠 from、转入组织 toOrgCode、转移类型与单号。"""
    return {
        "pushType": util.PUSH_TRANSFER_OUT,
        "sn": e["sn"],
        "rev": int(e["rev"]),
        "serial": rules.seq_text(e["rule_version_id"], e["seq_dec"]),
        "serialDec": str(e["seq_dec"]),
        "serialPi": e["seq_pi_no"],
        "from": {
            "pi": e["pi_no"],
            "billNo": e["bill_no"],
            "billSeq": int(e["bill_seq"]),
            "materialCode": _str_or_none(e["material_code"]),
            "customerCode": e["customer_code"],
        },
        "toOrgCode": e["to_factory"],
        "transferKind": e["transfer_kind"],
        "transferDocNo": e["transfer_doc_no"],
    }


def _insert_batch(
    cu: CurrentUser,
    prefix: str,
    source: str,
    batch_type: str,
    org: str,
    pi: str | None,
    payload: list[dict],
    rows: list[tuple],
    transfer: tuple[str | None, str | None] = (None, None),
    rebuilt_from: str | None = None,
) -> dict:
    """写定批次（请求体、摘要）与明细。rows = sn_delivery_item 的 (item_id, sn, rev, push_type, pi, bill, seq, material,
    customer, seq_pi, seq_dec, to_factory)。"""
    batch_no = _batch_no(prefix, org)
    now = util.now()
    digest = _digest(payload)
    obj = {
        "schemaVersion": "1.0",
        "batchNo": batch_no,
        "batchType": batch_type,
        "orgCode": org,
        "pi": pi,
        "count": len(payload),
        "createdAt": util.fmt(now),
        "itemsDigest": digest,
        "items": payload,
    }
    body = mes_client.gzip_bytes(mes_client.canonical(obj))
    bid = db.insert(
        "INSERT INTO sn_delivery_batch(batch_no, source, batch_type, factory_code, pi_no, qty, items_digest, body_gz, "
        "result, transfer_kind, transfer_doc_no, rebuilt_from, created_by, created_at, updated_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        batch_no,
        source,
        batch_type,
        org,
        pi,
        len(payload),
        digest,
        body,
        UNCONFIRMED,
        transfer[0],
        transfer[1],
        rebuilt_from,
        cu.emp_no,
        now,
        now,
    )
    db.exec_many(
        "INSERT INTO sn_delivery_item(batch_id, line_no, item_id, sn, rev, push_type, pi_no, bill_no, bill_seq, "
        "material_code, customer_code, seq_pi_no, seq_dec, to_factory) VALUES "
        "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        [(bid, i + 1, *r) for i, r in enumerate(rows)],
    )
    return db.one("SELECT * FROM sn_delivery_batch WHERE id = %s", bid)


_CLAIM_COLS = (
    "id, sn, rev, push_type, pi_no, bill_no, bill_seq, material_code, customer_code, seq_pi_no, seq_dec, "
    "length_text, rule_version_id, transfer_kind, transfer_doc_no, transfer_from_org"
)


def claim(org: str, pi: str, limit: int, bill: str | None = None) -> list[dict]:
    """从「待推送、未入批、未暂停」号池按流水顺序认领（当前事务内加锁，已被别处锁住的跳过）。"""
    sql = (
        f"SELECT {_CLAIM_COLS} FROM sn_item WHERE pi_no = %s AND factory_code = %s AND status = 'TO_PUSH' "
        "AND suspend IS NULL AND delivery_batch_no IS NULL AND acquire_batch_no IS NULL"
    )
    args: list = [pi, org]
    if bill:
        sql += " AND bill_no = %s"
        args.append(bill)
    sql += " ORDER BY seq_pi_no, seq_dec LIMIT %s FOR UPDATE SKIP LOCKED"
    return db.all(sql, *args, limit)


def build_delivery(cu: CurrentUser, org: str, pi: str, source: str, bill: str | None = None) -> dict | None:
    """认领并建立一个交付批次（短事务）；没有可认领的号返回 None。"""
    f = factories.must_get(org)
    with db.tx():
        items = claim(org, pi, max(1, int(f["batch_size"])), bill)
        if not items:
            return None
        payload = [_delivery_payload(r) for r in items]
        rows = [
            (
                r["id"],
                r["sn"],
                r["rev"],
                r["push_type"],
                r["pi_no"],
                r["bill_no"],
                r["bill_seq"],
                r["material_code"],
                r["customer_code"],
                r["seq_pi_no"],
                r["seq_dec"],
                None,
            )
            for r in items
        ]
        b = _insert_batch(cu, "PS" if source == SRC_PUSH else "PL", source, DELIVERY, org, pi, payload, rows)
        ids = [r["id"] for r in items]
        for i in range(0, len(ids), 1000):
            part = ids[i : i + 1000]
            n = db.exec(
                f"UPDATE sn_item SET delivery_batch_no = %s, updated_at = %s WHERE id IN ({marks(len(part))}) "
                "AND status = 'TO_PUSH' AND delivery_batch_no IS NULL",
                b["batch_no"],
                util.now(),
                *part,
            )
            if n != len(part):
                raise biz(ErrorCode.CONFLICT_RETRY)
        audit.record(
            cu,
            audit.entry(audit.SN_BATCH_CREATE)
            .factory(org)
            .pi(pi)
            .bill(bill)
            .count(len(items))
            .range(items[0]["sn"], items[-1]["sn"])
            .batch(b["batch_no"])
            .status(util.TO_PUSH, util.TO_PUSH)
            .info(f"source={source} type={DELIVERY}"),
        )
        return b


def build_transfer_out(cu: CurrentUser, org: str, entries: list[dict], rebuilt_from: str | None = None) -> list[dict]:
    """转出批次（在转移事务内调用）：按来源组织当前单批枚数拆分。entries 每枚含转出前挂靠、转入组织、转移类型与单号。"""
    f = factories.get(org)
    size = max(1, int(f["batch_size"])) if f else 5000
    out = []
    for i in range(0, len(entries), size):
        part = entries[i : i + size]
        pis = {e["pi_no"] for e in part}
        payload = [_transfer_out_payload(e) for e in part]
        rows = [
            (
                e["item_id"],
                e["sn"],
                e["rev"],
                util.PUSH_TRANSFER_OUT,
                e["pi_no"],
                e["bill_no"],
                e["bill_seq"],
                e["material_code"],
                e["customer_code"],
                e["seq_pi_no"],
                e["seq_dec"],
                e["to_factory"],
            )
            for e in part
        ]
        b = _insert_batch(
            cu,
            "XO",
            SRC_PUSH,
            TRANSFER_OUT,
            org,
            pis.pop() if len(pis) == 1 else None,
            payload,
            rows,
            (part[0]["transfer_kind"], part[0]["transfer_doc_no"]),
            rebuilt_from,
        )
        audit.record(
            cu,
            audit.entry(audit.SN_BATCH_CREATE)
            .factory(org)
            .pi(b["pi_no"])
            .count(len(part))
            .range(part[0]["sn"], part[-1]["sn"])
            .batch(b["batch_no"])
            .transfer(part[0]["transfer_doc_no"])
            .info(f"source=PUSH type={TRANSFER_OUT}" + (f" rebuilt_from={rebuilt_from}" if rebuilt_from else "")),
        )
        out.append(b)
    return out


def body_obj(b: dict) -> dict:
    import gzip

    return json.loads(gzip.decompress(b["body_gz"]).decode("utf-8"))


# ================================================================== 回执


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
            _fail(b, "REJECTED", reason, True, now)
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
            _fail(b, "RECEIPT_MISMATCH", "回执与批次不符：" + text, False, now)
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
        _confirm(cu, b, method, r["results"], now)
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
    _after_confirm(b)
    return Applied("CONFIRMED", b2)


def _fail(b: dict, code: str, reason: str, voidable: bool, now) -> None:
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


def _confirm(cu: CurrentUser, b: dict, method: str, results: list[dict] | None, now) -> None:
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


def _after_confirm(b: dict) -> None:
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
    poke()


# ================================================================== 推送任务


_wake = threading.Event()
executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="sn-push")
_running_here: set[tuple[str, str]] = set()
_running_lock = threading.Lock()


def poke() -> None:
    _wake.set()


def start_task(cu: CurrentUser, org: str, key: str, resend: bool = False) -> None:
    """启动（或排队）推送任务：不存在 / 已结束 → 排队；运行中 → 结束后再排一次。"""
    now = util.now()
    with db.tx():
        t = db.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s FOR UPDATE", org, key)
        if t is None:
            db.exec(
                "INSERT INTO sn_push_task(factory_code, task_key, state, resend, started_by, started_at, updated_at) "
                "VALUES (%s,%s,'QUEUED',%s,%s,%s,%s)",
                org,
                key,
                1 if resend else 0,
                cu.emp_no,
                now,
                now,
            )
        elif t["state"] == "RUNNING":
            db.exec(
                "UPDATE sn_push_task SET requeue = 1, resend = resend OR %s, updated_at = %s WHERE factory_code = %s "
                "AND task_key = %s",
                1 if resend else 0,
                now,
                org,
                key,
            )
        elif t["state"] == "WAITING" and not resend:
            pass
        else:
            db.exec(
                "UPDATE sn_push_task SET state = 'QUEUED', run_after = NULL, resend = %s, requeue = 0, message = '', "
                "started_by = %s, started_at = %s, updated_at = %s WHERE factory_code = %s AND task_key = %s",
                1 if resend else 0,
                cu.emp_no,
                now,
                now,
                org,
                key,
            )
    poke()


def resume(cu: CurrentUser, org_in: str | None, key_in: str | None) -> dict:
    """继续推送：重置未确认、失败批次的自动重试，按原批次号、原内容先重推，再认领后续的号。"""
    org = factories.must_get(org_in)["factory_code"]
    key = util.trim(key_in)
    if not key:
        raise biz(ErrorCode.PI_REQUIRED)
    btype = TRANSFER_OUT if key == XO_KEY else DELIVERY
    with db.tx():
        cond = "batch_type = %s" + ("" if key == XO_KEY else " AND pi_no = %s")
        args = [btype] + ([] if key == XO_KEY else [key])
        n = db.exec(
            "UPDATE sn_delivery_batch SET retry_count = 0, push_state = 'IDLE', next_retry_at = NULL, "
            f"repush_count = repush_count + 1, updated_at = %s WHERE factory_code = %s AND {cond} "
            "AND result IN ('UNCONFIRMED','FAILED')",
            util.now(),
            org,
            *args,
        )
        remaining = remaining_pushes(org, key)
        if not n and not remaining["unbatched"]:
            raise biz(ErrorCode.PUSH_NOTHING, factory=org, pi=key)
        audit.record(
            cu,
            audit.entry(audit.SN_PUSH_RESUME)
            .factory(org)
            .pi(None if key == XO_KEY else key)
            .count(remaining["unbatched"])
            .info(f"key={key} batches={n} remaining={remaining['pushes']}"),
        )
    start_task(cu, org, key, resend=True)
    return {"factory_code": org, "key": key, **remaining}


def remaining_pushes(org: str, key: str) -> dict:
    """「剩余 N 次」：未入批且未暂停的号按单批枚数折算的次数 + 未确认或失败的批次数。"""
    f = factories.get(org)
    size = max(1, int(f["batch_size"])) if f else 5000
    if key == XO_KEY:
        unbatched = 0
        batches = db.count(
            "SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND batch_type = %s "
            "AND result IN ('UNCONFIRMED','FAILED')",
            org,
            TRANSFER_OUT,
        )
    else:
        unbatched = db.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s AND status = 'TO_PUSH' "
            "AND suspend IS NULL AND delivery_batch_no IS NULL AND acquire_batch_no IS NULL",
            key,
            org,
        )
        batches = db.count(
            "SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND pi_no = %s AND batch_type = %s "
            "AND result IN ('UNCONFIRMED','FAILED')",
            org,
            key,
            DELIVERY,
        )
    return {"unbatched": unbatched, "batches": batches, "pushes": math.ceil(unbatched / size) + batches}


def task_state(org: str, key: str) -> dict | None:
    t = db.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, key)
    return None if t is None else {k: util.jv(v) for k, v in t.items()}


def kick_bill(bill_no: str) -> None:
    """单据解除暂停 / 号回到未入批后：涉及的（组织，PI）有可推送的号时启动推送。"""
    for r in db.all(
        "SELECT DISTINCT factory_code, pi_no FROM sn_item WHERE bill_no = %s AND status = 'TO_PUSH' "
        "AND delivery_batch_no IS NULL AND acquire_batch_no IS NULL AND suspend IS NULL",
        bill_no,
    ):
        if factories.is_mes(r["factory_code"]):
            start_task(CurrentUser.system(), r["factory_code"], r["pi_no"])


def _claim_task() -> dict | None:
    """挑一个可运行的任务并占用（多实例安全）。"""
    now = util.now()
    rows = db.all(
        "SELECT t.*, f.push_concurrency FROM sn_push_task t JOIN sn_factory f ON f.factory_code = t.factory_code "
        "WHERE t.state = 'QUEUED' OR (t.state = 'WAITING' AND t.run_after <= %s) "
        "OR (t.state = 'RUNNING' AND t.lease_until < %s) ORDER BY t.updated_at LIMIT 50",
        now,
        now,
    )
    for t in rows:
        busy_org = db.count(
            "SELECT COUNT(*) FROM sn_push_task WHERE factory_code = %s AND state = 'RUNNING' AND lease_until >= %s",
            t["factory_code"],
            now,
        )
        if busy_org >= int(t["push_concurrency"]):
            continue
        if t["task_key"] != XO_KEY and db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_push_task WHERE task_key = %s AND state = 'RUNNING' AND lease_until >= %s)",
            t["task_key"],
            now,
        ):
            continue
        n = db.exec(
            "UPDATE sn_push_task SET state = 'RUNNING', lease_until = %s, runner = %s, requeue = 0, updated_at = %s "
            "WHERE factory_code = %s AND task_key = %s AND state = %s AND updated_at = %s",
            now + timedelta(seconds=LEASE_SECONDS),
            RUNNER,
            now,
            t["factory_code"],
            t["task_key"],
            t["state"],
            t["updated_at"],
        )
        if n == 1:
            return t
    return None


def run_pending(wait: bool = True) -> int:
    """跑完当前所有可运行的任务（测试、调度线程调用）；返回执行的任务数。wait = False 时提交到线程池即返回。"""
    count = 0
    while True:
        t = _claim_task()
        if t is None:
            return count
        count += 1
        if wait:
            _run_task(t)
        else:
            executor.submit(_run_task, t)


def _renew(t: dict, batch_no: str | None) -> None:
    db.exec(
        "UPDATE sn_push_task SET lease_until = %s, current_batch = %s, updated_at = %s WHERE factory_code = %s "
        "AND task_key = %s AND state = 'RUNNING'",
        util.now() + timedelta(seconds=LEASE_SECONDS),
        batch_no,
        util.now(),
        t["factory_code"],
        t["task_key"],
    )


def _end_task(t: dict, state: str, message: str = "", run_after=None, batch_no: str | None = None) -> None:
    with db.tx():
        cur = db.one(
            "SELECT requeue FROM sn_push_task WHERE factory_code = %s AND task_key = %s FOR UPDATE",
            t["factory_code"],
            t["task_key"],
        )
        if cur is not None and cur["requeue"] and state == "DONE":
            state = "QUEUED"
        db.exec(
            "UPDATE sn_push_task SET state = %s, run_after = %s, lease_until = NULL, current_batch = %s, message = %s, "
            "requeue = 0, resend = IF(%s IN ('DONE','STOPPED'), 0, resend), updated_at = %s "
            "WHERE factory_code = %s AND task_key = %s",
            state,
            run_after,
            batch_no,
            util.cut(message, 500),
            state,
            util.now(),
            t["factory_code"],
            t["task_key"],
        )
    if state == "QUEUED":
        poke()


def _next_batch(t: dict, resend: bool) -> dict | str | None:
    """下一个要发的批次：先未确认 / 失败的（续推时含拉取建立的、失败的），再认领新号建批。
    返回批次，或 'WAIT:<批次号>'（等重试）/ 'STOP:<原因>'。"""
    org, key = t["factory_code"], t["task_key"]
    btype = TRANSFER_OUT if key == XO_KEY else DELIVERY
    cond = "factory_code = %s AND batch_type = %s" + ("" if key == XO_KEY else " AND pi_no = %s")
    args = [org, btype] + ([] if key == XO_KEY else [key])
    pending = db.all(
        f"SELECT * FROM sn_delivery_batch WHERE {cond} AND result IN ('UNCONFIRMED','FAILED') ORDER BY created_at, id",
        *args,
    )
    for b in pending:
        if b["result"] == FAILED:
            if resend:
                return b
            return f"STOP:批次 {b['batch_no']} 失败：{b['fail_reason'] or b['fail_code']}"
        if b["push_state"] == "WAIT_RETRY":
            if b["next_retry_at"] is not None and b["next_retry_at"] > util.now():
                return f"WAIT:{b['batch_no']}"
            return b
        if b["push_state"] == "EXHAUSTED":
            if resend:
                return b
            return f"STOP:批次 {b['batch_no']} 自动重试用尽仍未确认"
        if b["source"] == SRC_PULL and not resend:
            # MES 取走、尚未回执的批次：自动推送不抢着发，续推时才重推
            continue
        return b
    if key == XO_KEY or not factories.is_mes(org):
        return None
    return build_delivery(CurrentUser.system(), org, key, SRC_PUSH)


def _run_task(t: dict) -> None:
    key = (t["factory_code"], t["task_key"])
    with _running_lock:
        if key in _running_here:
            return
        _running_here.add(key)
    try:
        _drive(t)
    except Exception as e:  # noqa: BLE001
        log.exception("push_task_failed", org=t["factory_code"], key=t["task_key"])
        _end_task(t, "STOPPED", f"推送任务异常：{type(e).__name__}")
    finally:
        with _running_lock:
            _running_here.discard(key)


def _drive(t: dict) -> None:
    sent = 0
    resend = bool(t["resend"])
    seen_resend: set[int] = set()
    while True:
        nxt = _next_batch(t, resend)
        if nxt is None:
            other = (
                db.count(
                    "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s AND status IN "
                    "('TO_PUSH','PUSHED','ACQUIRED') "
                    "AND delivery_mode IN ('PULL','PAGE')",
                    t["task_key"],
                    t["factory_code"],
                )
                if t["task_key"] != XO_KEY
                else 0
            )
            _end_task(
                t, "DONE", f"已推送 {sent} 批" + (f"；其余 {other} 枚已由 MES 拉取 / 在领取页领取" if other else "")
            )
            return
        if isinstance(nxt, str):
            kind, _, info = nxt.partition(":")
            if kind == "WAIT":
                b = must_batch(info)
                _end_task(t, "WAITING", f"批次 {info} 等待自动重试", b["next_retry_at"], info)
            else:
                _end_task(t, "STOPPED", info)
            return
        if resend and nxt["id"] in seen_resend:
            _end_task(t, "STOPPED", f"批次 {nxt['batch_no']} 续推仍未确认")
            return
        if resend and nxt["result"] == FAILED:
            seen_resend.add(nxt["id"])
        _renew(t, nxt["batch_no"])
        ok = push_once(nxt)
        if ok == "CONFIRMED":
            sent += 1
            continue
        b = must_batch(nxt["batch_no"])
        if b["result"] in CONFIRMED:
            continue
        if b["push_state"] == "WAIT_RETRY":
            _end_task(t, "WAITING", f"批次 {b['batch_no']} 等待自动重试", b["next_retry_at"], b["batch_no"])
            return
        _end_task(
            t, "STOPPED", f"批次 {b['batch_no']}：{b['fail_reason'] or '自动重试用尽仍未确认'}", None, b["batch_no"]
        )
        return


def push_once(b: dict) -> str:
    """发送一次。返回 CONFIRMED / FAILED / RETRY / EXHAUSTED / SKIPPED。"""
    b = must_batch(b["batch_no"])
    if b["result"] not in (UNCONFIRMED, FAILED):
        return "CONFIRMED" if b["result"] in CONFIRMED else "SKIPPED"
    f = factories.must_get(b["factory_code"])
    target = mes_client.Target.of(f)
    attempt = b["push_count"] + 1
    out = mes_client.send(target, b["batch_no"], b["body_gz"], attempt)
    now = util.now()
    sys = CurrentUser.system()
    db.exec(
        "UPDATE sn_delivery_batch SET push_count = push_count + 1, last_push_at = %s, updated_at = %s WHERE id = %s",
        now,
        now,
        b["id"],
    )
    if out.kind == mes_client.RECEIPT:
        applied = apply_receipt(sys, b["batch_no"], out.receipt or {}, M_PUSH)
        audit.record_isolated(
            sys,
            audit.entry(audit.SN_PUSH)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .info(f"attempt={attempt} verdict={applied.verdict}")
            if applied.verdict in ("CONFIRMED", "ARCHIVED")
            else audit.entry(audit.SN_PUSH)
            .fail(f"MES 应答：{applied.verdict}")
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .info(f"attempt={attempt}"),
        )
        if f["cfg_status"] != "OK":
            db.exec(
                "UPDATE sn_factory SET cfg_status = 'OK', cfg_message = '' WHERE factory_code = %s", f["factory_code"]
            )
        return "CONFIRMED" if applied.verdict in ("CONFIRMED", "ARCHIVED") else "FAILED"
    if out.kind == mes_client.RETRY:
        intervals = settings.push_retry_intervals
        with db.tx():
            cur = must_batch(b["batch_no"], lock=True)
            if cur["result"] in CONFIRMED:
                return "CONFIRMED"
            if cur["retry_count"] < len(intervals):
                wait = intervals[cur["retry_count"]] * 60
                if out.retry_after is not None:
                    wait = max(wait, out.retry_after)
                db.exec(
                    "UPDATE sn_delivery_batch SET retry_count = retry_count + 1, push_state = 'WAIT_RETRY', "
                    "next_retry_at = %s, fail_code = %s, fail_reason = %s, updated_at = %s WHERE id = %s",
                    now + timedelta(seconds=wait),
                    out.code,
                    util.cut(out.message, 1000),
                    now,
                    b["id"],
                )
                state = "RETRY"
            else:
                db.exec(
                    "UPDATE sn_delivery_batch SET push_state = 'EXHAUSTED', next_retry_at = NULL, fail_code = %s, "
                    "fail_reason = %s, updated_at = %s WHERE id = %s",
                    out.code,
                    util.cut(out.message, 1000),
                    now,
                    b["id"],
                )
                state = "EXHAUSTED"
            audit.record(
                sys,
                audit.entry(audit.SN_PUSH)
                .fail(out.message)
                .factory(b["factory_code"])
                .pi(b["pi_no"])
                .count(b["qty"])
                .batch(b["batch_no"])
                .info(f"attempt={attempt} code={out.code} next={state}"),
            )
        return state
    with db.tx():
        cur = must_batch(b["batch_no"], lock=True)
        if cur["result"] in CONFIRMED:
            return "CONFIRMED"
        _fail(cur, out.code, out.message, out.voidable, now)
        if out.config_problem:
            db.exec(
                "UPDATE sn_factory SET cfg_status = 'FAILED', cfg_message = %s WHERE factory_code = %s",
                util.cut(out.message, 500),
                f["factory_code"],
            )
        audit.record(
            sys,
            audit.entry(audit.SN_PUSH)
            .fail(out.message)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .info(f"attempt={attempt} code={out.code} http={out.http_status}"),
        )
    return "FAILED"


class _Scheduler:
    def __init__(self) -> None:
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, name="sn-push-scheduler", daemon=True)

    def start(self) -> _Scheduler:
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        _wake.set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            _wake.wait(settings.push_poll_seconds)
            _wake.clear()
            if self._stop.is_set():
                return
            try:
                run_pending(wait=False)
            except Exception:  # noqa: BLE001
                log.exception("push_scheduler_failed")


def start_scheduler() -> _Scheduler | None:
    return _Scheduler().start() if settings.push_enabled else None


# ================================================================== 人工处理


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
        if b["result"] not in (UNCONFIRMED, FAILED):
            raise biz(ErrorCode.BATCH_NOT_PENDING, batch_no=b["batch_no"], result=b["result"])
        now = util.now()
        _confirm(cu, b, M_MANUAL, None, now)
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
    _after_confirm(b)
    return batch_json(must_batch(b["batch_no"]))


def void(cu: CurrentUser, batch_no: str | None, reason: str | None, basis: str | None) -> dict:
    """作废：MES 没有处理的批次（整批拒绝、报文过大、认证或地址错误，或书面确认未处理的未确认批次）。
    交付批次的号清除批次号、回到待推送（未入批），rev 不变；转出批次按原明细重建新的转出批次并自动推送。"""
    r, bs = _check_manual(reason, basis)
    rebuilt: list[dict] = []
    with db.tx():
        b = must_batch(batch_no, lock=True)
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
        if b["batch_type"] == DELIVERY:
            bills = set(db.column("SELECT DISTINCT bill_no FROM sn_item WHERE delivery_batch_no = %s", b["batch_no"]))
            db.exec(
                "UPDATE sn_item SET delivery_batch_no = NULL, updated_at = %s WHERE delivery_batch_no = %s "
                "AND status = 'TO_PUSH'",
                now,
                b["batch_no"],
            )
            changes.refresh_items(bills)
        else:
            entries = [
                {**e, "transfer_kind": b["transfer_kind"], "transfer_doc_no": b["transfer_doc_no"]}
                for e in db.all(
                    "SELECT d.item_id, d.sn, d.rev, d.pi_no, d.bill_no, d.bill_seq, d.material_code, d.customer_code, "
                    "d.seq_pi_no, d.seq_dec, d.to_factory, i.rule_version_id FROM sn_delivery_item d "
                    "JOIN sn_item i ON i.id = d.item_id WHERE d.batch_id = %s ORDER BY d.line_no",
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
    if b["batch_type"] == DELIVERY:
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


def handle_alert(cu: CurrentUser, alert_id: int, action: str | None, note: str | None) -> dict:
    """以本系统为准重推（rev 调为 max(本系统, MES 已存) + 1，以改挂推给 MES 持有组织）/ 关闭告警。"""
    act = util.trim(action)
    why = util.trim(note)
    if not why or len(why) > 500:
        raise biz(ErrorCode.REASON_REQUIRED)
    org = None
    pi = None
    with db.tx():
        a = db.one("SELECT * FROM sn_delivery_alert WHERE id = %s FOR UPDATE", alert_id)
        if a is None or a["status"] != "OPEN":
            raise biz(ErrorCode.DALERT_NOT_FOUND)
        if act == "RESEND":
            if a["item_id"] is None:
                raise biz(ErrorCode.DALERT_RESEND_BLOCKED, sn=a["sn"] or "", detail="不是逐枚告警")
            it = db.one("SELECT * FROM sn_item WHERE id = %s FOR UPDATE", a["item_id"])
            if it is None or it["mes_holder"] is None or it["mes_holder"] != it["factory_code"]:
                raise biz(
                    ErrorCode.DALERT_RESEND_BLOCKED,
                    sn=a["sn"] or "",
                    detail="号已转走或 MES 持有组织不是号当前所属组织",
                )
            if it["status"] == util.TO_PUSH and it["delivery_batch_no"] is not None:
                raise biz(ErrorCode.DALERT_RESEND_BLOCKED, sn=a["sn"] or "", detail="号在未确认或失败的批次中")
            if it["status"] not in (util.PUSHED, util.TO_PUSH):
                raise biz(ErrorCode.DALERT_RESEND_BLOCKED, sn=a["sn"] or "", detail=f"号当前状态为 {it['status']}")
            if frozen_by_transfer_out([it["id"]]):
                raise biz(ErrorCode.DALERT_RESEND_BLOCKED, sn=a["sn"] or "", detail="号在未确认或失败的转出批次中")
            rev = max(int(it["rev"]), int(a["existing_rev"] or 0)) + 1
            db.exec(
                "UPDATE sn_item SET rev = %s, status = 'TO_PUSH', push_type = 'REASSIGN', delivery_batch_no = NULL, "
                "transfer_kind = NULL, transfer_doc_no = NULL, transfer_from_org = NULL, updated_at = %s WHERE id = %s",
                rev,
                util.now(),
                it["id"],
            )
            org, pi = it["factory_code"], it["pi_no"]
            detail = f"rev {it['rev']} -> {rev}"
        elif act == "CLOSE":
            detail = "closed"
        else:
            raise biz(ErrorCode.ALERT_ACTION_INVALID)
        db.exec(
            "UPDATE sn_delivery_alert SET status = 'CLOSED', close_action = %s, closed_by = %s, closed_at = %s, note "
            "= %s "
            "WHERE id = %s",
            act,
            cu.emp_no,
            util.now(),
            why,
            alert_id,
        )
        audit.record(
            cu,
            audit.entry(audit.SN_ALERT_HANDLE)
            .factory(a["factory_code"])
            .pi(pi)
            .batch(a["batch_no"])
            .range(a["sn"], a["sn"])
            .why(why)
            .info(f"alert={alert_id} type={a['alert_type']} action={act} {detail}"),
        )
    if org is not None and factories.is_mes(org):
        start_task(cu, org, pi)
    return {"id": alert_id, "action": act}


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
    fsql, fargs = factories.scope_sql(cu.factories or None)
    where += fsql
    args += fargs
    total = db.count("SELECT COUNT(*) FROM sn_delivery_alert" + where, *args)
    rows = db.all(
        f"SELECT * FROM sn_delivery_alert{where} ORDER BY id DESC LIMIT %s, %s", *args, page.offset, page.size
    )
    return util.page_result([{k: util.jv(v) for k, v in r.items()} for r in rows], total, page)


# ================================================================== MES 拉取（F-12）


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


def _earliest_unconfirmed(org: str, mes: bool, pi: str | None, bill: str | None, exclude: int | None = None):
    dc, da, xc, xa = _scope_conds(pi, bill)
    ex = "" if exclude is None else " AND b.id <> %s"
    exa = [] if exclude is None else [exclude]
    parts, args = [], []
    if mes:
        parts.append(
            "SELECT b.* FROM sn_delivery_batch b WHERE b.factory_code = %s AND b.batch_type = 'DELIVERY' "
            "AND b.result = 'UNCONFIRMED'" + dc + ex
        )
        args += [org, *da, *exa]
    parts.append(
        "SELECT b.* FROM sn_delivery_batch b WHERE b.factory_code = %s AND b.batch_type = 'TRANSFER_OUT' "
        "AND b.result = 'UNCONFIRMED'" + xc + ex
    )
    args += [org, *xa, *exa]
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
            "AND delivery_batch_no IS NULL AND acquire_batch_no IS NULL" + cond,
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
    """接口 ③ 取下一批。带 batchNo 时只读取回本组织该未确认或失败批次；否则先给范围内最早的未确认批次，没有才建批。"""
    org = cu.factory_code
    _rate_limit(org)
    f = factories.must_get(org)
    mes = bool(f["mes_enabled"])
    pi, bill = util.trim(pi_in) or None, util.trim(bill_in) or None
    batch_no = util.trim(batch_no_in) or None
    if batch_no:
        b = _batch(batch_no)
        if b is None or b["factory_code"] != org:
            raise MesError(404, "BATCH_NOT_FOUND", "批次号不存在，或不属于本组织", batch_no)
        if b["result"] == VOIDED:
            raise MesError(409, "BATCH_VOIDED", "该批已作废，丢弃这批内容", batch_no)
        if b["result"] in CONFIRMED:
            raise MesError(409, "BATCH_CONFIRMED", "该批已确认，不用再处理", batch_no)
        return _next_out(b, False, *_remaining(org, mes, None, None, b["id"]))
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
                    "AND delivery_batch_no IS NULL AND acquire_batch_no IS NULL"
                    + cond
                    + " ORDER BY seq_pi_no, seq_dec LIMIT 1",
                    org,
                    *cargs,
                )
            if target_pi is not None:
                b = build_delivery(cu, org, target_pi, SRC_PULL, bill)
                created = b is not None
        if b is not None:
            db.exec("UPDATE sn_delivery_batch SET pulled_at = %s WHERE id = %s", util.now(), b["id"])
    remaining, paused = _remaining(org, mes, pi, bill, None if b is None else b["id"])
    if b is None:
        changes.count_daily("MES_PULL_EMPTY", org)
        return _next_out(None, False, remaining, paused)
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
    return _next_out(must_batch(b["batch_no"]), created, remaining, paused)


def _next_out(b: dict | None, created: bool, remaining: int, paused: int) -> dict:
    return {
        "schemaVersion": "1.0",
        "code": "OK",
        "batch": None if b is None else body_obj(b),
        "batchSource": None if b is None else b["source"],
        "newlyCreated": created,
        "batchResult": None if b is None else b["result"],
        "remaining": remaining,
        "paused": paused,
    }


def submit_receipt(cu: CurrentUser, batch_no: str, body: dict) -> dict:
    """接口 ④ 提交回执（或整批拒收）。按批次号幂等：已确认的只存档；已作废的拒绝；不符时拒收并记批次失败。"""
    org = cu.factory_code
    _rate_limit(org)
    b = _batch(batch_no)
    if b is None or b["factory_code"] != org:
        raise MesError(404, "BATCH_NOT_FOUND", "批次号不存在，或不属于本组织", batch_no)
    if b["result"] == VOIDED:
        raise MesError(409, "BATCH_VOIDED", "批次已作废，不得再处理这批内容", batch_no)
    applied = apply_receipt(cu, b["batch_no"], body, M_PULL)
    if applied.verdict == "VOIDED":
        raise MesError(409, "BATCH_VOIDED", "批次已作废，不得再处理这批内容", batch_no)
    if applied.verdict == "MISMATCH":
        raise MesError(422, "RECEIPT_MISMATCH", "回执与批次不符", batch_no, applied.details)
    return {
        "schemaVersion": "1.0",
        "code": "OK",
        "batchNo": b["batch_no"],
        "batchResult": applied.batch["result"],
        "firstConfirmation": applied.verdict == "CONFIRMED",
    }


# ================================================================== 只读接口（F-19）


def _cursor(v: str | None) -> int:
    try:
        return max(0, int(util.trim(v) or 0))
    except ValueError:
        return 0


def _page_size(v: int | None) -> int:
    return 1000 if v is None or v < 1 else min(v, 5000)


_READ_COLS = (
    "id, sn, rev, status, factory_code, pi_no, bill_no, bill_seq, material_code, customer_code, seq_pi_no, seq_dec, "
    "length_text, rule_version_id, delivery_batch_no, delivered_at"
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
        "materialCode": _str_or_none(r["material_code"]),
        "length": r["length_text"],
        "customerCode": r["customer_code"],
        "orgCode": r["factory_code"],
        "status": r["status"],
        "batchNo": r["delivery_batch_no"],
    }


def read_delivered(
    cu: CurrentUser, org_in: str | None, pi_in: str | None, cursor: str | None, size: int | None
) -> dict:
    """接口 ⑤：MES 接口账户只得到已交付给本组织 MES 的号（已推送，含拉取）；其他账户按可看范围、按生产组织和 PI。
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
    return {
        "schemaVersion": "1.0",
        "code": "OK",
        "items": [_read_item(r) for r in rows],
        "nextCursor": str(rows[-1]["id"]) if more else None,
    }


def read_batch_items(cu: CurrentUser, batch_no: str, cursor: str | None, size: int | None) -> dict:
    """接口 ⑥：按批次号查询仍在该批次中的号（已转到别的组织的不返回，整批移出时为 0 枚）。只读。"""
    if cu.is_mes:
        _rate_limit(cu.factory_code)
    b = _batch(batch_no)
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
    return {
        "schemaVersion": "1.0",
        "code": "OK",
        "batchNo": b["batch_no"],
        "items": [_read_item(r) for r in rows],
        "nextCursor": str(rows[-1]["id"]) if more else None,
    }


# ================================================================== 页面查询（F-18 交付批次）


def list_batches(
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    batch_no: str | None,
    source: str | None,
    batch_type: str | None,
    result: str | None,
    transfer_no: str | None,
    page: util.Page,
) -> dict:
    fsql, fargs = factories.scope_sql(cu.read_factories(factory))
    where = " WHERE 1=1" + fsql
    args = list(fargs)
    for col, v in (
        ("pi_no", pi),
        ("batch_no", batch_no),
        ("source", source),
        ("batch_type", batch_type),
        ("result", result),
        ("transfer_doc_no", transfer_no),
    ):
        if not util.blank(v):
            where += f" AND {col} = %s"
            args.append(util.trim(v))
    total = db.count("SELECT COUNT(*) FROM sn_delivery_batch" + where, *args)
    rows = db.all(
        f"SELECT {', '.join(BATCH_FIELDS)} FROM sn_delivery_batch{where} ORDER BY created_at DESC, id DESC LIMIT %s, "
        "%s",
        *args,
        page.offset,
        page.size,
    )
    return util.page_result([batch_json(r) for r in rows], total, page)


def batch_detail(cu: CurrentUser, batch_no: str | None) -> dict:
    b = must_batch(batch_no)
    cu.check_read(b["factory_code"])
    out = batch_json(b)
    out["receipts"] = [
        {k: util.jv(v) for k, v in r.items()}
        for r in db.all(
            "SELECT id, method, outcome, verdict, detail, created_by, created_at FROM sn_delivery_receipt "
            "WHERE batch_id = %s ORDER BY id",
            b["id"],
        )
    ]
    out["segments"] = segments_of(b)
    out["bills"] = db.column(
        "SELECT DISTINCT bill_no FROM sn_delivery_item WHERE batch_id = %s ORDER BY bill_no", b["id"]
    )
    out["materials"] = db.column(
        "SELECT DISTINCT material_code FROM sn_delivery_item WHERE batch_id = %s ORDER BY material_code", b["id"]
    )
    return out


def segments_of(b: dict) -> list[dict]:
    """号段清单：连续的号合并为一段（被拉取、领取打散后可有多段）。"""
    # 段内首尾号用窗口函数一并取出（一个批次内同一流水归属 PI 的十进制流水不重复）
    rows = db.all(
        "SELECT seq_pi_no, MIN(seq_dec) s, MAX(seq_dec) e, COUNT(*) n, MAX(first_sn) first_sn, MAX(last_sn) last_sn "
        "FROM (SELECT seq_pi_no, seq_dec, g, FIRST_VALUE(sn) OVER w first_sn, "
        "LAST_VALUE(sn) OVER (w ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) last_sn "
        "FROM (SELECT seq_pi_no, seq_dec, sn, seq_dec - ROW_NUMBER() OVER (PARTITION BY seq_pi_no ORDER BY seq_dec) g "
        "FROM sn_delivery_item WHERE batch_id = %s) t WINDOW w AS (PARTITION BY seq_pi_no, g ORDER BY seq_dec)) u "
        "GROUP BY seq_pi_no, g ORDER BY seq_pi_no, s LIMIT 500",
        b["id"],
    )
    return [
        {
            "seq_pi_no": r["seq_pi_no"],
            "start_seq": int(r["s"]),
            "end_seq": int(r["e"]),
            "qty": int(r["n"]),
            "start_sn": r["first_sn"],
            "end_sn": r["last_sn"],
        }
        for r in rows
    ]


def batch_items_page(cu: CurrentUser, batch_no: str | None, page: util.Page) -> dict:
    b = must_batch(batch_no)
    cu.check_read(b["factory_code"])
    total = b["qty"]
    rows = db.all(
        "SELECT d.*, i.status, i.factory_code current_factory, i.rev current_rev FROM sn_delivery_item d "
        "LEFT JOIN sn_item i ON i.id = d.item_id WHERE d.batch_id = %s ORDER BY d.line_no LIMIT %s, %s",
        b["id"],
        page.offset,
        page.size,
    )
    return util.page_result([{k: util.jv(v) for k, v in r.items()} for r in rows], total, page)


def export_rows(b: dict) -> Iterator[list]:
    last = 0
    while True:
        rows = db.all(
            "SELECT * FROM sn_delivery_item WHERE batch_id = %s AND line_no > %s ORDER BY line_no LIMIT 5000",
            b["id"],
            last,
        )
        if not rows:
            return
        for r in rows:
            last = r["line_no"]
            yield [
                r["line_no"],
                r["sn"],
                r["rev"],
                r["push_type"],
                r["seq_pi_no"],
                r["seq_dec"],
                r["pi_no"],
                r["bill_no"],
                r["bill_seq"],
                r["material_code"],
                r["customer_code"],
                r["to_factory"],
                r["receipt_result"],
                r["existing_rev"],
                r["existing_batch_no"],
            ]


EXPORT_HEADERS = [
    "行号",
    "完整SN",
    "rev",
    "推送类型",
    "流水归属PI",
    "十进制流水",
    "PI（转出为转出前）",
    "单据编号",
    "单据序号",
    "物料编码",
    "客户编码",
    "转入生产组织",
    "回执结果",
    "MES已存rev",
    "MES已存批次",
]
