"""sn_item 行 → 对外视图（附进制流水）；生成号段切分。"""

from __future__ import annotations

from dataclasses import asdict, dataclass

from app.core import encoding as codec
from app.core import utils as util
from app.services import rules

COLS = (
    "id, gen_month, sn, pi_no, customer_code, material_code, factory_code, bill_no, rule_version_id, seq_pi_no, "
    "seq_dec, status, source, batch_no, print_no, transfer_id, created_at, allocated_at, acquired_at, printed_at"
)


def view(r: dict) -> dict:
    """一枚 SN：完整 SN、进制流水、十进制流水同时给出。"""
    return {
        "id": r["id"],
        "gen_month": r["gen_month"],
        "sn": r["sn"],
        "seq_text": rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        "seq_dec": r["seq_dec"],
        "seq_pi_no": r["seq_pi_no"],
        "pi_no": r["pi_no"],
        "customer_code": r["customer_code"],
        "material_code": r["material_code"],
        "factory_code": r["factory_code"],
        "bill_no": r["bill_no"],
        "status": r["status"],
        "source": r["source"],
        "batch_no": r["batch_no"],
        "print_no": r["print_no"],
        "created_at": util.fmt(r["created_at"]),
        "allocated_at": util.fmt(r["allocated_at"]),
        "acquired_at": util.fmt(r["acquired_at"]),
        "printed_at": util.fmt(r["printed_at"]),
    }


FILE_HEADERS = [
    "序号",
    "完整SN",
    "进制流水",
    "十进制流水",
    "PI",
    "物料编码",
    "客户编码",
    "工厂",
    "状态",
    "领取批次",
    "来源订单",
]


def file_row(no: int, v: dict) -> list:
    return [
        no,
        v["sn"],
        v["seq_text"],
        v["seq_dec"],
        v["pi_no"],
        v["material_code"],
        v["customer_code"],
        v["factory_code"],
        v["status"],
        v["batch_no"],
        v["bill_no"],
    ]


# ---------------------------------------------------------------- 号段


def sn_qty(line: dict) -> int:
    """该物料行要准备的 SN 枚数：数量取整数部分。"""
    q = line.get("qty")
    return 0 if q is None else max(0, int(q))


@dataclass(frozen=True)
class Segment:
    """一段：对应一条物料行。"""

    line_seq: int
    material_code: str
    material_name: str
    qty: int
    start_seq: int
    end_seq: int
    start_sn: str
    end_sn: str

    def as_json(self) -> dict:
        return asdict(self)

    def as_camel(self) -> dict:
        """落库的 segments_json 与 Java 一致（Jackson 默认驼峰），两个后端的预演可互认。"""
        return {
            "lineSeq": self.line_seq,
            "materialCode": self.material_code,
            "materialName": self.material_name,
            "qty": self.qty,
            "startSeq": self.start_seq,
            "endSeq": self.end_seq,
            "startSn": self.start_sn,
            "endSn": self.end_sn,
        }


def split(
    lines: list[dict], already_generated: int, qty: int, start_seq: int, spec: codec.Spec | None
) -> list[Segment]:
    """把一次生成的连续流水按快照物料行顺序切段：每一行一段，物料编码为空的行物料为空。

    订单已生成 G 枚时，这些号按行顺序占满前面的行，本次从第 G 个位置接着切。
    """
    out: list[Segment] = []
    frm = already_generated
    to = already_generated + qty
    cum = 0
    for line in lines:
        line_start = cum
        line_end = cum + sn_qty(line)
        cum = line_end
        s = max(frm, line_start)
        e = min(to, line_end)
        if s >= e:
            continue
        seq_s = start_seq + (s - frm)
        seq_e = start_seq + (e - frm) - 1
        out.append(
            Segment(
                line["line_seq"],
                line.get("material_number") or "",
                line.get("material_name") or "",
                e - s,
                seq_s,
                seq_e,
                "" if spec is None else codec.format_sn(seq_s, spec),
                "" if spec is None else codec.format_sn(seq_e, spec),
            )
        )
    return out
