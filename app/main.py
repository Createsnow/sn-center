"""多工厂 SN 防重管控 · FastAPI + MySQL 8。

启动顺序：建表 / 升级（Flyway 兼容）→ 建首个管理员 → 维护月分区 → 可选演示数据 → 每日分区维护定时任务。
请求链路：RequestIdMiddleware（请求号 / 缓存写请求体）→ CORS → 路由（鉴权依赖）→ services → MySQL。
"""

import mimetypes
import re
from contextlib import asynccontextmanager
from pathlib import Path

import pymysql
import structlog
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api.deps import run_sync
from app.api.routes import api_router, health
from app.core import scheduler
from app.core.config import reload_settings, settings
from app.core.errors import ErrorCode, biz
from app.core.exceptions import AppError, BizError
from app.core.logging import setup_logging
from app.core.security import CurrentUser
from app.db import init_db
from app.db.session import db, is_integrity, is_lock_conflict
from app.middleware.request_id import WRITE_METHODS, RequestIdMiddleware
from app.services import audit as audit_svc
from app.services import demo_seed, partitions, users

log = structlog.get_logger()


def _dist() -> Path:
    return settings.root / "frontend" / "dist"


def _json_safe(params: dict) -> dict:
    """错误参数只允许基础类型进响应体，其余转字符串。"""
    return {k: v if v is None or isinstance(v, (str, int, float, bool)) else str(v) for k, v in params.items()}


def _body(code: str, message: str, params: dict | None = None) -> dict:
    return {"code": code, "message": message, "params": params or {}}


def _snake_to_camel(name: str) -> str:
    return re.sub(r"_([a-z0-9])", lambda m: m.group(1).upper(), name)


def _is_api_path(path: str) -> bool:
    return (
        path.startswith("/api")
        or path.startswith("/health")
        or path in {"/docs", "/redoc", "/openapi.json"}
        or path.startswith("/docs/")
    )


def _file(path: Path) -> FileResponse:
    ctype, _ = mimetypes.guess_type(path.name)
    ctype = ctype or "application/octet-stream"
    if ctype.startswith("text/") or "javascript" in ctype:
        ctype += "; charset=utf-8"
    return FileResponse(path, media_type=ctype)


def _spa_fallback(request: Request) -> Response:
    """未知的非 API GET 路径返回前端 index.html（history 路由）；其余 404。"""
    path = request.url.path
    if request.method in {"GET", "HEAD"} and not _is_api_path(path):
        dist = _dist().resolve()
        candidate = (dist / path.lstrip("/")).resolve()
        if candidate.is_relative_to(dist) and candidate.is_file():
            return _file(candidate)
        index = dist / "index.html"
        if index.is_file():
            return _file(index)
    return JSONResponse({"detail": "Not Found"}, status_code=404)


def startup() -> None:
    db.configure(settings.db_target(), settings.db_pool_size)
    init_db()
    if settings.get_bool("SN_RESET_ADMIN", False):
        users.reset_admin(settings.init_admin_password)
        log.warning("admin_reset", emp_no="admin", note="password=SN_INIT_ADMIN_PASSWORD; remove SN_RESET_ADMIN now")
    elif users.bootstrap_admin(settings.init_admin_password):
        log.warning("bootstrap_admin", emp_no="admin", note="must change password on first login")
    partitions.maintain()
    if settings.demo_seed:
        demo_seed.seed_if_empty()
    log.info("sn_center_ready", retention_days=settings.audit_retention_days)


@asynccontextmanager
async def lifespan(app: FastAPI):
    await run_sync(startup)
    job = scheduler.start_daily(settings.partition_cron, partitions.maintain)
    try:
        yield
    finally:
        # 后台生成任务由线程池自行跑完（解释器退出前会等待）
        job.stop()
        db.close()


