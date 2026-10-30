"""SN 查询导出的列（导出时刻的当前值）。SN 分配页「已分配」页签的下载另有列，见 sn_export.FILE_HEADERS，
只复用这里的 FROM / COLS 取数。

生产组织列出组织编号与中文名称（金蝶组织机构的简体中文名，见 factories）；物料编码后紧跟附件项次、米数
（附件项次、米数都是生成时写到号上的值；规则不带米数时米数为空）。
"""

from __future__ import annotations

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
#: 号池 i 关联生产组织 f（取中文名称）
FROM = "sn_item i LEFT JOIN sn_factory f ON f.factory_code = i.factory_code"
COLS = (
    "i.id, i.sn, i.rule_version_id, i.seq_dec, i.seq_pi_no, i.factory_code, f.factory_name, i.customer_code, "
    "i.pi_no, i.bill_no, i.bill_seq, i.material_code, i.attach_no, i.length_text, i.status, i.delivery_mode, "
    "i.delivery_batch_no"
)


def row(no: int, r: dict) -> list:
    """一行，列顺序同 HEADERS。"""
    return [
        no,
        r["sn"],
        rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        str(r["seq_dec"]),
        r["factory_code"] or "",
        r["factory_name"] or "",
        r["customer_code"],
        r["pi_no"],
        r["bill_no"],
        r["bill_seq"],
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
