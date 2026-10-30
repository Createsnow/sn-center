"""SN 编码：完整 SN = 前缀 + 流水 + 米数前分隔符 + 米数 + 米数后分隔符 + 单位 + 后缀（PRD 5.2.2）。

- 流水定长：左侧用字符表首字符（默认字符表即 0）补到流水位数；10 / 16 / 32 / 36 进制。
- 米数是字符串，取所属物料行的米数原样写入（只去首尾空格，000 / 001 / 0.5 / 其他都可以，不按数值规范化），
  不足米数补 0 位数时左侧补 0（超过不截断）；物料行米数为空时规则带米数不能生成（须先填写）。
  规则不带米数时米数和前后分隔符都不写。
- 单位取物料行的产品单位原样写入；规则不带单位时不写。
- 前缀、后缀、分隔符不限制字符（只去首尾空格）。前缀必填：一个前缀只归属一张 PI，同一 PI 内流水不重复，
  SN 就不重复（号池唯一约束兜底）。
- 流水紧跟前缀、定长，所以按版本可以把 SN 反解出十进制流水。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.core.errors import ErrorCode

#: 默认字符表（字母一律大写）；32 进制去掉易混的 I、L、O、U
DEFAULT_CHARSETS: dict[int, str] = {
    10: "0123456789",
    16: "0123456789ABCDEF",
    32: "0123456789ABCDEFGHJKMNPQRSTVWXYZ",
    36: "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
}
MAX_SEQ_LEN = 12
#: 米数补 0 位数上限（0 = 不补）
MAX_LENGTH_PAD = 32
#: 分隔符最多字符数
MAX_SEP = 16
SN_MAX = 64
LONG_MAX = 2**63 - 1
#: 字符表不能含空白
_CHARSET_RE = re.compile(r"^\S+$")
#: 中文（含中文标点、全角字符）：页面填写的米数、产品单位不能含这些字符
_CHINESE_RE = re.compile(r"[　-〿㐀-䶿一-鿿豈-﫿＀-￯]")


class SeqOverflow(ArithmeticError):
    """流水超出规则位数。"""


@dataclass(frozen=True)
class Spec:
    """规则版本的编码参数。charset 为完整字符表，长度 = 进制。"""

    prefix: str
    suffix: str
    base: int
    seq_len: int
    charset: str
    with_length: bool = True
    length_pad: int = 0
    sep_before: str = ""
    sep_after: str = ""
    with_unit: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "prefix", self.prefix or "")
        object.__setattr__(self, "suffix", self.suffix or "")
        object.__setattr__(self, "sep_before", self.sep_before or "")
        object.__setattr__(self, "sep_after", self.sep_after or "")
        object.__setattr__(self, "length_pad", self.length_pad or 0)


def validate(s: Spec) -> ErrorCode | None:
    """校验参数；返回 None 表示合法，否则返回错误码。米数、单位部分在生成时按实际值再校验总长。"""
    if s.base not in DEFAULT_CHARSETS:
        return ErrorCode.RULE_BASE_INVALID
    if s.seq_len < 1 or s.seq_len > MAX_SEQ_LEN:
        return ErrorCode.RULE_SEQ_LEN_INVALID
    if not s.prefix:
        return ErrorCode.RULE_PREFIX_REQUIRED
    if s.length_pad < 0 or s.length_pad > MAX_LENGTH_PAD:
        return ErrorCode.RULE_LENGTH_PAD_INVALID
    if len(s.sep_before) > MAX_SEP or len(s.sep_after) > MAX_SEP:
        return ErrorCode.RULE_AFFIX_INVALID
    if s.charset is None or len(s.charset) != s.base:
        return ErrorCode.RULE_CHARSET_LENGTH
    if not _CHARSET_RE.match(s.charset):
        return ErrorCode.RULE_CHARSET_INVALID
    if len(set(s.charset)) != len(s.charset):
        return ErrorCode.RULE_CHARSET_DUPLICATE
    if len(s.prefix) + len(s.suffix) + s.seq_len + _fixed_length_part(s) > SN_MAX:
        return ErrorCode.RULE_SN_TOO_LONG
    return None


def _fixed_length_part(s: Spec) -> int:
    """米数部分至少占的字符数（分隔符 + 补 0 位数）。"""
    return len(s.sep_before) + s.length_pad + len(s.sep_after) if s.with_length else 0


def max_seq(s: Spec) -> int:
    """该规则能表示的最大十进制流水（封顶在 long 上限）。"""
    v = s.base**s.seq_len
    return LONG_MAX if v - 1 > LONG_MAX else v - 1


def encode_seq(seq: int, s: Spec) -> str:
    """定长进制流水；超出位数抛 SeqOverflow。"""
    if seq < 0:
        raise ValueError("negative seq")
    digits = [""] * s.seq_len
    n = seq
    for i in range(s.seq_len - 1, -1, -1):
        digits[i] = s.charset[n % s.base]
        n //= s.base
    if n != 0:
        raise SeqOverflow(f"seq {seq} exceeds {s.seq_len} digits of base {s.base}")
    return "".join(digits)


def decode_seq(text: str, s: Spec) -> int | None:
    index = {c: i for i, c in enumerate(s.charset)}
    n = 0
    for c in text:
        d = index.get(c)
        if d is None:
            return None
        n = n * s.base + d
    return n


def pad_length(length: str | None, s: Spec) -> str:
    """米数按规则左侧补 0 到补 0 位数（超过不截断）。"""
    v = length or ""
    return v.rjust(s.length_pad, "0") if s.length_pad else v


def format_sn(seq: int, s: Spec, length: str | None = None, unit: str | None = None) -> str:
    """完整 SN；规则带米数时写入（补 0）米数及前后分隔符，带单位时写入单位，不带时忽略。"""
    mid = s.sep_before + pad_length(length, s) + s.sep_after if s.with_length else ""
    tail = (unit or "") if s.with_unit else ""
    return s.prefix + encode_seq(seq, s) + mid + tail + s.suffix


def parse(sn: str | None, s: Spec) -> int | None:
    """按规则反解完整 SN → 十进制流水；前缀、后缀、流水位数或字符、米数分隔符不符返回 None。"""
    if sn is None or not sn.startswith(s.prefix) or not sn.endswith(s.suffix):
        return None
    body = sn[len(s.prefix) : len(sn) - len(s.suffix)]
    if len(body) < s.seq_len:
        return None
    seq_text, rest = body[: s.seq_len], body[s.seq_len :]
    if s.with_length:
        if len(rest) < _fixed_length_part(s) or not rest.startswith(s.sep_before):
            return None
    elif not s.with_unit and rest:
        return None
    return decode_seq(seq_text, s)


def unit_of(sn: str | None, s: Spec, length: str | None = None) -> str | None:
    """从完整 SN 截出写入的产品单位（米数传号上记的未补 0 原值）；规则不带单位或号与规则对不上时返回 None。"""
    if not s.with_unit or sn is None or not sn.startswith(s.prefix) or not sn.endswith(s.suffix):
        return None
    mid = s.sep_before + pad_length(length, s) + s.sep_after if s.with_length else ""
    head = len(s.prefix) + s.seq_len + len(mid)
    end = len(sn) - len(s.suffix)
    if end < head or sn[len(s.prefix) + s.seq_len : head] != mid:
        return None
    return sn[head:end]


def clean_length(raw: str | None) -> str | None:
    """米数字符串：去首尾空格后原样使用；为空返回 None。"""
    v = (raw or "").strip()
    return v or None


def has_chinese(text: str | None) -> bool:
    """是否含中文（含中文标点、全角字符）。"""
    return bool(text) and _CHINESE_RE.search(text) is not None
