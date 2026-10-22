from app.schemas.common import In
from app.services.transfers import BillRequest, OrgRequest


class BillTransferIn(In):
    from_bill: str | None = None
    to_bill: str | None = None
    #: VOID 整单作废 / SPLIT 拆单
    mode: str | None = None
    mapping: dict[str, int] | None = None
    factory_code: str | None = None
    pi_no: str | None = None
    from_seq: int | None = None
    start_sn: str | None = None
    end_sn: str | None = None
    to_seq: int | None = None
    reason: str | None = None

    def req(self) -> BillRequest:
        return BillRequest(**self.model_dump())


class OrgTransferIn(In):
    factory_code: str | None = None
    pi_no: str | None = None
    #: PI 整张 / MATERIAL PI + 物料 / SINGLE 单枚 / RANGE 号段
    scope: str | None = None
    material_code: str | None = None
    sn: str | None = None
    start_sn: str | None = None
    end_sn: str | None = None
    to_factory: str | None = None
    to_pi: str | None = None
    to_customer: str | None = None
    to_material: str | None = None
    manual_fields: list[str] | None = None
    reason: str | None = None

    def req(self) -> OrgRequest:
        return OrgRequest(**self.model_dump())


class NoteIn(In):
    note: str | None = None
