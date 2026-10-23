"""账户管理（F-01）：只有系统管理员。账户只停用、不删除。MES 接口账户的密钥加密保存，可随时查看、重置。"""

from fastapi import APIRouter

from app.api.deps import SuperAdminDep, page_of
from app.schemas.common import OptInt
from app.schemas.user import UserCreateIn, UserPasswordIn, UserUpdateIn
from app.services import users

router = APIRouter(prefix="/users", tags=["users"])


@router.get("", summary="账户列表：工号开头匹配、姓名包含；按角色（含 MES 接口账户 mes）、状态、生产组织筛选")
def user_list(
    cu: SuperAdminDep,
    q: str | None = None,
    role: str | None = None,
    status: str | None = None,
    factory_code: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return users.list_users(q, role, status, factory_code, page_of(page, page_size))


@router.post("", summary="新建账户；MES 接口账户返回 secret（之后可在查看密钥接口再取）")
def user_create(body: UserCreateIn, cu: SuperAdminDep) -> dict:
    return users.create(
        cu, body.emp_no, body.name, body.role, body.factory_code, body.password, body.factory_codes, body.ip_whitelist
    )


@router.put("/{id}")
def user_update(id: int, body: UserUpdateIn, cu: SuperAdminDep) -> dict:
    return users.update(cu, id, body.name, body.role, body.factory_code, body.factory_codes, body.ip_whitelist)


@router.post("/{id}/disable")
def user_disable(id: int, cu: SuperAdminDep) -> dict:
    return users.disable(cu, id)


@router.post("/{id}/enable")
def user_enable(id: int, cu: SuperAdminDep) -> dict:
    return users.enable(cu, id)


@router.post("/{id}/reset-password", summary="重置页面账户密码：旧令牌失效，该账户须用新密码重新登录（不强制改密）")
def user_reset(id: int, body: UserPasswordIn, cu: SuperAdminDep) -> dict:
    return users.reset_password(cu, id, body.password)


@router.post("/{id}/reset-secret", summary="重置 MES 接口账户密钥：返回新 secret，旧密钥立即失效")
def user_reset_secret(id: int, cu: SuperAdminDep) -> dict:
    return users.reset_secret(cu, id)


@router.get("/{id}/secret", summary="查看 MES 接口账户当前密钥（留痕）；解不开时须重置")
def user_view_secret(id: int, cu: SuperAdminDep) -> dict:
    return users.view_secret(cu, id)
