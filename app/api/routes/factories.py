from fastapi import APIRouter

from app.api.deps import AdminDep, UserDep
from app.schemas.factory import FactoryIn
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
