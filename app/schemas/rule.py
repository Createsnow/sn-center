from app.schemas.common import In


class RuleSpecIn(In):
    prefix: str | None = None
    suffix: str | None = None
    base: int | None = None
    seq_len: int | None = None
    charset: str | None = None


class RuleIn(RuleSpecIn):
    rule_code: str | None = None
    rule_name: str | None = None
    #: 不传为通用规则；规则库页不传，绑定在生成页做
    bind_scope: str | None = None
    bind_value: str | None = None
    #: 归类：NONE 未分类 / CUSTOMER / PI；不传时同绑定对象
    category_scope: str | None = None
    category_value: str | None = None


class RuleUpdateIn(RuleSpecIn):
    rule_name: str | None = None
    #: 不传则绑定不变；改成 GENERAL 即解绑
    bind_scope: str | None = None
    bind_value: str | None = None
    #: 不传则归类不变
    category_scope: str | None = None
    category_value: str | None = None


class RulePiBindIn(In):
    pi_no: str | None = None
    rule_id: int | None = None


class RulePreviewIn(RuleSpecIn):
    start: int | None = None
