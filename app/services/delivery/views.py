"""页面查询（F-18 交付批次）：批次列表、详情（回执存档、号段）、明细与导出。只读。"""

from __future__ import annotations

from collections.abc import Iterator

from app.core import utils as util
from app.core.security import CurrentUser
from app.db.session import db
from app.services import factories
from app.services.delivery.batches import BATCH_FIELDS, batch_json, must_batch


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
    rows = db.all(
        "SELECT seq_pi_no, MIN(seq_dec) s, MAX(seq_dec) e, COUNT(*) n, MIN(line_no) a, MAX(line_no) z FROM "
        "(SELECT seq_pi_no, seq_dec, line_no, seq_dec - ROW_NUMBER() OVER (PARTITION BY seq_pi_no ORDER BY seq_dec) g "
        "FROM sn_delivery_item WHERE batch_id = %s) t GROUP BY seq_pi_no, g ORDER BY seq_pi_no, s LIMIT 500",
        b["id"],
    )
    out = []
    for r in rows:
        first = db.scalar(
            "SELECT sn FROM sn_delivery_item WHERE batch_id = %s AND seq_pi_no = %s AND seq_dec = %s",
            b["id"],
            r["seq_pi_no"],
            r["s"],
        )
        last = db.scalar(
            "SELECT sn FROM sn_delivery_item WHERE batch_id = %s AND seq_pi_no = %s AND seq_dec = %s",
            b["id"],
            r["seq_pi_no"],
            r["e"],
        )
        out.append(
            {
                "seq_pi_no": r["seq_pi_no"],
                "start_seq": int(r["s"]),
                "end_seq": int(r["e"]),
                "qty": int(r["n"]),
                "start_sn": first,
                "end_sn": last,
            }
        )
    return out


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
