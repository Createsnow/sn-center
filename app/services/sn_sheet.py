"""SN 查询导出的列（导出时刻的当前值）。SN 分配页「已分配」页签的下载另有列，见 sn_export.FILE_HEADERS，
只复用这里的 FROM / COLS 取数。

生产组织列出组织编号与中文名称（金蝶组织机构的简体中文名，见 factories；
SN 查询导出中待分配的号取所在单据的组织）；物料编码后紧跟附件项次、米数
（附件项次、米数都是生成时写到号上的值；规则不带米数时米数为空）。
"""

from __future__ import annotations

from app.core import utils as util
from app.services import rules

HEADERS = [
    "行号",
    "完整SN",
    "进制流水",
    "十进制流水",
    "组织编号",
    "生产组织名称",
    "客户编码",
    "PI",
    "单据编号",
    "单据序号",
    "物料编码",
    "附件项次",
    "米数",
    "状态",
    "交付方式",
    "交付批次",
]
#: 与 HEADERS 一一对应的字段名（页面明细用）
KEYS = (
    "line_no",
    "sn",
    "seq_text",
    "seq_dec",
    "factory_code",
    "factory_name",
    "customer_code",
    "pi_no",
    "bill_no",
    "bill_seq",
    "material_code",
    "attach_no",
    "length",
    "status",
    "delivery_mode",
    "delivery_batch_no",
)
_COLS = (
    "i.id, i.sn, i.rule_version_id, i.seq_dec, i.seq_pi_no, {org} factory_code, {name} factory_name, "
    "i.customer_code, i.pi_no, i.bill_no, i.bill_seq, i.material_code, i.attach_no, i.length_text, i.status, "
    "i.delivery_mode, i.delivery_batch_no"
)
#: 号池 i 关联生产组织 f（取中文名称）；生产组织取号上的（分配后才有）
FROM = "sn_item i LEFT JOIN sn_factory f ON f.factory_code = i.factory_code"
COLS = _COLS.format(org="i.factory_code", name="f.factory_name")
#: SN 查询导出：待分配的号还没有生产组织（号上为空），取所在单据的组织（已出号单据台账 bl，
#: 与可看范围 factories.item_scope_sql 同口径）；已分配的号仍取号上的组织
QUERY_FROM = (
    "sn_item i LEFT JOIN sn_bill_ledger bl ON i.factory_code IS NULL AND bl.bill_no = i.bill_no "
    "LEFT JOIN sn_factory f ON f.factory_code = COALESCE(i.factory_code, bl.org_code)"
)
QUERY_COLS = _COLS.format(
    org="COALESCE(i.factory_code, NULLIF(bl.org_code, ''))", name="COALESCE(f.factory_name, NULLIF(bl.org_name, ''))"
)


def row(no: int, r: dict) -> list:
    """一行，列顺序同 HEADERS。SN 导入的号（带 r["import"]，见 sn_import.with_details）的流水与单据序号取导入记录。"""
    imp = r.get("import")
    return [
        no,
        r["sn"],
        imp["serial_text"] if imp else rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        str(imp["seq_dec"]) if imp else util.seq_dec_text(r["seq_pi_no"], r["seq_dec"]),
        r["factory_code"] or "",
        r["factory_name"] or "",
        r["customer_code"],
        r["pi_no"],
        r["bill_no"],
        int(imp["cur_bill_seq"]) if imp else r["bill_seq"],
        r["material_code"],
        r["attach_no"] or "",
        r["length_text"] or "",
        r["status"],
        r["delivery_mode"] or "",
        r["delivery_batch_no"] or "",
    ]


def view(no: int, r: dict) -> dict:
    """页面明细的一行：字段同 KEYS。"""
    return dict(zip(KEYS, row(no, r), strict=True))
