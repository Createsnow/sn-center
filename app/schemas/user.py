from app.schemas.common import In


class UserCreateIn(In):
    emp_no: str | None = None
    name: str | None = None
    role: str | None = None
    factory_code: str | None = None
    #: 查询员绑定的工厂（可多个，空 = 全部）；不传时按 factory_code 绑一个
    factory_codes: list[str | None] | None = None
    password: str | None = None


class UserUpdateIn(In):
    name: str | None = None
    role: str | None = None
    factory_code: str | None = None
    factory_codes: list[str | None] | None = None


class UserPasswordIn(In):
    password: str | None = None
