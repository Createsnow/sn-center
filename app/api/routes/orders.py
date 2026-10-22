"""生产订单快照（F-05、F-07）与订单变更提醒（F-06）：总部与查询员可看；同步、填写附件项次与米标、处理只有总部。"""

from fastapi import APIRouter

from app.api.deps import AdminDep, AdminOrQueryDep, UserDep, page_of
from app.schemas.common import OptInt
from app.schemas.misc import AlertHandleIn, AttachIn, LengthBatchIn, SyncIn
from app.services import changes, orders

router = APIRouter(prefix="/orders", tags=["orders"])


@router.get("", summary="左表：每张单据一行（含客户编码为空的单据），单据编号大的在前")
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


@router.get(
    "/meta", summary="顶部：最近同步、快照规模、客户为空的单据数、附件项次待填写行数、比对同步状态、未处理提醒数"
)
def order_meta(cu: AdminOrQueryDep) -> dict:
    return orders.meta(cu)


@router.get("/customers")
def order_customers(cu: AdminOrQueryDep, q: str | None = None) -> list:
    return orders.customers(cu, q)


@router.get("/targets", summary="转单、转组织目标候选：快照中的物料行（pi 精确匹配；生产组织操作员申请转组织也要用）")
def order_targets(cu: UserDep, q: str | None = None, pi: str | None = None, limit: OptInt = None) -> list:
    return orders.targets(q, 50 if limit is None else limit, pi)


@router.get("/{bill_no}/lines", summary="右表：该单表头与各物料行")
def order_lines(bill_no: str, cu: AdminOrQueryDep) -> dict:
    return orders.lines_of(cu, bill_no)


@router.post("/sync", summary="按单据编号同步（不带单据状态条件，只看单据状态）")
def order_sync(body: SyncIn, cu: AdminDep) -> dict:
    return orders.sync_bill(cu, body.bill_no)


@router.post("/compare", summary="立即比对同步（正有同步在运行时跳过，返回 skipped）")
def order_compare(cu: AdminDep) -> dict:
    r = orders.compare_sync(cu, "MANUAL")
    return {"skipped": True} if r is None else r


@router.post("/page-sync", summary="进页同步：后台触发一次比对同步，立即返回")
def order_page_sync(cu: AdminOrQueryDep) -> dict:
    return {"started": orders.page_entry_sync(cu)}


@router.post("/rebuild", summary="全量重建快照（首次上线导入、怀疑快照与金蝶不一致时）")
def order_rebuild(cu: AdminDep) -> dict:
    return orders.rebuild(cu)


@router.put("/attach", summary="填写附件项次（金蝶为空时必填；物料行有号后不能改）；不写回金蝶，不进推送和领取文件")
def order_attach(body: AttachIn, cu: AdminDep) -> dict:
    return orders.fill_attach(cu, body.bill_no, body.seq, body.value)


@router.put("/length", summary="填写米标（金蝶为空时选填，字符串原样写入 SN；留空用默认 000；物料行有号后不能改）")
def order_length(body: AttachIn, cu: AdminDep) -> dict:
    return orders.fill_length(cu, body.bill_no, body.seq, body.value)


@router.put("/length/batch", summary="统一设置米标：范围内米标为默认 000、还没有号的物料行设为同一个值（已填写的不改）")
def order_length_batch(body: LengthBatchIn, cu: AdminDep) -> dict:
    return orders.fill_lengths(
        cu, body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None, body.value
    )


@router.get("/alerts", summary="订单变更提醒；status = OPEN（默认）/ CLOSED / ALL")
def order_alerts(
    cu: AdminOrQueryDep,
    status: str | None = None,
    bill_no: str | None = None,
    alert_type: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return changes.list_alerts(cu, status, bill_no, alert_type, page_of(page, page_size))


@router.post("/alerts/{id}/handle", summary="处理提醒：CONFIRM / MAP（target_seq）/ UNRELATED / VOID（note 必填）/ ACK")
def order_alert_handle(id: int, body: AlertHandleIn, cu: AdminDep) -> dict:
    return changes.handle(cu, id, body.action, body.note, body.target_seq)
