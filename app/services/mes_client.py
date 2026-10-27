"""本系统调 MES（接口 ①）：推送一个批次，按应答归类。

- HTTPS；默认发原始 JSON（UTF-8），组织开启「gzip 压缩」时发 gzip 并带 Content-Encoding: gzip；不跟随 3xx 跳转。
  同一批次每次送达请求体逐字节相同（建批时写定 body_gz，原始 JSON 由它解压得到）。
- 请求头：认证（A 固定 Bearer 令牌 + 来源 IP 白名单；B HMAC-SHA256 签名）；X-Sn-Attempt、X-Request-Id 只供日志；
  开启压缩时另带 Content-Encoding: gzip。
- HMAC 签名串 = timestamp + "\\n" + hex(SHA256(解压后的原始请求体字节))（请求体已含 batchNo），
  放在 X-Sn-Timestamp（UTC+8，2026-10-26 11:47:05.303）与 X-Sn-Signature（小写十六进制）。
- 应答：HTTP 200 + 回执 {batchNo, code = OK / NG, message, processedAt}（NG 为整批退回）；
  超时、5xx、断连、409 BATCH_IN_PROGRESS、429、503 可重试；
  409 BATCH_CONTENT_MISMATCH、401 / 403 / 404 / 413 / 400 等不重试。
"""

from __future__ import annotations

import gzip
import hashlib
import hmac
import json
import uuid
from dataclasses import dataclass
from typing import Any

import httpx

from app.core import secretbox
from app.core import utils as util

TOKEN = "TOKEN"
HMAC = "HMAC"
AUTH_TYPES = frozenset((TOKEN, HMAC))

#: 应答分类
RECEIPT = "RECEIPT"
RETRY = "RETRY"
FAIL = "FAIL"

#: 不重试的失败中，MES 确实没有处理的（可作废）
VOIDABLE_CODES = frozenset(("AUTH", "ADDRESS", "TOO_LARGE", "BAD_REQUEST", "REJECTED"))


@dataclass(frozen=True)
class Target:
    url: str
    auth_type: str
    credential: str
    timeout: int
    #: 请求体 gzip 压缩；默认发原始 JSON
    gzip: bool = False

    @staticmethod
    def of(factory: dict) -> Target:
        cred = secretbox.open_(factory["mes_credential"]) or ""
        return Target(
            factory["mes_url"],
            factory["mes_auth_type"],
            cred,
            max(1, int(factory["push_timeout_sec"])),
            bool(factory.get("mes_gzip")),
        )


@dataclass(frozen=True)
class Outcome:
    kind: str
    #: RECEIPT：MES 返回的回执（dict）
    receipt: dict | None = None
    #: FAIL / RETRY：错误码与说明
    code: str = ""
    message: str = ""
    http_status: int | None = None
    retry_after: int | None = None

    @property
    def voidable(self) -> bool:
        return self.kind == FAIL and self.code in VOIDABLE_CODES

    @property
    def config_problem(self) -> bool:
        """认证失败、地址错误：配置状态标为异常。"""
        return self.code in ("AUTH", "ADDRESS")


def gzip_bytes(raw: bytes) -> bytes:
    """固定 mtime：同一内容压缩结果逐字节相同。"""
    return gzip.compress(raw, compresslevel=6, mtime=0)


def canonical(obj: Any) -> bytes:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def sign(secret: str, timestamp: str, raw: bytes) -> str:
    msg = f"{timestamp}\n{hashlib.sha256(raw).hexdigest()}"
    return hmac.new(secret.encode("utf-8"), msg.encode("utf-8"), hashlib.sha256).hexdigest()


def _headers(t: Target, attempt: int, raw: bytes) -> dict[str, str]:
    h = {
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json",
        "Accept-Encoding": "gzip",
        "X-Sn-Attempt": str(attempt),
        "X-Request-Id": str(uuid.uuid4()),
    }
    if t.gzip:
        h["Content-Encoding"] = "gzip"
    if t.auth_type == HMAC:
        ts = util.mes_now()
        h["X-Sn-Timestamp"] = ts
        h["X-Sn-Signature"] = sign(t.credential, ts, raw)
    else:
        h["Authorization"] = f"Bearer {t.credential}"
    return h


