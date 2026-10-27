"""SN 导出（F-14）。

与分配后的推送、拉取是两套独立的机制：号一经分配到生产组织（不再是待分配）就按「生产组织 + PI + 单据」一行列出，
不论该组织是否对接 MES，也不看推送、拉取的进度和结果（推送失败、批次未确认都照常导出）；
明细逐枚列出全部号（不按号段合并），也可直接下载 xlsx（列与 SN 查询导出、历史领取文件统一，含组织编号与
中文生产组织名称，见 sn_sheet）。
只读：导出不改号的状态、不建批次，可反复导出；导出也不影响推送、拉取。历史领取批次在 SN 查询中查看。
"""

from __future__ import annotations

from collections.abc import Iterator

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import factories, files, sn_sheet

#: 各汇总都带的枚数：暂停（单据暂停交付）、申请中（转组织待审批，批准后会转走）
_NOTES = "SUM(i.suspend IS NOT NULL) suspended, SUM(i.status = 'APPLYING') applying"


def _where(cu: CurrentUser, factory: str | None) -> tuple[str, list]:
    """已分配 = 已分到生产组织（待分配的号还没有生产组织）；不看推送、拉取状态。"""
    fsql, args = factories.scope_sql(cu.read_factories(factory), "i.factory_code")
    return f" WHERE i.factory_code IS NOT NULL AND i.status <> '{util.PENDING_ALLOC}'" + fsql, args


def _notes(r: dict) -> dict:
    return {"suspended": int(r["suspended"] or 0), "applying": int(r["applying"] or 0)}


def bills(cu: CurrentUser, factory: str | None, q: str | None, mes: bool | None, page: util.Page) -> dict:
    """每个「生产组织 + PI + 单据」一行：枚数、暂停枚数、申请中枚数；q 按 PI 或单据编号前缀匹配。"""
    where, args = _where(cu, factory)
    if not util.blank(q):
        where += " AND (i.pi_no LIKE %s OR i.bill_no LIKE %s)"
        args += [util.like_prefix(q.strip())] * 2
    if mes is not None:
        where += " AND COALESCE(f.mes_enabled, 0) = %s"
        args.append(1 if mes else 0)
    grouped = (
        "SELECT i.pi_no, i.factory_code, i.bill_no, MAX(i.customer_code) customer_code, "
        "MAX(COALESCE(f.mes_enabled, 0)) mes, COUNT(*) qty, " + _NOTES + " "
        "FROM sn_item i LEFT JOIN sn_factory f ON f.factory_code = i.factory_code"
        + where
        + " GROUP BY i.pi_no, i.factory_code, i.bill_no"
    )
    total = db.count("SELECT COUNT(*) FROM (" + grouped + ") g", *args)
    rows = db.all(
        grouped + " ORDER BY i.pi_no DESC, i.factory_code, i.bill_no LIMIT %s, %s", *args, page.offset, page.size
    )
    items = [
        {
            "factory_code": r["factory_code"],
            "pi_no": r["pi_no"],
            "bill_no": r["bill_no"],
            "customer_code": r["customer_code"],
            "mes_enabled": bool(r["mes"]),
            "qty": int(r["qty"]),
            **_notes(r),
        }
        for r in rows
    ]
    return util.page_result(items, total, page)


def _key(pi: str | None, bill: str | None) -> tuple[str, str]:
    p, b = util.trim(pi), util.trim(bill)
    if not p:
        raise biz(ErrorCode.PI_REQUIRED)
    if not b:
        raise biz(ErrorCode.VALIDATION, detail="bill_no")
    return p, b


def _bill_where(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None) -> tuple[str, str, str, list]:
    """一张单据（生产组织 + PI + 单据）的已分配号。"""
    p, b = _key(pi, bill)
    where, args = _where(cu, factory)
    return p, b, where + " AND i.pi_no = %s AND i.bill_no = %s", [*args, p, b]


def lines(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None) -> list[dict]:
    """明细顶部：每个单据序号 + 物料一行，附件项次、米数、枚数、暂停枚数、申请中枚数。"""
    _p, _b, where, args = _bill_where(cu, factory, pi, bill)
    rows = db.all(
        "SELECT i.bill_seq, i.material_code, MAX(i.attach_no) attach_no, MAX(i.length_text) length_text, "
        "COUNT(*) qty, "
        + _NOTES
        + " FROM sn_item i"
        + where
        + " GROUP BY i.bill_seq, i.material_code ORDER BY i.bill_seq, i.material_code",
        *args,
    )
    return [
        {
            "bill_seq": r["bill_seq"],
            "material_code": r["material_code"],
            "attach_no": r["attach_no"] or "",
            "length": r["length_text"] or "",
            "qty": int(r["qty"]),
            **_notes(r),
        }
        for r in rows
    ]


