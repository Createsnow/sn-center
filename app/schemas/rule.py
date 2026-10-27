from app.schemas.common import In


class RuleSpecIn(In):
    prefix: str | None = None
    suffix: str | None = None
    base: int | None = None
    seq_len: int | None = None
    charset: str | None = None
    #: 带米数；米数补 0 位数（0 = 不补）与前后分隔符只在带米数时起作用
    with_length: bool | None = True
    length_pad: int | None = 0
    sep_before: str | None = ""
    sep_after: str | None = ""
    #: 带单位（物料行的产品单位）
    with_unit: bool | None = False


class RuleIn(RuleSpecIn):
    rule_code: str | None = None
    rule_name: str | None = None
    #: 分类：NONE 未分类 / CUSTOMER / PI（只用于筛选和推荐）
    category_scope: str | None = None
    category_value: str | None = None


class RuleUpdateIn(RuleSpecIn):
    rule_name: str | None = None
    #: 不传则分类不变
    category_scope: str | None = None
    category_value: str | None = None


class RulePreviewIn(RuleSpecIn):
    #: 示例米数（不填用 100）、示例单位（不填用 M）
    length: str | None = None
    unit: str | None = None


class LineRef(In):
    bill_no: str | None = None
    seq: int | None = None


class CustomRuleIn(In):
    """自定义规则：前缀必填（生成页默认取单据号后 7 位）；进制默认 10，流水位数默认 4，
    默认不带米数、不带单位，后缀选填。"""

    prefix: str | None = None
    base: int | None = None
    seq_len: int | None = None
    with_length: bool | None = False
    length_pad: int | None = 0
    sep_before: str | None = ""
    sep_after: str | None = ""
    with_unit: bool | None = False
    suffix: str | None = None


class LineSelectIn(In):
    pi_no: str | None = None
    lines: list[LineRef] | None = None
    #: 规则库规则（二选一）
    rule_id: int | None = None
    #: 自定义规则（二选一）
    custom: CustomRuleIn | None = None
    #: 选中的规则版本已用于别的 PI 时，为本 PI 填的新前缀
    new_prefix: str | None = None


class LineClearIn(In):
    pi_no: str | None = None
    lines: list[LineRef] | None = None


class PiBindIn(In):
    pi_no: str | None = None
    rule_id: int | None = None
    new_prefix: str | None = None


class CustomerTemplateIn(In):
    pi_no: str | None = None
    new_prefix: str | None = None


class CustomerBindIn(In):
    customer_code: str | None = None
    rule_id: int | None = None
    rule_code: str | None = None
