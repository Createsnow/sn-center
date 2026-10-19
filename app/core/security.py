"""口令哈希、登录令牌与当前操作人。

令牌带口令版本：改密 / 重置后旧令牌失效。页面登录的令牌另带登录版本：同一账户在页面上再次登录后，
之前页面上的令牌失效（一个账户同一时刻只在一个页面有效）；MES 等对接方登录的令牌不带登录版本，不受影响。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
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


_PWD = re.compile(r"^[A-Za-z0-9!@#$&*]{8,64}$")


def check_policy(password: str | None) -> None:
    """8–64 位，须同时含字母和数字；只能用字母、数字和 ! @ # $ & *（不能含空格、中文等）。"""
    if (
        password is None
        or not _PWD.match(password)
        or not any(ch.isascii() and ch.isalpha() for ch in password)
        or not any(ch.isdigit() for ch in password)
    ):
        raise biz(ErrorCode.USER_PASSWORD_WEAK)


# ---------------------------------------------------------------- 令牌


def pwd_version(pwd_changed_at: datetime | None) -> int:
    """口令版本 = 改密时间按 UTC 解释的秒数。"""
    if pwd_changed_at is None:
        return 0
    return int((pwd_changed_at - datetime(1970, 1, 1)).total_seconds())


def session_version(last_login_at: datetime | None) -> int:
    """登录版本 = 最近一次登录时间的秒数；每次登录都会变（同一秒内再登录顺延一秒）。"""
    return pwd_version(last_login_at)


def _sign(raw: str) -> str:
    return hmac.new(settings.secret_key.encode("utf-8"), raw.encode("ascii"), hashlib.sha256).hexdigest()


def issue_token(user_id: int, pv: int, sv: int | None = None) -> str:
    """base64url(JSON{uid, pv[, sv], exp}) + "." + HMAC-SHA256；sv 只有页面登录的令牌才有。"""
    body: dict[str, int] = {"uid": user_id, "pv": pv}
    if sv is not None:
        body["sv"] = sv
    body["exp"] = int(time.time()) + settings.access_token_ttl_seconds
    raw = base64.urlsafe_b64encode(json.dumps(body, separators=(",", ":")).encode()).decode().rstrip("=")
    return raw + "." + _sign(raw)


@dataclass(frozen=True)
class Claims:
    user_id: int
    pwd_version: int
    exp: int
    #: 页面登录的令牌才有；None = 对接方令牌，不做单页面校验
    session_version: int | None = None


def is_expired(token: str) -> bool:
    """签名正确但已过期（MES 接口区分 TOKEN_EXPIRED 与 TOKEN_INVALID）。"""
    raw, dot, sig = token.partition(".")
    try:
        if not dot or not hmac.compare_digest(_sign(raw).encode(), sig.encode("ascii")):
            return False
        m = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
        return int(m["exp"]) < int(time.time())
    except (ValueError, KeyError, TypeError, UnicodeEncodeError, UnicodeDecodeError):
        return False


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
        sv = m.get("sv")
        c = Claims(int(m["uid"]), int(m["pv"]), int(m["exp"]), None if sv is None else int(sv))
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
    """当前操作人。source = PAGE（页面）/ API（对外接口 /api/mes）/ SYSTEM（定时任务）。"""

    id: int | None
    emp_no: str
    name: str
    role: str
    factory_code: str | None
    source: str
    trace_id: str | None
    #: 可读的生产组织：操作员、MES 接口账户 = 本组织；查询员 = 绑定的一个或多个；空 = 全部（总部、未绑的查询员）
    factories: tuple[str, ...] = ()

    @staticmethod
    def system(trace_id: str | None = None) -> CurrentUser:
        return CurrentUser(None, "system", "系统", util.ADMIN, None, SYSTEM, trace_id)

    @property
    def is_admin(self) -> bool:
        return self.role == util.ADMIN

    @property
    def is_super_admin(self) -> bool:
        """内置 admin 账户：唯一可以管理账户的人；其他系统管理员不能建账户。"""
        return self.source != SYSTEM and util.is_super_admin(self.emp_no, self.role)

    @property
    def is_factory(self) -> bool:
        return self.role == util.FACTORY

    @property
    def is_query(self) -> bool:
        return self.role == util.QUERY

    @property
    def is_mes(self) -> bool:
        return self.role == util.MES

    @property
    def bound(self) -> bool:
        return bool(self.factories)

    def scope_text(self) -> str:
        return "、".join(self.factories)

    def can_read(self, factory: str | None) -> bool:
        return not self.factories or factory in self.factories

    def check_read(self, *factories: str | None) -> None:
        """记录涉及的工厂里至少有一个在可读范围内，否则拒绝。"""
        if not any(self.can_read(f) for f in factories):
            raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=self.scope_text())

    def write_factory(self, requested: str | None) -> str:
        """写操作（领取、转组织申请）的生产组织：总部必须指定；操作员只能是本组织；查询员不能写。"""
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

    def read_factories(self, requested: str | None) -> tuple[str, ...] | None:
        """读范围的工厂：None = 全部；指定了工厂须在可读范围内，没指定 = 全部可读的厂。"""
        req = util.trim(requested)
        if req:
            self.check_read(req)
            return (req,)
        return self.factories or None
