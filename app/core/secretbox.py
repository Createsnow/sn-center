"""对称加密：SN 调用 MES 的凭证、MES 接口账户的密钥加密保存（AES-GCM，密钥由 SN_SECRET 派生）。

更换 SN_SECRET 后旧凭证无法解密，须在 MES 对接配置中重新填写；MES 接口账户的密钥须重置后才能再查看。
"""

from __future__ import annotations

import base64
import hashlib
import secrets

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.config import settings

_PREFIX = "v1:"


def _key() -> bytes:
    return hashlib.sha256(("sn-mes-credential:" + settings.secret_key).encode("utf-8")).digest()


def seal(plain: str) -> str:
    if not plain:
        return ""
    nonce = secrets.token_bytes(12)
    ct = AESGCM(_key()).encrypt(nonce, plain.encode("utf-8"), None)
    return _PREFIX + base64.urlsafe_b64encode(nonce + ct).decode("ascii")


def open_(sealed: str) -> str | None:
    """解密；为空返回空串，解不开（密钥换过）返回 None。"""
    if not sealed:
        return ""
    if not sealed.startswith(_PREFIX):
        return None
    try:
        raw = base64.urlsafe_b64decode(sealed[len(_PREFIX) :].encode("ascii"))
        return AESGCM(_key()).decrypt(raw[:12], raw[12:], None).decode("utf-8")
    except (ValueError, InvalidTag):
        return None
