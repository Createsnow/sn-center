"""账户管理：只有系统管理员。账户只停用、不删除。"""

from fastapi import APIRouter

from app.api.deps import AdminDep, page_of
from app.schemas.common import OptInt
from app.schemas.user import UserCreateIn, UserPasswordIn, UserUpdateIn
from app.services import users

router = APIRouter(prefix="/users", tags=["users"])


@router.get("")
def user_list(
    cu: AdminDep,
    q: str | None = None,
    role: str | None = None,
    status: str | None = None,
    factory_code: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return users.list_users(q, role, status, factory_code, page_of(page, page_size))


@router.post("")
def user_create(body: UserCreateIn, cu: AdminDep) -> dict:
    return users.create(cu, body.emp_no, body.name, body.role, body.factory_code, body.password)


@router.put("/{id}")
def user_update(id: int, body: UserUpdateIn, cu: AdminDep) -> dict:
    return users.update(cu, id, body.name, body.role, body.factory_code)


@router.post("/{id}/disable")
def user_disable(id: int, cu: AdminDep) -> dict:
    return users.disable(cu, id)


@router.post("/{id}/enable")
def user_enable(id: int, cu: AdminDep) -> dict:
    return users.enable(cu, id)


@router.post("/{id}/reset-password")
def user_reset(id: int, body: UserPasswordIn, cu: AdminDep) -> dict:
    return users.reset_password(cu, id, body.password)
