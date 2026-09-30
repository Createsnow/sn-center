"""生产订单快照：总部与查询员可看；同步只有总部。"""

from fastapi import APIRouter, Body

from app.api.deps import AdminDep, AdminOrQueryDep, page_of
from app.schemas.common import Flag, OptInt
from app.schemas.prd_mo import SyncIn, SyncScheduleIn
from app.services import order_sync_schedule, orders

router = APIRouter(prefix="/orders", tags=["orders"])


@router.get(
    "",
    summary="订单页左表：按单据汇总（客户编码为空的单据不显示）；q 匹配单据 / PI / PO / 物料 / 客户；"
    "pending=1 只要还有额度或待分配的单据",
)
def order_list(
    cu: AdminOrQueryDep,
    q: str | None = None,
    customer: str | None = None,
    pi: str | None = None,
    factory_code: str | None = None,
    pending: Flag = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return orders.list_bills(cu, q, customer, pi, factory_code, page_of(page, page_size), bool(pending))


@router.get("/pis", summary="生成页 PI 下拉：按 PI 汇总单据数与额度；可按客户过滤，q 匹配 PI")
def order_pis(
    cu: AdminOrQueryDep,
    q: str | None = None,
    customer: str | None = None,
    pending: Flag = None,
    limit: OptInt = None,
) -> list:
    return orders.pis(cu, q, customer, bool(pending), 50 if limit is None else limit)


@router.get("/sync-schedule", summary="定时全量同步：开关、间隔、下次执行与最近一次结果")
def sync_schedule(cu: AdminOrQueryDep) -> dict:
    return order_sync_schedule.status()


@router.put("/sync-schedule", summary="修改定时全量同步（只有总部）：开启 / 关闭、间隔 10 分钟–7 天")
def sync_schedule_save(body: SyncScheduleIn, cu: AdminDep) -> dict:
    return order_sync_schedule.save(cu, body.enabled, body.interval_minutes)


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