def _retry_after(resp: httpx.Response) -> int | None:
    v = resp.headers.get("Retry-After")
    try:
        return max(0, int(v)) if v else None
    except ValueError:
        return None


def _json(resp: httpx.Response) -> Any:
    try:
        return json.loads(resp.content.decode("utf-8")) if resp.content else None
    except (ValueError, UnicodeDecodeError):
        return None


def send(t: Target, body_gz: bytes, attempt: int) -> Outcome:
    """推送一次；不抛异常，结果按应答归类。"""
    if not t.url:
        return Outcome(FAIL, code="ADDRESS", message="未配置 MES 接口地址")
    raw = gzip.decompress(body_gz)
    try:
        with httpx.Client(trust_env=False, follow_redirects=False, timeout=t.timeout) as http:
            content = body_gz if t.gzip else raw
            resp = http.post(t.url, content=content, headers=_headers(t, attempt, raw))
    except httpx.TimeoutException:
        return Outcome(RETRY, code="TIMEOUT", message=f"等待 MES 应答超时（{t.timeout} 秒）")
    except httpx.HTTPError as e:
        return Outcome(RETRY, code="NETWORK", message=f"连接中断：{type(e).__name__} {e}"[:500])
    body = _json(resp)
    code = body.get("code") if isinstance(body, dict) else None
    msg = body.get("message") if isinstance(body, dict) and isinstance(body.get("message"), str) else ""
    st = resp.status_code
    if st == 200:
        if isinstance(body, dict) and code in ("OK", "NG"):
            return Outcome(RECEIPT, receipt=body, http_status=st)
        return Outcome(FAIL, code="RESPONSE_INVALID", message="HTTP 200 但应答不是回执", http_status=st)
    if st == 409 and code == "BATCH_IN_PROGRESS":
        return Outcome(
            RETRY,
            code="IN_PROGRESS",
            message=msg or "同一批次正在 MES 处理",
            http_status=st,
            retry_after=_retry_after(resp),
        )
    if st == 409 and code == "BATCH_CONTENT_MISMATCH":
        return Outcome(FAIL, code="CONTENT_MISMATCH", message=msg or "MES 报同一批次号内容不同", http_status=st)
    if st == 429 or st == 503 or st >= 500:
        return Outcome(
            RETRY, code=f"HTTP_{st}", message=msg or f"HTTP {st}", http_status=st, retry_after=_retry_after(resp)
        )
    if st in (401, 403):
        return Outcome(FAIL, code="AUTH", message=msg or f"认证失败（HTTP {st}）", http_status=st)
    if st == 404:
        return Outcome(FAIL, code="ADDRESS", message=msg or "地址错误（HTTP 404）", http_status=st)
    if st == 413:
        return Outcome(FAIL, code="TOO_LARGE", message=msg or "报文过大（HTTP 413）", http_status=st)
    if st == 400:
        return Outcome(FAIL, code="BAD_REQUEST", message=msg or "MES 无法解析请求（HTTP 400）", http_status=st)
    return Outcome(FAIL, code=f"HTTP_{st}", message=msg or f"HTTP {st}", http_status=st)


def ping_body(org: str, batch_no: str) -> bytes:
    """连接测试批次：批次类型 PING、不含号。"""
    items: list = []
    obj = {
        "schemaVersion": "1.0",
        "batchNo": batch_no,
        "batchType": "PING",
        "orgCode": org,
        "toOrgCode": None,
        "count": 0,
        "createdAt": util.mes_now(),
        "itemsDigest": "sha256:" + hashlib.sha256(canonical(items)).hexdigest(),
        "items": items,
    }
    return gzip_bytes(canonical(obj))
