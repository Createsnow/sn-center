"""交付批次的建立：批次号、报文（交付项 6.3、转出项 6.4）、写定请求体与明细；从「待推送、未入批、未暂停」号池认领。

批次类型（报文 batchType）按 MES 已有的数据说明这批号从哪来，一批只有一种：首次下发 NEW、同组织转移
SAME_ORG_TRANSFER、跨组织转入 TRANSFER_IN、订单变更改挂 REMAP（以上为交付类，MES 都逐枚按 rev 新增或覆盖），
以及转出 TRANSFER_OUT。转移、确认物料对应、告警重推时 MES 已持有的号不回号池，在同一事务内当场建批
（转移的批次上记转移单号）；号池认领也按类型分批，兜底暂停期间留在号池的号。

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
#: 批次类型（报文 batchType）
NEW = "NEW"
SAME_ORG_TRANSFER = "SAME_ORG_TRANSFER"
TRANSFER_IN = "TRANSFER_IN"
REMAP = "REMAP"
TRANSFER_OUT = "TRANSFER_OUT"
DELIVERY_TYPES = (NEW, SAME_ORG_TRANSFER, TRANSFER_IN, REMAP)
#: SQL 条件：交付类批次（批次表里除交付类只有转出批次；连接测试批次不入库）
IS_DELIVERY = "batch_type <> 'TRANSFER_OUT'"
M_PUSH = "PUSH_RESPONSE"
M_PULL = "PULL_RECEIPT"
M_MANUAL = "MANUAL"
XO_KEY = "#XO"
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
    """PS- / PL- / XO- + 组织 + yyyymmdd（UTC+8）+ 当天序号（≤ 40 位，全局唯一）。"""
    day = util.mes_local().strftime("%Y%m%d")
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


def item_type(r: dict, org: str) -> str:
    """号该进哪类交付批次，按 MES 已有的数据算：转移时原组织 MES 已持有的（transfer_from_org 只在这时记录）按前后
    组织分同组织转移 / 跨组织转入；未经转移的改挂（确认物料对应、告警重推）为 REMAP；
    其余（含 MES 还没收到就转移的）为首次下发。与 TYPE_SQL 一致。"""
    if r["transfer_from_org"]:
        return SAME_ORG_TRANSFER if r["transfer_from_org"] == org else TRANSFER_IN
    return REMAP if r["push_type"] == util.PUSH_REASSIGN else NEW


#: 号池中号的批次类型（与 item_type 一致；号池按 factory_code 认领，即批次的组织）
TYPE_SQL = (
    "(CASE WHEN transfer_from_org IS NOT NULL THEN IF(transfer_from_org = factory_code, 'SAME_ORG_TRANSFER', "
    "'TRANSFER_IN') WHEN push_type = 'REASSIGN' THEN 'REMAP' ELSE 'NEW' END)"
)


def _delivery_payload(r: dict) -> dict:
    """交付项（6.3）。十进制流水、米标按字符串；物料为空、规则不带米标时为 null。MES 逐枚按 rev 新增或覆盖。"""
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
        "createdAt": util.mes_fmt(now),
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


#: 号池：待推送、未入批、未暂停（按组织 + PI）
_POOL = (
    "pi_no = %s AND factory_code = %s AND status = 'TO_PUSH' AND suspend IS NULL AND delivery_batch_no IS NULL "
    "AND acquire_batch_no IS NULL"
)


def claim(org: str, pi: str, limit: int, bill: str | None = None, btype: str | None = None) -> list[dict]:
    """从号池按流水顺序认领（当前事务内加锁，已被别处锁住的跳过）；btype 只认领该类型的号。"""
    sql = f"SELECT {_CLAIM_COLS} FROM sn_item WHERE {_POOL}"
    args: list = [pi, org]
    if bill:
        sql += " AND bill_no = %s"
        args.append(bill)
    if btype:
        sql += f" AND {TYPE_SQL} = %s"
        args.append(btype)
    sql += " ORDER BY seq_pi_no, seq_dec LIMIT %s FOR UPDATE SKIP LOCKED"
    return db.all(sql, *args, limit)


def pool_types(org: str, pi: str, bill: str | None = None) -> dict[str, int]:
    """号池中各类型的枚数，按各类型最早的号排序。"""
    sql = f"SELECT {TYPE_SQL} t, COUNT(*) n, MIN(seq_pi_no) p, MIN(seq_dec) d FROM sn_item WHERE {_POOL}"
    args: list = [pi, org]
    if bill:
        sql += " AND bill_no = %s"
        args.append(bill)
    return {r["t"]: int(r["n"]) for r in db.all(sql + " GROUP BY t ORDER BY p, d", *args)}


def _deliver(
    cu: CurrentUser,
    org: str,
    pi: str,
    source: str,
    btype: str,
    items: list[dict],
    bill: str | None = None,
    transfer: tuple[str | None, str | None] = (None, None),
    rebuilt_from: str | None = None,
) -> dict:
    """把已加锁的同一类型的号写成一个交付批次，号记上批次号。"""
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
    b = _insert_batch(
        cu, "PS" if source == SRC_PUSH else "PL", source, btype, org, pi, payload, rows, transfer, rebuilt_from
    )
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
        .transfer(transfer[1])
        .status(util.TO_PUSH, util.TO_PUSH)
        .info(f"source={source} type={btype}" + (f" rebuilt_from={rebuilt_from}" if rebuilt_from else "")),
    )
    return b


def build_delivery(cu: CurrentUser, org: str, pi: str, source: str, bill: str | None = None) -> dict | None:
    """从号池认领并建立一个交付批次（短事务）：取最早的号所属的类型，只认领该类型；没有可认领的号返回 None。"""
    f = factories.must_get(org)
    size = max(1, int(f["batch_size"]))
    with db.tx():
        for btype in pool_types(org, pi, bill):
            items = claim(org, pi, size, bill, btype)
            if items:
                return _deliver(cu, org, pi, source, btype, items, bill)
        return None


def build_delivery_now(
    cu: CurrentUser,
    org: str,
    pi: str,
    ids: list[int],
    transfer: tuple[str | None, str | None] = (None, None),
    rebuilt_from: str | None = None,
) -> list[dict]:
    """当场建批（在转移、确认物料对应、告警重推、作废重建的事务内调用）：MES 已持有、改挂后待推送的号不回号池等认领。
    按类型分批，按组织当前单批枚数拆分；暂停交付的号留在号池，解除后照常认领。"""
    items: list[dict] = []
    for i in range(0, len(ids), 1000):
        part = ids[i : i + 1000]
        items += db.all(
            f"SELECT {_CLAIM_COLS} FROM sn_item WHERE id IN ({marks(len(part))}) AND {_POOL} FOR UPDATE",
            *part,
            pi,
            org,
        )
    items.sort(key=lambda r: (r["seq_pi_no"], r["seq_dec"]))
    f = factories.get(org)
    size = max(1, int(f["batch_size"])) if f else 5000
    by_type: dict[str, list[dict]] = {}
    for r in items:
        by_type.setdefault(item_type(r, org), []).append(r)
    out = []
    for btype, group in by_type.items():
        for i in range(0, len(group), size):
            out.append(_deliver(cu, org, pi, SRC_PUSH, btype, group[i : i + size], None, transfer, rebuilt_from))
    return out


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
