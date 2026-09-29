"""/api 鉴权依赖：Bearer 令牌 → 账户（停用即拒绝）→ 强制改密 → 角色。

当前账户挂到 request.state.user，全局 BizError 处理据此记失败痕迹。
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable, Iterator
from typing import Annotated, Any
from urllib.parse import quote

from fastapi import Depends, Request
from fastapi.responses import StreamingResponse

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import API, PAGE, CurrentUser, parse_token, pwd_version
from app.db.session import db


async def run_sync(fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
    """把同步 MySQL / PBKDF2 路径移出 event loop，不改业务函数本身。"""
    return await asyncio.to_thread(fn, *args, **kwargs)


#: 强制改密期间仍可调用的接口
PWD_CHANGE_ALLOWED = frozenset(("/api/auth/me", "/api/auth/password", "/api/auth/lang"))


def authenticate(request: Request) -> CurrentUser:
    header = request.headers.get("Authorization")
    token = None
    if header is not None and header[:7].lower() == "bearer ":
        token = header[7:].strip()
    if not token:
        raise biz(ErrorCode.UNAUTHENTICATED)
    claims = parse_token(token)
    user = db.one(
        "SELECT id, emp_no, name, role, factory_code, status, must_change_pwd, pwd_changed_at FROM sn_user "
        "WHERE id = %s",
        claims.user_id,
    )
    if user is None or pwd_version(user["pwd_changed_at"]) != claims.pwd_version:
        raise biz(ErrorCode.TOKEN_INVALID)
    if user["status"] != "ACTIVE":
        raise biz(ErrorCode.ACCOUNT_DISABLED)
    path = request.url.path
    source = API if path.startswith("/api/open/") else PAGE
    cu = CurrentUser(
        user["id"],
        user["emp_no"],
        user["name"],
        user["role"],
        user["factory_code"],
        source,
        getattr(request.state, "request_id", None),
    )
    request.state.user = cu
    if user["must_change_pwd"] and path not in PWD_CHANGE_ALLOWED:
        raise biz(ErrorCode.PASSWORD_CHANGE_REQUIRED)
    return cu


def current_user(request: Request) -> CurrentUser:
    """任何已登录账户。"""
    return authenticate(request)


def require_role(*roles: str) -> Callable[[Request], CurrentUser]:
    """接口允许的角色；未声明 = 任何已登录账户。"""
    allowed = frozenset(roles)

    def dep(request: Request) -> CurrentUser:
        cu = authenticate(request)
        if cu.role not in allowed:
            raise biz(ErrorCode.FORBIDDEN)
        return cu

    return dep


UserDep = Annotated[CurrentUser, Depends(current_user)]
AdminDep = Annotated[CurrentUser, Depends(require_role(util.ADMIN))]
FactoryDep = Annotated[CurrentUser, Depends(require_role(util.FACTORY))]
AdminOrFactoryDep = Annotated[CurrentUser, Depends(require_role(util.ADMIN, util.FACTORY))]
AdminOrQueryDep = Annotated[CurrentUser, Depends(require_role(util.ADMIN, util.QUERY))]


def page_of(page: int | None, size: int | None, default_size: int | None = None) -> util.Page:
    return util.Page.of(page, default_size if size is None and default_size is not None else size)


def download(base_name: str, fmt: str, content_type: str, body: Iterator[bytes]) -> StreamingResponse:
    """文件下载：流式写出，文件名按 RFC 5987 编码（中文不乱码）。"""
    name = f"{base_name}.{fmt}"
    return StreamingResponse(
        body,
        media_type=content_type,
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(name, safe='')}"},
    )
