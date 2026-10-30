"""SN 导出（F-14）。

与分配后的推送、拉取是两套独立的机制：号一经分配到生产组织（不再是待分配）就按「生产组织 + PI + 单据」一行列出，
不论该组织是否对接 MES，也不看推送、拉取的进度和结果（推送失败、批次未确认都照常导出）；
明细逐枚列出全部号（不按号段合并），也可直接下载 xlsx（列见 FILE_HEADERS，与 SN 查询导出的列不同；
可按明细的物料编码、SN 起止号筛选只下载范围内的号）。
只读：导出不改号的状态、不建批次，可反复导出；导出也不影响推送、拉取。
"""

from __future__ import annotations

from collections.abc import Iterator

from app.core import encoding as codec
from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import factories, files, rules, sn_sheet

#: 各汇总都带的枚数：暂停（单据暂停交付）
_NOTES = "SUM(i.suspend IS NOT NULL) suspended"


def _where(cu: CurrentUser, factory: str | None) -> tuple[str, list]:
    """已分配 = 已分到生产组织（待分配的号还没有生产组织）；不看推送、拉取状态。"""
    fsql, args = factories.scope_sql(cu.read_factories(factory), "i.factory_code")
    return f" WHERE i.factory_code IS NOT NULL AND i.status <> '{util.PENDING_ALLOC}'" + fsql, args


def _notes(r: dict) -> dict:
    return {"suspended": int(r["suspended"] or 0)}


def bills(
    cu: CurrentUser, factory: str | None, q: str | None, mes: bool | None, page: util.Page, pi: str | None = None
) -> dict:
    """每个「生产组织 + PI + 单据」一行：枚数、暂停枚数、申请中枚数；q 按 PI 或单据编号前缀匹配，pi 按 PI 精确匹配
    （SN 分配页的「已分配单据」页签）。"""
    where, args = _where(cu, factory)
    if not util.blank(pi):
        where += " AND i.pi_no = %s"
        args.append(pi.strip())
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
        "SUBSTRING(MAX(CONCAT(LPAD(seq_dec, 20, '0'), sn)), 21) end_sn, COUNT(*) qty, MAX(suspend) suspend "
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
        }
        for r in rows
    ]
    return util.page_result(items, total, page)


def items(
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    bill: str | None,
    page: util.Page,
    bill_seq: int | None = None,
    material: str | None = None,
    q: str | None = None,
    sn_from: str | None = None,
    sn_to: str | None = None,
) -> dict:
    """明细：逐枚列出该单据的全部已分配号（不按号段合并），按单据序号、流水排序，与下载文件的行顺序一致。
    可只看一个物料行（bill_seq + material）或一个物料编码（只给 material），q 按完整 SN 模糊匹配；
    sn_from / sn_to 为 SN 起止号（含两端，可只给一端）：按号的流水（流水归属 PI + 十进制流水）取范围，
    起止号须是该单据已分配的号，否则没有结果。每枚带 PI、生产组织（编号与名称）、物料行、进制、流水位数、
    进制 / 十进制流水、附件项次、米数、产品单位、流水归属（转入号注明）、状态、交付方式、交付批次、暂停。"""
    p, _b, where, args = _bill_where(cu, factory, pi, bill)
    got = _filter(where, args, bill_seq, material, sn_from, sn_to)
    if got is None:
        return util.page_result([], 0, page)
    where, args = got
    if not util.blank(q):
        where += " AND i.sn LIKE %s"
        args.append(util.like_any(q.strip()))
    total = db.count("SELECT COUNT(*) FROM sn_item i" + where, *args)
    rows = db.all(
        "SELECT i.sn, i.rule_version_id, i.pi_no, i.factory_code, f.factory_name, i.bill_seq, i.material_code, "
        "i.attach_no, i.length_text, i.seq_pi_no, i.seq_dec, i.status, i.delivery_mode, i.delivery_batch_no, "
        "i.suspend FROM sn_item i LEFT JOIN sn_factory f ON f.factory_code = i.factory_code"
        + where
        + " ORDER BY i.bill_seq, i.seq_pi_no, i.seq_dec LIMIT %s, %s",
        *args,
        page.offset,
        page.size,
    )
    out = [
        {
            "line_no": page.offset + n + 1,
            "pi_no": r["pi_no"],
            "factory_code": r["factory_code"] or "",
            "factory_name": r["factory_name"] or "",
            "sn": _text(r["sn"]),
            "bill_seq": r["bill_seq"],
            "material_code": r["material_code"],
            "attach_no": _text(r["attach_no"]) or "",
            "length": _text(r["length_text"]) or "",
            "unit": _unit(r),
            "seq_pi_no": r["seq_pi_no"],
            "transferred_in": r["seq_pi_no"] != p,
            "base": _base(r["rule_version_id"]),
            "seq_len": _seq_len(r["rule_version_id"]),
            "seq_text": rules.seq_text(r["rule_version_id"], r["seq_dec"]),
            "seq_dec": int(r["seq_dec"]),
            "status": r["status"],
            "delivery_mode": r["delivery_mode"] or "",
            "delivery_batch_no": r["delivery_batch_no"] or "",
            "suspend": _text(r["suspend"]),
        }
        for n, r in enumerate(rows)
    ]
    return util.page_result(out, total, page)


