"""生产组织（F-03，只读同步）与 MES 对接配置（F-04，仅总部）。"""

from fastapi import APIRouter

from app.api.deps import AdminDep, UserDep
from app.schemas.misc import MesConfigIn, MesToggleIn
from app.services import factories

router = APIRouter(prefix="/factories", tags=["factories"])


@router.get("", summary="生产组织列表；总部另看 MES 对接配置（凭证不回显）与绑定的启用账户数")
def factory_list(cu: UserDep) -> list:
    return factories.list_factories(cu)


@router.post("/sync", summary="从金蝶组织机构同步：本地没有的新增（禁用的不新增），名称、启用状态随金蝶更新")
def factory_sync(cu: AdminDep) -> dict:
    return factories.sync_from_k3(cu)


@router.put("/{code}/mes-config", summary="保存 MES 对接配置；已开启对接时改了地址、认证或凭证须先连接测试通过")
def factory_config(code: str, body: MesConfigIn, cu: AdminDep) -> dict:
    return factories.save_config(cu, code, body.model_dump(exclude_unset=True))


@router.post("/{code}/mes-test", summary="连接测试：用页面上的配置（凭证留空 = 已存的）发一个 PING 批次，只提示结果")
def factory_test(code: str, body: MesConfigIn, cu: AdminDep) -> dict:
    return factories.test_connection(cu, code, body.model_dump(exclude_unset=True))


@router.get("/{code}/mes-toggle", summary="切换「对接 MES」前的提示：受影响枚数、关闭的阻塞项")
def factory_toggle_preview(code: str, cu: AdminDep) -> dict:
    return factories.mes_toggle_preview(code)


@router.post("/{code}/mes-toggle", summary="开启 / 关闭「对接 MES」")
def factory_toggle(code: str, body: MesToggleIn, cu: AdminDep) -> dict:
    return factories.set_mes_enabled(cu, code, body.enabled)
