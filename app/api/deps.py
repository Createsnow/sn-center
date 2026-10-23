"""/api 鉴权依赖：Bearer 令牌 → 账户（改密或被重置即拒绝、停用即拒绝；页面令牌在别的页面重新登录即拒绝）→ 角色。

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
from app.core.exceptions import BizError, MesError
from app.core.security import API, PAGE, CurrentUser, is_expired, parse_token, pwd_version, session_version
from app.db.session import db
from app.services import users


async def run_sync(fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
    """把同步 MySQL / PBKDF2 路径移出 event loop，不改业务函数本身。"""
    return await asyncio.to_thread(fn, *args, **kwargs)


def authenticate(request: Request) -> CurrentUser:
    header = request.headers.get("Authorization")
    token = None
    if header is not None and header[:7].lower() == "bearer ":
        token = header[7:].strip()
    if not token:
        raise biz(ErrorCode.UNAUTHENTICATED)
    claims = parse_token(token)
    user = db.one(
        "SELECT id, emp_no, name, role, factory_code, status, pwd_changed_at, last_login_at "
        "FROM sn_user WHERE id = %s",
        claims.user_id,
    )
    if user is None:
        raise biz(ErrorCode.TOKEN_INVALID)
    if pwd_version(user["pwd_changed_at"]) != claims.pwd_version:
        # 本人改密或被重置密码：所有页面都要用新密码重新登录
        raise biz(ErrorCode.PASSWORD_CHANGED)
    if user["status"] != "ACTIVE":
        raise biz(ErrorCode.ACCOUNT_DISABLED)
    if claims.session_version is not None and session_version(user["last_login_at"]) != claims.session_version:
        raise biz(ErrorCode.SESSION_REPLACED)
    if user["role"] == util.MES:
        # MES 接口账户只能调 /api/mes/v1
        raise biz(ErrorCode.FORBIDDEN)
    path = request.url.path
    source = API if path.startswith("/api/mes/") or claims.session_version is None else PAGE
    cu = CurrentUser(
        user["id"],
        user["emp_no"],
        user["name"],
        user["role"],
        user["factory_code"],
        source,
        getattr(request.state, "request_id", None),
        users.read_scope(user["id"], user["role"], user["factory_code"]),
    )
    request.state.user = cu
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


def require_super_admin(request: Request) -> CurrentUser:
    """账户管理：只有系统管理员账户；其他管理员也不行。"""
    cu = authenticate(request)
    if not cu.is_super_admin:
        raise biz(ErrorCode.USER_MANAGE_FORBIDDEN)
    return cu


def _client_ip(request: Request) -> str | None:
    real = request.headers.get("X-Real-IP")
    return real.strip() if real and real.strip() else (request.client.host if request.client else None)


def mes_user(request: Request) -> CurrentUser:
    """/api/mes/v1 的鉴权：错误用 MES 错误结构。

    - MES 接口账户：每次请求带 Authorization: Bearer <工号>:<密钥>（不换令牌），只限所绑生产组织；
      错误 401 INVALID_CREDENTIALS、423 ACCOUNT_LOCKED、403 IP_NOT_ALLOWED / ACCOUNT_DISABLED。
    - 其他账户（查询员等）：用登录令牌，按可看范围，只能调只读接口；错误 401 TOKEN_EXPIRED / TOKEN_INVALID。
    """
    header = request.headers.get("Authorization")
    token = header[7:].strip() if header is not None and header[:7].lower() == "bearer " else ""
    if not token:
        raise MesError(401, "INVALID_CREDENTIALS", "缺少认证信息：Authorization: Bearer <工号>:<密钥>")
    emp_no, colon, secret = token.partition(":")
    if colon:
        user = users.mes_authenticate(emp_no, secret, _client_ip(request), getattr(request.state, "request_id", None))
    else:
        try:
            claims = parse_token(token)
        except BizError:
            if is_expired(token):
                raise MesError(401, "TOKEN_EXPIRED", "令牌已过期，请重新登录") from None
            raise MesError(401, "TOKEN_INVALID", "令牌无效") from None
        user = db.one(
            "SELECT id, emp_no, name, role, factory_code, status, pwd_changed_at, last_login_at "
            "FROM sn_user WHERE id = %s",
            claims.user_id,
        )
        if user is None or pwd_version(user["pwd_changed_at"]) != claims.pwd_version:
            raise MesError(401, "TOKEN_INVALID", "令牌已失效")
        if user["role"] == util.MES:
            raise MesError(401, "TOKEN_INVALID", "MES 接口账户请用 Authorization: Bearer <工号>:<密钥>")
        if user["status"] != "ACTIVE":
            raise MesError(403, "ACCOUNT_DISABLED", "账户已停用")
        if claims.session_version is not None and session_version(user["last_login_at"]) != claims.session_version:
            raise MesError(401, "TOKEN_INVALID", "该账户已在其他页面登录")
    cu = CurrentUser(
        user["id"],
        user["emp_no"],
        user["name"],
        user["role"],
        user["factory_code"],
        API,
        getattr(request.state, "request_id", None),
        users.read_scope(user["id"], user["role"], user["factory_code"]),
    )
    request.state.user = cu
    return cu


UserDep = Annotated[CurrentUser, Depends(current_user)]
AdminDep = Annotated[CurrentUser, Depends(require_role(util.ADMIN))]
FactoryDep = Annotated[CurrentUser, Depends(require_role(util.FACTORY))]
SuperAdminDep = Annotated[CurrentUser, Depends(require_super_admin)]
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
