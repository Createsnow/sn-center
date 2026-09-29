from app.schemas.common import In
from app.services.transfers import Request


class TransferIn(In):
    factory_code: str | None = None
    pi_no: str | None = None
    scope: str | None = None
    material_code: str | None = None
    sn: str | None = None
    to_factory: str | None = None
    to_customer: str | None = None
    to_pi: str | None = None
    to_material: str | None = None
    target_source: str | None = None
    reason: str | None = None

    def req(self) -> Request:
        return Request(**self.model_dump())


class NoteIn(In):
    note: str | None = None
