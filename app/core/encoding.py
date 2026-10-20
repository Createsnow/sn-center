"""SN 编码：完整 SN = 前缀 + 长度 + 定长进制流水 + 后缀（PRD 5.2.2）。

- 流水左侧用字符表首字符补齐；10 / 16 / 32 / 36 进制。
- 长度是字符串，取所属物料行的长度原样写入（只去首尾空格，000 / 001 / 0.5 / 其他都可以，不按数值规范化）；
  物料行长度为空时写默认长度 000；规则不带长度时不写。
- 同一版本的前缀、流水位数、后缀固定，SN 总长减去这几部分就是长度位数，所以按版本可以把 SN 反解成（长度，流水）。
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
SN_MAX = 64
LONG_MAX = 2**63 - 1
#: 前缀：字母或数字；后缀：字母、数字、_
PREFIX_RE = re.compile(r"^[A-Za-z0-9]+$")
SUFFIX_RE = re.compile(r"^[A-Za-z0-9_]*$")
_CHARSET_RE = re.compile(r"^[A-Za-z0-9]+$")
#: 物料行长度为空（金蝶为空且页面未填写）时写入 SN 的默认长度
DEFAULT_LENGTH = "000"


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

    def __post_init__(self) -> None:
        object.__setattr__(self, "prefix", self.prefix or "")
        object.__setattr__(self, "suffix", self.suffix or "")


def validate(s: Spec) -> ErrorCode | None:
    """校验参数；返回 None 表示合法，否则返回错误码。长度部分在生成时按实际长度再校验总长。"""
    if s.base not in DEFAULT_CHARSETS:
        return ErrorCode.RULE_BASE_INVALID
    if s.seq_len < 1 or s.seq_len > MAX_SEQ_LEN:
        return ErrorCode.RULE_SEQ_LEN_INVALID
    if not s.prefix:
        return ErrorCode.RULE_PREFIX_REQUIRED
    if not PREFIX_RE.match(s.prefix) or not SUFFIX_RE.match(s.suffix):
        return ErrorCode.RULE_AFFIX_INVALID
    if s.charset is None or len(s.charset) != s.base:
        return ErrorCode.RULE_CHARSET_LENGTH
    if not _CHARSET_RE.match(s.charset):
        return ErrorCode.RULE_CHARSET_INVALID
    if len(set(s.charset)) != len(s.charset):
        return ErrorCode.RULE_CHARSET_DUPLICATE
    if len(s.prefix) + len(s.suffix) + s.seq_len > SN_MAX:
        return ErrorCode.RULE_SN_TOO_LONG
    return None


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


def format_sn(seq: int, s: Spec, length: str | None = None) -> str:
    """完整 SN；规则带长度时原样写入长度字符串，不带长度时忽略。"""
    mid = (length or "") if s.with_length else ""
    return s.prefix + mid + encode_seq(seq, s) + s.suffix


def parse(sn: str | None, s: Spec) -> tuple[str, int] | None:
    """按规则反解完整 SN →（长度，十进制流水）；前后缀、位数或字符不符返回 None。带长度的规则长度不能为空。"""
    if sn is None or not sn.startswith(s.prefix) or not sn.endswith(s.suffix):
        return None
    body = sn[len(s.prefix) : len(sn) - len(s.suffix)]
    if len(body) < s.seq_len:
        return None
    length, seq_text = body[: len(body) - s.seq_len], body[len(body) - s.seq_len :]
    if not s.with_length and length:
        return None
    if s.with_length and not length:
        return None
    n = decode_seq(seq_text, s)
    return None if n is None else (length, n)


def clean_length(raw: str | None) -> str | None:
    """长度字符串：去首尾空格后原样使用；为空返回 None（生成时取默认长度 000）。"""
    v = (raw or "").strip()
    return v or None
