import re
import uuid
from urllib.parse import parse_qsl, urlencode

import structlog
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from app.core.errors import ErrorCode, biz
from app.core.exceptions import MesError

# 失败审计需要读请求体：只缓存 /api 写请求且不超过 64KB
MAX_BODY = 64 * 1024
#: /api 写请求体上限（与 frontend/nginx.conf 的 client_max_body_size 50m 一致），超出直接拒绝、不再往下读
MAX_REQUEST_BODY = 50 * 1024 * 1024
WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}
#: 沿用调用方请求号的条件：痕迹 trace_id 为 VARCHAR(64)，超长或含其他字符的另生成
_REQUEST_ID = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")


def request_id_of(raw: str | None) -> str:
    return raw if raw and _REQUEST_ID.fullmatch(raw) else str(uuid.uuid4())


async def _too_large(path: str, send: Send) -> None:
    """请求体超限：MES 接口按 MES 错误信封回 400 INVALID_REQUEST（沿用接口文档已有的错误码），页面接口回 413。"""
    max_mb = MAX_REQUEST_BODY // (1024 * 1024)
    if path.startswith("/api/mes/"):
        e = MesError(400, "INVALID_REQUEST", f"请求体超过 {max_mb} MB")
        resp = JSONResponse(e.body(), status_code=e.status_code)
    else:
        b = biz(ErrorCode.REQUEST_TOO_LARGE, max=max_mb)
        resp = JSONResponse({"code": b.code, "message": b.message, "params": b.params}, status_code=b.status_code)
    await resp({"type": "http"}, _no_receive, send)


async def _no_receive() -> dict:
    return {"type": "http.disconnect"}


def camel_to_snake(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])([A-Z])", lambda m: "_" + m.group(1).lower(), name)


class RequestIdMiddleware:
    """请求号：沿用 X-Request-ID（不超过 64 位的字母、数字、._:-），没有或不合格就生成；写入日志上下文与响应头。

    查询参数也接受驼峰写法（billNo = bill_no）。
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope.get("headers", [])}
        request_id = request_id_of(headers.get("x-request-id"))
        structlog.contextvars.bind_contextvars(request_id=request_id)
        state = scope.setdefault("state", {})
        state["request_id"] = request_id

        qs = scope.get("query_string", b"")
        if qs and re.search(rb"[a-z][A-Z]", qs):
            pairs = parse_qsl(qs.decode("latin-1"), keep_blank_values=True)
            names = {k for k, _ in pairs}
            extra = [(camel_to_snake(k), v) for k, v in pairs if camel_to_snake(k) not in names]
            scope["query_string"] = urlencode(pairs + extra).encode("latin-1")

        async def send_with_id(message: dict) -> None:
            if message["type"] == "http.response.start":
                raw = list(message.get("headers", []))
                raw.append((b"x-request-id", request_id.encode("latin-1")))
                message = {**message, "headers": raw}
            await send(message)

        path = scope.get("path", "")
        if scope.get("method", "") in WRITE_METHODS and path.startswith("/api/"):
            # 鉴权之前整包读入：先按 Content-Length、再按实际读到的字节数挡住超限请求（直连 8000 端口时前面没有 nginx）
            declared = headers.get("content-length", "")
            too_large = declared.isdigit() and int(declared) > MAX_REQUEST_BODY
            chunks: list[bytes] = []
            size = 0
            more = not too_large
            while more:
                msg = await receive()
                if msg["type"] != "http.request":
                    break
                chunk = msg.get("body", b"")
                size += len(chunk)
                if size > MAX_REQUEST_BODY:
                    too_large = True
                    break
                chunks.append(chunk)
                more = msg.get("more_body", False)
            if too_large:
                chunks.clear()
                try:
                    await _too_large(path, send_with_id)
                finally:
                    structlog.contextvars.unbind_contextvars("request_id")
                return
            body = b"".join(chunks)
            state["body"] = body if len(body) <= MAX_BODY else None
            replayed = False

            async def receive_replay() -> dict:
                nonlocal replayed
                if not replayed:
                    replayed = True
                    return {"type": "http.request", "body": body, "more_body": False}
                return await receive()

            receive = receive_replay

        try:
            await self.app(scope, receive, send_with_id)
        finally:
            structlog.contextvars.unbind_contextvars("request_id")
