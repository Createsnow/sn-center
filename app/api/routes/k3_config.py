"""金蝶连接参数（生产订单页「金蝶连接」）：只有系统管理员。应用密钥不回显。"""

from fastapi import APIRouter

from app.api.deps import AdminDep
from app.schemas.misc import K3ConfigIn
from app.services import k3_config

router = APIRouter(prefix="/k3-config", tags=["k3-config"])


@router.get("", summary="当前金蝶连接参数（不含应用密钥）、来源（页面保存 / .env）、最近连接测试与最后修改")
def k3_config_view(cu: AdminDep) -> dict:
    return k3_config.view()


@router.post("/test", summary="连接测试：用页面上的参数（密钥留空 = 当前的）登录金蝶并读组织机构，只提示结果、不保存")
def k3_config_test(body: K3ConfigIn, cu: AdminDep) -> dict:
    return k3_config.test_connection(cu, body.model_dump(exclude_unset=True))


@router.put("", summary="保存金蝶连接参数：先用新参数连接测试，通过才保存；下一次调用金蝶即生效")
def k3_config_save(body: K3ConfigIn, cu: AdminDep) -> dict:
    return k3_config.save(cu, body.model_dump(exclude_unset=True))
