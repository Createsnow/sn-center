from fastapi import APIRouter, Request

from app.api.deps import UserDep
from app.core import utils as util
from app.schemas.auth import LangIn, LoginIn, PasswordIn
from app.services import users

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post(
    "/login",
    tags=["open"],
    summary="登录（页面与对外接口共用），返回 Bearer 令牌",
)
def login(body: LoginIn, request: Request) -> dict:
    return users.login(
        body.emp_no, body.password, getattr(request.state, "request_id", None), _client(request), _is_page(request)
    )


#: 页面登录时前端带的请求头；不带的（MES 等对接方）不参与「一个账户只在一个页面有效」
PAGE_CLIENT_HEADER = "X-SN-Client"


def _is_page(request: Request) -> bool:
    return request.headers.get(PAGE_CLIENT_HEADER, "").strip().lower() == "web"


def _client(request: Request) -> str | None:
    """留痕用的来源地址：经 nginx 时取 X-Real-IP，直连取对端地址。"""
    real = request.headers.get("X-Real-IP")
    peer = request.client.host if request.client else None
    return util.cut(real.strip(), 64) if real and real.strip() else peer


@router.get("/me")
def me(cu: UserDep) -> dict:
    return users.me(cu)


@router.post("/password", summary="修改自己的密码；成功后该账户所有令牌失效（含当前页面），须用新密码重新登录")
def change_password(body: PasswordIn, cu: UserDep) -> dict:
    return users.change_password(cu, body.old_password, body.new_password)


@router.put("/lang")
def set_lang(body: LangIn, cu: UserDep) -> dict:
    return users.set_lang(cu, body.lang)
