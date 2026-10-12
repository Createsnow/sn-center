from fastapi import APIRouter

from app.api.deps import AdminDep, UserDep
from app.schemas.factory import FactoryIn, FactoryUpdateIn
from app.services import factories

router = APIRouter(prefix="/factories", tags=["factories"])


@router.get("")
def factory_list(cu: UserDep) -> list:
    return factories.list_factories()


@router.post("/sync")
def factory_sync(cu: AdminDep) -> dict:
    return factories.sync_from_k3(cu)


@router.post("")
def factory_create(body: FactoryIn, cu: AdminDep) -> dict:
    return factories.create(cu, body.factory_code, body.factory_name)


@router.put("/{code}", summary="改工厂名称（须与金蝶生产订单上的生产组织名称一致）")
def factory_update(code: str, body: FactoryUpdateIn, cu: AdminDep) -> dict:
    return factories.update(cu, code, body.factory_name)


@router.post("/{code}/disable", summary="停用工厂：不能再作为生成、分配、转入、账户绑定的目标；已分到的号照常领取打印")
def factory_disable(code: str, cu: AdminDep) -> dict:
    return factories.set_enabled(cu, code, False)


@router.post("/{code}/enable", summary="启用工厂")
def factory_enable(code: str, cu: AdminDep) -> dict:
    return factories.set_enabled(cu, code, True)