def segments(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None, page: util.Page) -> dict:
    """明细：一行 = 单据序号 + 物料 + 连续号段（转入号注明流水归属 PI）；不按推送状态拆段。"""
    p, b, where, args = _bill_where(cu, factory, pi, bill)
    grouped = (
        "SELECT factory_code, bill_seq, material_code, MAX(attach_no) attach_no, MAX(length_text) length_text, "
        "seq_pi_no, MIN(seq_dec) s, MAX(seq_dec) e, "
        "SUBSTRING(MIN(CONCAT(LPAD(seq_dec, 20, '0'), sn)), 21) start_sn, "
        "SUBSTRING(MAX(CONCAT(LPAD(seq_dec, 20, '0'), sn)), 21) end_sn, COUNT(*) qty, MAX(suspend) suspend, "
        "SUM(status = 'APPLYING') applying "
        "FROM (SELECT i.factory_code, i.bill_seq, i.material_code, i.attach_no, i.length_text, i.status, i.seq_pi_no, "
        "i.seq_dec, i.sn, i.suspend, "
        "i.seq_dec - ROW_NUMBER() OVER (PARTITION BY i.factory_code, i.bill_seq, i.material_code, "
        "i.seq_pi_no ORDER BY i.seq_dec) grp FROM sn_item i"
        + where
        + ") t GROUP BY factory_code, bill_seq, material_code, seq_pi_no, grp"
    )
    total = db.count("SELECT COUNT(*) FROM (" + grouped + ") g", *args)
    rows = db.all(
        grouped + " ORDER BY bill_seq, seq_pi_no, s, factory_code LIMIT %s, %s", *args, page.offset, page.size
    )
    items = [
        {
            "factory_code": r["factory_code"],
            "pi_no": p,
            "bill_no": b,
            "bill_seq": r["bill_seq"],
            "material_code": r["material_code"],
            "attach_no": _text(r["attach_no"]) or "",
            "length": _text(r["length_text"]) or "",
            "seq_pi_no": r["seq_pi_no"],
            "transferred_in": r["seq_pi_no"] != p,
            "start_seq": int(r["s"]),
            "end_seq": int(r["e"]),
            "start_sn": _text(r["start_sn"]),
            "end_sn": _text(r["end_sn"]),
            "qty": int(r["qty"]),
            "suspend": _text(r["suspend"]),
            "applying": int(r["applying"] or 0),
        }
        for r in rows
    ]
    return util.page_result(items, total, page)


def items(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None, page: util.Page) -> dict:
    """明细：逐枚列出该单据的全部已分配号（不按号段合并），按单据序号、流水排序，与下载文件的行顺序一致；
    每枚带十进制流水、流水归属（转入号注明）、暂停、申请中。"""
    p, _b, where, args = _bill_where(cu, factory, pi, bill)
    total = db.count("SELECT COUNT(*) FROM sn_item i" + where, *args)
    rows = db.all(
        "SELECT i.sn, i.bill_seq, i.material_code, i.attach_no, i.length_text, i.seq_pi_no, i.seq_dec, i.status, "
        "i.suspend FROM sn_item i" + where + " ORDER BY i.bill_seq, i.seq_pi_no, i.seq_dec LIMIT %s, %s",
        *args,
        page.offset,
        page.size,
    )
    out = [
        {
            "line_no": page.offset + n + 1,
            "sn": _text(r["sn"]),
            "bill_seq": r["bill_seq"],
            "material_code": r["material_code"],
            "attach_no": _text(r["attach_no"]) or "",
            "length": _text(r["length_text"]) or "",
            "seq_pi_no": r["seq_pi_no"],
            "transferred_in": r["seq_pi_no"] != p,
            "seq_dec": int(r["seq_dec"]),
            "applying": r["status"] == "APPLYING",
            "suspend": _text(r["suspend"]),
        }
        for n, r in enumerate(rows)
    ]
    return util.page_result(out, total, page)


def _text(v):
    return v.decode("utf-8") if isinstance(v, (bytes, bytearray)) else v


def _file_rows(where: str, args: list) -> Iterator[list]:
    """按单据序号、流水分页读出（键集翻页，不用 OFFSET）。"""
    after: tuple | None = None
    no = 0
    while True:
        cond, extra = "", []
        if after is not None:
            cond = (
                " AND (i.bill_seq > %s OR (i.bill_seq = %s AND (i.seq_pi_no > %s "
                "OR (i.seq_pi_no = %s AND i.seq_dec > %s))))"
            )
            extra = [after[0], after[0], after[1], after[1], after[2]]
        page = db.all(
            f"SELECT {sn_sheet.COLS} FROM {sn_sheet.FROM}"
            + where
            + cond
            + " ORDER BY i.bill_seq, i.seq_pi_no, i.seq_dec LIMIT 5000",
            *args,
            *extra,
        )
        if not page:
            return
        for r in page:
            no += 1
            yield sn_sheet.row(no, r)
        last = page[-1]
        after = (last["bill_seq"], last["seq_pi_no"], last["seq_dec"])


def file(cu: CurrentUser, factory: str | None, pi: str | None, bill: str | None) -> tuple[str, Iterator[bytes]]:
    """下载一行（生产组织 + PI + 单据）的全部已分配号；不改状态，可反复下载。"""
    p, b, where, args = _bill_where(cu, factory, pi, bill)
    if not db.exists("SELECT EXISTS(SELECT 1 FROM sn_item i" + where + ")", *args):
        raise biz(ErrorCode.EXPORT_NOTHING, pi=p, bill_no=b)
    return f"SN_{p}_{b}", files.xlsx_bytes("SN", sn_sheet.HEADERS, _file_rows(where, args))
