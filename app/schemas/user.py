from app.schemas.common import In


class UserCreateIn(In):
    emp_no: str | None = None
    name: str | None = None
    #: admin / factory_operator / query / mes（MES 接口账户）
    role: str | None = None
    factory_code: str | None = None
    #: 查询员绑定的生产组织（可多个，空 = 全部）
    factory_codes: list[str | None] | None = None
    #: 页面账户的初始密码；MES 接口账户不填（系统生成密钥，只显示一次）
    password: str | None = None
    #: MES 接口账户的来源地址白名单（IP / CIDR，逗号分隔；空 = 不限）
    ip_whitelist: str | None = None


class UserUpdateIn(In):
    name: str | None = None
    role: str | None = None
    factory_code: str | None = None
    factory_codes: list[str | None] | None = None
    ip_whitelist: str | None = None


class UserPasswordIn(In):
    password: str | None = None
