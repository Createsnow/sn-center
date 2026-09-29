from app.schemas.common import In


class FactoryIn(In):
    factory_code: str | None = None
    factory_name: str | None = None
