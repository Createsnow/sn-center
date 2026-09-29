import re
import uuid
from urllib.parse import parse_qsl, urlencode

import structlog
from starlette.types import ASGIApp, Receive, Scope, Send

# 失败审计需要读请求体：只缓存 /api 写请求且不超过 64KB
MAX_BODY = 64 * 1024
WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}


def camel_to_snake(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])([A-Z])", lambda m: "_" + m.group(1).lower(), name)


class RequestIdMiddleware:
    """请求号：沿用 X-Request-ID，没有就生成；写入日志上下文与响应头。

    查询参数也接受驼峰写法（billNo = bill_no）。
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope.get("headers", [])}
        request_id = headers.get("x-request-id") or str(uuid.uuid4())
        structlog.contextvars.bind_contextvars(request_id=request_id)
        state = scope.setdefault("state", {})
        state["request_id"] = request_id

        qs = scope.get("query_string", b"")
        if qs and re.search(rb"[a-z][A-Z]", qs):
            pairs = parse_qsl(qs.decode("latin-1"), keep_blank_values=True)
            names = {k for k, _ in pairs}
            extra = [(camel_to_snake(k), v) for k, v in pairs if camel_to_snake(k) not in names]
            scope["query_string"] = urlencode(pairs + extra).encode("latin-1")

        if scope.get("method", "") in WRITE_METHODS and scope.get("path", "").startswith("/api/"):
            body = b""
            more = True
            while more:
                msg = await receive()
                if msg["type"] != "http.request":
                    break
                body += msg.get("body", b"")
                more = msg.get("more_body", False)
            state["body"] = body if len(body) <= MAX_BODY else None
            replayed = False

            async def receive_replay() -> dict:
                nonlocal replayed
                if not replayed:
                    replayed = True
                    return {"type": "http.request", "body": body, "more_body": False}
                return await receive()

            receive = receive_replay

        async def send_with_id(message: dict) -> None:
            if message["type"] == "http.response.start":
                raw = list(message.get("headers", []))
                raw.append((b"x-request-id", request_id.encode("latin-1")))
                message = {**message, "headers": raw}
            await send(message)

        try:
            await self.app(scope, receive, send_with_id)
        finally:
            structlog.contextvars.unbind_contextvars("request_id")
