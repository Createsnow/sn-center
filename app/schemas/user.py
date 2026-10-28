from app.schemas.common import In


class UserCreateIn(In):
    emp_no: str | None = None
    name: str | None = None
    #: operator 普通操作员 / auditor 审计员 / mes MES 接口账户（系统管理员只有一个，不能新建）
    role: str | None = None
    #: MES 接口账户的生产组织（普通操作员、审计员只授权一个组织时也可用它）
    factory_code: str | None = None
    #: 普通操作员、审计员授权的生产组织（可多个，至少一个）
    factory_codes: list[str | None] | None = None
    #: 页面账户的初始密码；MES 接口账户不填（系统生成密钥，加密保存，可查看、可重置）
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
