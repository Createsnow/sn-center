from app.schemas.common import In
from app.services.transfers import TransferRequest


class TransferIn(In):
    """转单、转组织共用。来源：PI → 单据 →（物料 →（单枚或连续号段））；目标 PI、单据、生产组织、物料都必填。"""

    #: 来源生产组织（只用于转组织）
    factory_code: str | None = None
    pi_no: str | None = None
    bill_no: str | None = None
    #: BILL 整张单据 / MATERIAL 单据 + 物料 / RANGE 单据 + 物料 + 单枚或连续号段
    scope: str | None = None
    material_code: str | None = None
    start_sn: str | None = None
    #: 留空 = 单枚
    end_sn: str | None = None
    to_pi: str | None = None
    to_bill: str | None = None
    to_factory: str | None = None
    to_material: str | None = None
    #: 目标单据中同一物料有多行时指定序号
    to_seq: int | None = None
    reason: str | None = None

    def req(self) -> TransferRequest:
        return TransferRequest(**self.model_dump())
