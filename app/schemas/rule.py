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
    #: 规则只绑定 PI：category_scope 不填按 PI（填了只能是 PI），category_value = PI 号，必填
    category_scope: str | None = None
    category_value: str | None = None
    #: 所属生产组织：系统管理员不填 = 全局规则；普通操作员须是授权组织（只授权一个时可不填）
    factory_code: str | None = None


class RuleUpdateIn(RuleSpecIn):
    rule_name: str | None = None
    #: 都不传则分类与 PI 不变；传了只能是 PI，PI 必填
    category_scope: str | None = None
    category_value: str | None = None


class RulePreviewIn(RuleSpecIn):
    #: 示例米数（不填用 100）、示例单位（不填用 M）
    length: str | None = None
    unit: str | None = None
    #: 示例从这个流水（十进制）起（生成页自定义规则的起始号）；不填从 1 起
    seq_start: int | None = None
    #: 上限自动突破（生成页自定义规则的开关）：示例另带规则位数的最大号与突破后的第一个号；不传 = 关闭
    seq_grow: bool | None = None


class LineRef(In):
    bill_no: str | None = None
    seq: int | None = None


class CustomRuleIn(In):
    """自定义规则：前缀必填（生成页默认取单据号后 7 位）；进制默认 10，流水位数默认 4，
    默认不带米数、不带单位，后缀选填；起始号选填。"""

    prefix: str | None = None
    base: int | None = None
    seq_len: int | None = None
    with_length: bool | None = False
    length_pad: int | None = 0
    sep_before: str | None = ""
    sep_after: str | None = ""
    with_unit: bool | None = False
    suffix: str | None = None
    #: 起始号（十进制流水，选填）：首次用这条自定义规则生成、从 PI 最大号之后编号时，第一个号不小于它；
    #: 不是格式的一部分（同格式不同起始号仍是同一条规则），本 PI 已用过这个前缀时流水接续、不起作用
    seq_start: int | None = None
    #: 流水上限自动突破：开启时流水用完位数后自动加位（4 位用到 9999 后接 5 位 10000，以此类推，最多 12 位）；
    #: 不传或 false = 关闭（原逻辑：用完报规则位数已用尽）。不是格式的一部分，记在物料行上
    seq_grow: bool | None = None


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
