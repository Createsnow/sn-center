from app.schemas.common import In


class UserCreateIn(In):
    emp_no: str | None = None
    name: str | None = None
    role: str | None = None
    factory_code: str | None = None
    password: str | None = None


class UserUpdateIn(In):
    name: str | None = None
    role: str | None = None
    factory_code: str | None = None


class UserPasswordIn(In):
    password: str | None = None