def create_app() -> FastAPI:
    reload_settings()
    setup_logging(json_output=settings.production)
    docs = settings.docs_enabled
    app = FastAPI(
        title=settings.app_name,
        description=(
            "总部统一生成并分配 SN，工厂按 PI 领取、打印。对外只有 open 分组：领取、批次明细、回调、只读拉取。"
            "先调 POST /api/auth/login 取令牌，再放在 Authorization: Bearer <token>。"
        ),
        version="1.0.0",
        lifespan=lifespan,
        docs_url="/docs" if docs else None,
        redoc_url="/redoc" if docs else None,
        openapi_url="/openapi.json" if docs else None,
        openapi_tags=[
            {
                "name": "open",
                "description": "对外接口：领取（按 PI 整批，请求号幂等）、按批次号分页取明细、打印回调、"
                "按生产组织 / 客户 / PI 只读拉取。",
            },
        ],
        swagger_ui_parameters={"persistAuthorization": True},
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"] if settings.cors_allow_any_origin else settings.cors_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Request-ID", "Accept-Language"],
        expose_headers=["Content-Disposition", "X-Request-ID"],
    )
    app.add_middleware(RequestIdMiddleware)

    @app.exception_handler(AppError)
    async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
        # 业务错误：{code, message, params}；已登录账户的 /api 写请求被拒绝时另记一条 FAIL 痕迹
        if isinstance(exc, BizError):
            user = getattr(request.state, "user", None)
            if (
                isinstance(user, CurrentUser)
                and request.method in WRITE_METHODS
                and request.url.path.startswith("/api/")
            ):
                await run_sync(
                    audit_svc.record_failure,
                    user,
                    request.method,
                    request.url.path,
                    getattr(request.state, "body", None),
                    exc.message,
                )
            return JSONResponse(_body(exc.code, exc.message, _json_safe(exc.params)), status_code=exc.status_code)
        return JSONResponse(_body(exc.code, exc.message), status_code=exc.status_code)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        # 请求体问题报 body；查询 / 路径参数报形参名（bill_no → billNo，与既有契约一致）
        errs = exc.errors()
        loc = errs[0].get("loc", ("body",)) if errs else ("body",)
        detail = "body" if loc and loc[0] == "body" else _snake_to_camel(str(loc[-1]))
        b = biz(ErrorCode.VALIDATION, detail=detail)
        return JSONResponse(_body(b.code, b.message, b.params), status_code=422)

    @app.exception_handler(StarletteHTTPException)
    async def http_handler(request: Request, exc: StarletteHTTPException):
        if exc.status_code == 404:
            return _spa_fallback(request)
        if exc.status_code == 405:
            return JSONResponse(_body("METHOD_NOT_ALLOWED", "Method Not Allowed"), status_code=405)
        return JSONResponse({"detail": exc.detail}, status_code=exc.status_code)

    @app.exception_handler(pymysql.err.MySQLError)
    async def db_handler(request: Request, exc: pymysql.err.MySQLError) -> JSONResponse:
        if is_lock_conflict(exc) or is_integrity(exc):
            # 死锁 / 锁等待超时 / 约束冲突：让调用方重试
            log.warning("db_conflict", path=request.url.path, err=str(exc))
            b = biz(ErrorCode.CONFLICT_RETRY)
            return JSONResponse(_body(b.code, b.message), status_code=409)
        log.error("db_error", path=request.url.path, exc_info=exc)
        return JSONResponse(_body("INTERNAL", "Internal Server Error"), status_code=500)

    @app.exception_handler(Exception)
    async def internal_handler(request: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled_error", path=request.url.path, exc_info=exc)
        return JSONResponse(_body("INTERNAL", "Internal Server Error"), status_code=500)

    app.include_router(health.router)
    app.include_router(api_router, prefix="/api")

    @app.get("/", include_in_schema=False)
    async def root():
        index = _dist() / "index.html"
        if index.is_file():
            return _file(index)
        return RedirectResponse("/docs", status_code=307)

    if (_dist() / "assets").is_dir():
        app.mount("/assets", StaticFiles(directory=_dist() / "assets"), name="assets")
    return app


app = create_app()
