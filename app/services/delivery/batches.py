"""交付批次的建立：批次号、报文（交付项 6.3、转出项 6.4）、写定请求体与明细；从「待推送、未入批、未暂停」号池认领。

批次号、所含 SN 及其挂靠和 rev 建立后都不再改变（请求体 body_gz 写定，推送与拉取送达的内容相同）。
"""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import socket

import structlog

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, factories, mes_client, rules

log = structlog.get_logger()

UNCONFIRMED = "UNCONFIRMED"
SUCCESS = "SUCCESS"
#: 旧协议逐枚回执含冲突时的结果，只留给历史批次
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
#: 交付项的业务来源（选填，仅供 MES 参考，处理以 rev 为准）
BIZ_FIRST = "FIRST"
BIZ_SAME_ORG = "SAME_ORG_TRANSFER"
BIZ_CROSS_ORG = "CROSS_ORG_TRANSFER"
BIZ_REMAP = "REMAP"
#: MES 回执 code：OK 整批已处理 / NG 整批退回
RECEIPT_OK = "OK"
RECEIPT_NG = "NG"
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


def find_batch(batch_no: str | None, lock: bool = False) -> dict | None:
    if util.blank(batch_no):
        return None
    return db.one(
        "SELECT * FROM sn_delivery_batch WHERE batch_no = %s" + (" FOR UPDATE" if lock else ""), util.trim(batch_no)
    )


def must_batch(batch_no: str | None, lock: bool = False) -> dict:
    b = find_batch(batch_no, lock)
    if b is None:
        raise biz(ErrorCode.BATCH_NOT_FOUND, batch_no=util.trim(batch_no))
    return b


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


def str_or_none(v: str | None) -> str | None:
    return v if v else None


def biz_type(r: dict, org: str) -> str:
    """交付项的业务来源：最近一次转移（转单、转组织）按前后组织分同组织 / 跨组织；未经转移的改挂（确认物料对应、
    告警重推）为 REMAP；其余为首次下发。"""
    if r["transfer_from_org"]:
        return BIZ_SAME_ORG if r["transfer_from_org"] == org else BIZ_CROSS_ORG
    return BIZ_REMAP if r["push_type"] == util.PUSH_REASSIGN else BIZ_FIRST


def _delivery_payload(r: dict, org: str) -> dict:
    """交付项（6.3）。十进制流水、米标按字符串；物料为空、规则不带米标时为 null。MES 逐枚按 rev 新增或覆盖；
    bizType 只说明业务来源。"""
    return {
        "sn": r["sn"],
        "rev": int(r["rev"]),
        "serial": rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        "serialDec": str(r["seq_dec"]),
        "serialPi": r["seq_pi_no"],
        "pi": r["pi_no"],
        "billNo": r["bill_no"],
        "billSeq": int(r["bill_seq"]),
        "materialCode": str_or_none(r["material_code"]),
        "length": r["length_text"],
        "customerCode": r["customer_code"],
        "bizType": biz_type(r, org),
    }


def _transfer_out_payload(e: dict) -> dict:
    """转出项（6.4）：只有 SN、rev 与转入组织；MES 据此标记已转走（本地没有时按 SN、rev 建一条已转走记录）。"""
    return {"sn": e["sn"], "rev": int(e["rev"]), "toOrgCode": e["to_factory"]}


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
    "length_text, rule_version_id, transfer_from_org"
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
        payload = [_delivery_payload(r, org) for r in items]
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
    return json.loads(gzip.decompress(b["body_gz"]).decode("utf-8"))
