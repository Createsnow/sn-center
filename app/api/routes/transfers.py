"""转厂页（页面专用，不对外）。"""

from fastapi import APIRouter, Body, Query

from app.api.deps import AdminDep, FactoryDep, UserDep, page_of
from app.schemas.common import OptInt
from app.schemas.transfer import NoteIn, TransferIn
from app.services import orders, transfers

router = APIRouter(prefix="/transfers", tags=["transfers"])


@router.get("/candidates", summary="范围内各状态枚数；传 to_pi 时另给与转入 PI 重复的号、转入后下一个号的提示")
def tr_candidates(
    cu: UserDep,
    pi: str = Query(...),
    scope: str = Query(...),
    factory_code: str | None = None,
    material_code: str | None = None,
    sn: str | None = None,
    to_pi: str | None = None,
    to_customer: str | None = None,
) -> dict:
    return transfers.candidates(
        cu,
        transfers.Request(factory_code, pi, scope, material_code, sn, to_pi=to_pi, to_customer=to_customer),
    )


@router.get("/targets", summary="从生产订单快照选择转入目标")
def tr_targets(cu: UserDep, q: str | None = None) -> list:
    return orders.targets(q, 50)


@router.post("", summary="工厂提交转厂申请（原因必填），号变为申请中")
def tr_apply(body: TransferIn, cu: FactoryDep) -> dict:
    return transfers.apply(cu, body.req())


@router.post("/direct", summary="总部不经申请直接转移，号直接成为转入厂的待领取")
def tr_direct(body: TransferIn, cu: AdminDep) -> dict:
    return transfers.direct(cu, body.req())


@router.post("/{id}/approve")
def tr_approve(id: int, cu: AdminDep, body: NoteIn | None = Body(None)) -> dict:
    return transfers.approve(cu, id, None if body is None else body.note)


@router.post("/{id}/reject")
def tr_reject(id: int, cu: AdminDep, body: NoteIn | None = Body(None)) -> dict:
    return transfers.reject(cu, id, None if body is None else body.note)


@router.post("/{id}/withdraw")
def tr_withdraw(id: int, cu: FactoryDep) -> dict:
    return transfers.withdraw(cu, id)


@router.get("")
def tr_list(
    cu: UserDep,
    status: str | None = None,
    factory_code: str | None = None,
    pi: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return transfers.list_transfers(cu, status, factory_code, pi, page_of(page, page_size))


@router.get("/{id}")
def tr_get(id: int, cu: UserDep) -> dict:
    return transfers.get(cu, id)


@router.get("/{id}/items")
def tr_items(id: int, cu: UserDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return transfers.items(cu, id, page_of(page, page_size))
