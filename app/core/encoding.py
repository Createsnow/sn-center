"""十进制流水 ⇄ 规则进制流水 ⇄ 完整 SN。

完整 SN = 前缀 + 定长进制流水（左侧用字符表首字符补齐）+ 后缀。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.core.errors import ErrorCode

DEFAULT_CHARSETS: dict[int, str] = {
    10: "0123456789",
    16: "0123456789ABCDEF",
    32: "0123456789ABCDEFGHIJKLMNOPQRSTUV",
    36: "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
}
MAX_SEQ_LEN = 12
LONG_MAX = 2**63 - 1


class SeqOverflow(ArithmeticError):
    """流水超出规则位数。"""


@dataclass(frozen=True)
class Spec:
    """规则编码参数。charset 为完整字符表，长度 = 进制。"""

    prefix: str
    suffix: str
    base: int
    seq_len: int
    charset: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "prefix", self.prefix or "")
        object.__setattr__(self, "suffix", self.suffix or "")


def validate(s: Spec) -> ErrorCode | None:
    """校验参数；返回 None 表示合法，否则返回错误码。"""
    if s.base not in DEFAULT_CHARSETS:
        return ErrorCode.RULE_BASE_INVALID
    if s.seq_len < 1 or s.seq_len > MAX_SEQ_LEN:
        return ErrorCode.RULE_SEQ_LEN_INVALID
    if s.charset is None or len(s.charset) != s.base:
        return ErrorCode.RULE_CHARSET_LENGTH
    if len(set(s.charset)) != len(s.charset):
        return ErrorCode.RULE_CHARSET_DUPLICATE
    if len(s.prefix) + len(s.suffix) + s.seq_len > 64:
        return ErrorCode.RULE_SN_TOO_LONG
    return None


def max_seq(s: Spec) -> int:
    """该规则能表示的最大十进制流水（与 Java 一样封顶在 long 上限）。"""
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


def format_sn(seq: int, s: Spec) -> str:
    return s.prefix + encode_seq(seq, s) + s.suffix


def parse(sn: str | None, s: Spec) -> int | None:
    """按规则反解完整 SN 的十进制流水；前后缀、位数或字符不符返回 None。"""
    if (
        sn is None
        or not sn.startswith(s.prefix)
        or not sn.endswith(s.suffix)
        or len(sn) < len(s.prefix) + len(s.suffix)
    ):
        return None
    body = sn[len(s.prefix) : len(sn) - len(s.suffix)]
    if len(body) != s.seq_len:
        return None
    index = {c: i for i, c in enumerate(s.charset)}
    n = 0
    for c in body:
        d = index.get(c)
        if d is None:
            return None
        n = n * s.base + d
    return n
