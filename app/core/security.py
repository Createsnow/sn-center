"""口令哈希、登录令牌与当前操作人。

口令哈希与令牌格式与 Java 后端完全一致：两个后端签发的令牌可以互认（同一 SN_SECRET）。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from dataclasses import dataclass
from datetime import datetime

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz

# ---------------------------------------------------------------- 口令

ROUNDS = 120_000


def _pbkdf2(password: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, ROUNDS, 32)


def hash_password(password: str) -> str:
    """PBKDF2-SHA256，存 salt(hex 32) + dk(hex 64)。"""
    salt = secrets.token_bytes(16)
    return salt.hex() + _pbkdf2(password, salt).hex()


def verify_password(password: str | None, stored: str | None) -> bool:
    if stored is None or len(stored) < 34 or password is None:
        return False
    try:
        salt = bytes.fromhex(stored[:32])
    except ValueError:
        return False
    return hmac.compare_digest(stored[32:].encode("ascii", "replace"), _pbkdf2(password, salt).hex().encode())


def check_policy(password: str | None) -> None:
    """8–64 位，同时含字母与数字。"""
    if (
        password is None
        or len(password) < 8
        or len(password) > 64
        or not any(ch.isalpha() for ch in password)
        or not any(ch.isdigit() for ch in password)
    ):
        raise biz(ErrorCode.USER_PASSWORD_WEAK)


# ---------------------------------------------------------------- 令牌


def pwd_version(pwd_changed_at: datetime | None) -> int:
    """口令版本 = 改密时间按 UTC 解释的秒数（与 Java toEpochSecond(UTC) 相同）。"""
    if pwd_changed_at is None:
        return 0
    return int((pwd_changed_at - datetime(1970, 1, 1)).total_seconds())


def _sign(raw: str) -> str:
    return hmac.new(settings.secret_key.encode("utf-8"), raw.encode("ascii"), hashlib.sha256).hexdigest()


def issue_token(user_id: int, pv: int) -> str:
    """base64url(JSON{uid, pv, exp}) + "." + HMAC-SHA256。"""
    body = {"uid": user_id, "pv": pv, "exp": int(time.time()) + settings.access_token_ttl_seconds}
    raw = base64.urlsafe_b64encode(json.dumps(body, separators=(",", ":")).encode()).decode().rstrip("=")
    return raw + "." + _sign(raw)


@dataclass(frozen=True)
class Claims:
    user_id: int
    pwd_version: int
    exp: int


def parse_token(token: str) -> Claims:
    raw, dot, sig = token.partition(".")
    if not dot:
        raise biz(ErrorCode.TOKEN_INVALID)
    try:
        ok = hmac.compare_digest(_sign(raw).encode(), sig.encode("ascii"))
    except (UnicodeEncodeError, UnicodeDecodeError):
        ok = False
    if not ok:
        raise biz(ErrorCode.TOKEN_INVALID)
    try:
        m = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
        c = Claims(int(m["uid"]), int(m["pv"]), int(m["exp"]))
    except (ValueError, KeyError, TypeError):
        raise biz(ErrorCode.TOKEN_INVALID) from None
    if c.exp < int(time.time()):
        raise biz(ErrorCode.TOKEN_INVALID)
    return c


# ---------------------------------------------------------------- 当前操作人

PAGE = "PAGE"
API = "API"
SYSTEM = "SYSTEM"


@dataclass(frozen=True)
class CurrentUser:
    """当前操作人。source = PAGE（页面）/ API（对外接口 /api/open）/ SYSTEM（定时任务）。"""

    id: int | None
    emp_no: str
    name: str
    role: str
    factory_code: str | None
    source: str
    trace_id: str | None

    @staticmethod
    def system(trace_id: str | None = None) -> CurrentUser:
        return CurrentUser(None, "system", "系统", util.ADMIN, None, SYSTEM, trace_id)

    @property
    def is_admin(self) -> bool:
        return self.role == util.ADMIN

    @property
    def is_factory(self) -> bool:
        return self.role == util.FACTORY

    @property
    def is_query(self) -> bool:
        return self.role == util.QUERY

    @property
    def bound(self) -> bool:
        return not util.blank(self.factory_code)

    def write_factory(self, requested: str | None) -> str:
        """写操作（领取、打印、转厂申请）的工厂：总部必须指定工厂；厂区只能是本厂；查询员不能写。"""
        req = util.trim(requested)
        if self.is_admin:
            if not req:
                raise biz(ErrorCode.FACTORY_NOT_FOUND, factory="")
            return req
        if self.is_factory:
            if req and req != self.factory_code:
                raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=self.factory_code)
            return self.factory_code  # type: ignore[return-value]
        raise biz(ErrorCode.FORBIDDEN)

    def read_factory(self, requested: str | None) -> str | None:
        """读范围的工厂：总部 / 未绑厂查询员可看任意厂（空 = 全部）；绑厂账户固定本厂。"""
        req = util.trim(requested)
        if not self.bound:
            return req or None
        if req and req != self.factory_code:
            raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=self.factory_code)
        return self.factory_code
