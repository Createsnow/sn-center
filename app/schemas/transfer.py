from app.schemas.common import In
from app.services.transfers import LineSpec, TransferRequest


class TransferLineIn(In):
    """按料号转移时选中的一个料号 + 附件项次（同级）及其转移方式。"""

    material_code: str | None = None
    #: 留空 = 该料号下全部附件项次
    attach_no: str | None = None
    #: ALL 全部 / RANGE 号段 / QTY 指定数量（留空按其余字段推断）
    mode: str | None = None
    start_sn: str | None = None
    #: 留空 = 单枚
    end_sn: str | None = None
    qty: int | None = None
    #: 目标单据中同料号、同附件项次有多行时指定序号
    to_seq: int | None = None


class TransferIn(In):
    """转单、转组织共用。来源：PI → 单据 →（整单 / 一个或多个料号 + 附件项次，各自全部、号段或指定数量）；
    目标 PI、单据、生产组织必填，物料行按同料号、同附件项次匹配（只有同料号、同附件项次才能转移）。"""

    #: 来源生产组织（只用于转组织）
    factory_code: str | None = None
    pi_no: str | None = None
    bill_no: str | None = None
    #: BILL 整单 / MATERIAL 按料号（见 lines）/ RANGE 单个料号下单枚或连续号段
    scope: str | None = None
    #: 只选一个料号时可不用 lines
    material_code: str | None = None
    attach_no: str | None = None
    start_sn: str | None = None
    #: 留空 = 单枚
    end_sn: str | None = None
    qty: int | None = None
    #: 按料号转移时选中的各料号；整单转移时只用来给各料号指定目标序号
    lines: list[TransferLineIn] | None = None
    to_pi: str | None = None
    to_bill: str | None = None
    to_factory: str | None = None
    #: 选填；填了须与来源料号相同
    to_material: str | None = None
    #: 目标单据中同料号、同附件项次有多行时指定序号
    to_seq: int | None = None
    reason: str | None = None

    def req(self) -> TransferRequest:
        d = self.model_dump(exclude={"lines"})
        lines = None if self.lines is None else tuple(LineSpec(**x.model_dump()) for x in self.lines)
        return TransferRequest(**d, lines=lines)
