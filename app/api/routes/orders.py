"""生产订单快照：总部与查询员可看；同步只有总部。"""

from fastapi import APIRouter, Body

from app.api.deps import AdminDep, AdminOrQueryDep, page_of
from app.schemas.common import OptInt
from app.schemas.prd_mo import SyncIn
from app.services import orders

router = APIRouter(prefix="/orders", tags=["orders"])


@router.get("", summary="订单页左表：按单据汇总（客户编码为空的单据不显示）")
def order_list(
    cu: AdminOrQueryDep,
    q: str | None = None,
    customer: str | None = None,
    pi: str | None = None,
    factory_code: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return orders.list_bills(cu, q, customer, pi, factory_code, page_of(page, page_size))


@router.get("/meta")
def order_meta(cu: AdminOrQueryDep) -> dict:
    return orders.meta()


@router.get("/customers")
def order_customers(cu: AdminOrQueryDep, q: str | None = None) -> list:
    return orders.customers(cu, q)


@router.get("/{bill_no}/lines", summary="订单页右表：该单各物料行")
def order_lines(bill_no: str, cu: AdminOrQueryDep) -> list:
    return [orders.prd_mo_json(r) for r in orders.lines_of(cu, bill_no)]


@router.post("/sync", summary="同步金蝶：不传 bill_no = 全量；传 bill_no = 只替换该单")
def order_sync(cu: AdminDep, body: SyncIn | None = Body(None)) -> dict:
    if body is None or body.bill_no is None:
        return orders.sync_all(cu)
    return orders.sync_bill(cu, body.bill_no)
