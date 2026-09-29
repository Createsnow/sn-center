from fastapi import APIRouter, Request

from app.api.deps import UserDep
from app.core import utils as util
from app.schemas.auth import LangIn, LoginIn, PasswordIn
from app.services import users

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post(
    "/login",
    tags=["open"],
    summary="登录（页面与对外接口共用），返回 Bearer 令牌；同一工号连续失败过多会临时锁定（429 LOGIN_LOCKED）",
)
def login(body: LoginIn, request: Request) -> dict:
    return users.login(body.emp_no, body.password, getattr(request.state, "request_id", None), _client(request))


def _client(request: Request) -> str | None:
    """留痕用的来源地址：经 nginx 时取 X-Real-IP，直连取对端地址。"""
    real = request.headers.get("X-Real-IP")
    peer = request.client.host if request.client else None
    return util.cut(real.strip(), 64) if real and real.strip() else peer


@router.get("/me")
def me(cu: UserDep) -> dict:
    return users.me(cu)


@router.post("/password", summary="修改自己的密码（首次登录必须先改）；返回新令牌")
def change_password(body: PasswordIn, cu: UserDep) -> dict:
    return users.change_password(cu, body.old_password, body.new_password)


@router.put("/lang")
def set_lang(body: LangIn, cu: UserDep) -> dict:
    return users.set_lang(cu, body.lang)