def _filter(
    where: str,
    args: list,
    bill_seq: int | None,
    material: str | None,
    sn_from: str | None,
    sn_to: str | None,
) -> tuple[str, list] | None:
    """明细与下载共用的筛选：物料行（bill_seq + material）或物料编码、SN 起止号（含两端，可只给一端，按流水归属 PI +
    十进制流水取范围）。起止号不是该单据已分配的号时返回 None（没有结果）。"""
    args = list(args)
    if bill_seq is not None:
        where += " AND i.bill_seq = %s AND i.material_code = %s"
        args += [bill_seq, util.trim(material)]
    elif not util.blank(material):
        where += " AND i.material_code = %s"
        args.append(util.trim(material))
    for sn, op in ((sn_from, ">="), (sn_to, "<=")):
        if util.blank(sn):
            continue
        at = db.one("SELECT i.seq_pi_no, i.seq_dec FROM sn_item i" + where + " AND i.sn = %s", *args, sn.strip())
        if at is None:
            return None
        where += f" AND (i.seq_pi_no, i.seq_dec) {op} (%s, %s)"
        args += [at["seq_pi_no"], at["seq_dec"]]
    return where, args


def _seq_len(version_id: int | None) -> int | None:
    """号所用规则版本的流水位数。"""
    s = rules.cached_spec(version_id)
    return None if s is None else s.seq_len


def _unit(r: dict) -> str:
    """号上写入的产品单位（按规则版本从 SN 截出；规则不带单位时为空）。"""
    s = rules.cached_spec(r["rule_version_id"])
    return (None if s is None else codec.unit_of(_text(r["sn"]), s, _text(r["length_text"]))) or ""


def _base(version_id: int | None) -> int | None:
    """号所用规则版本的进制（10 / 16 / 32 / 36）。"""
    s = rules.cached_spec(version_id)
    return None if s is None else s.base


def _text(v):
    return v.decode("utf-8") if isinstance(v, (bytes, bytearray)) else v


#: 下载文件的列（与 SN 查询导出不同，只列分配页需要的字段）
FILE_HEADERS = [
    "组织编号",
    "生产组织",
    "PI",
    "物料编码",
    "SN",
    "流水位数",
    "十进制流水",
    "流水号",
    "附件项次",
    "米数",
    "产品单位",
]


def _file_row(r: dict) -> list:
    """一行，列顺序同 FILE_HEADERS。"""
    return [
        r["factory_code"] or "",
        r["factory_name"] or "",
        r["pi_no"],
        r["material_code"],
        _text(r["sn"]),
        _seq_len(r["rule_version_id"]),
        str(r["seq_dec"]),
        rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        _text(r["attach_no"]) or "",
        _text(r["length_text"]) or "",
        _unit(r),
    ]


def _file_rows(where: str, args: list) -> Iterator[list]:
    """按单据序号、流水分页读出（键集翻页，不用 OFFSET）。"""
    after: tuple | None = None
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
            yield _file_row(r)
        last = page[-1]
        after = (last["bill_seq"], last["seq_pi_no"], last["seq_dec"])


def file(
    cu: CurrentUser,
    factory: str | None,
    pi: str | None,
    bill: str | None,
    material: str | None = None,
    sn_from: str | None = None,
    sn_to: str | None = None,
) -> tuple[str, Iterator[bytes]]:
    """下载一行（生产组织 + PI + 单据）的已分配号；可按物料编码、SN 起止号（同明细的筛选）只下载范围内的号。
    不改状态，可反复下载。"""
    p, b, where, args = _bill_where(cu, factory, pi, bill)
    got = _filter(where, args, None, material, sn_from, sn_to)
    if got is None or not db.exists("SELECT EXISTS(SELECT 1 FROM sn_item i" + got[0] + ")", *got[1]):
        raise biz(ErrorCode.EXPORT_NOTHING, pi=p, bill_no=b)
    return f"SN_{p}_{b}", files.xlsx_bytes("SN", FILE_HEADERS, _file_rows(*got))
