from fastapi import APIRouter

from app.api.deps import UserDep
from app.schemas.auth import LangIn, LoginIn, PasswordIn
from app.services import users

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", tags=["open"], summary="登录（页面与对外接口共用），返回 Bearer 令牌")
def login(body: LoginIn) -> dict:
    return users.login(body.emp_no, body.password)


@router.get("/me")
def me(cu: UserDep) -> dict:
    return users.me(cu)


@router.post("/password", summary="修改自己的密码（首次登录必须先改）；返回新令牌")
def change_password(body: PasswordIn, cu: UserDep) -> dict:
    return users.change_password(cu, body.old_password, body.new_password)


@router.put("/lang")
def set_lang(body: LangIn, cu: UserDep) -> dict:
    return users.set_lang(cu, body.lang)
