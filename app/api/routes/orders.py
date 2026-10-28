"""生产订单快照（F-05、F-07）与订单变更提醒（F-06）：系统管理员、普通操作员（只看授权组织的单据）；
全量重建快照只有系统管理员。"""

from fastapi import APIRouter

from app.api.deps import AdminDep, OperatorDep, page_of
from app.schemas.common import OptInt
from app.schemas.misc import AlertHandleIn, AttachIn, SyncIn
from app.services import changes, orders

router = APIRouter(prefix="/orders", tags=["orders"])


@router.get("", summary="左表：每张单据一行（含客户编码为空的单据），金蝶修改日期新的在前")
def order_list(
    cu: OperatorDep,
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
def order_meta(cu: OperatorDep) -> dict:
    return orders.meta(cu)


@router.get("/customers")
def order_customers(cu: OperatorDep, q: str | None = None) -> list:
    return orders.customers(cu, q)


@router.get("/targets", summary="转单、转组织目标候选：快照中的物料行（pi 精确匹配；转组织申请也要用，目标组织不限）")
def order_targets(cu: OperatorDep, q: str | None = None, pi: str | None = None, limit: OptInt = None) -> list:
    return orders.targets(q, 50 if limit is None else limit, pi)


@router.get("/{bill_no}/lines", summary="右表：该单表头与各物料行")
def order_lines(bill_no: str, cu: OperatorDep) -> dict:
    return orders.lines_of(cu, bill_no)


@router.post("/sync", summary="按单据编号同步（不带单据状态条件，只看单据状态）")
def order_sync(body: SyncIn, cu: OperatorDep) -> dict:
    return orders.sync_bill(cu, body.bill_no)


@router.post("/compare", summary="立即比对同步（正有同步在运行时跳过，返回 skipped）")
def order_compare(cu: OperatorDep) -> dict:
    r = orders.compare_sync(cu, "MANUAL")
    return {"skipped": True} if r is None else r


@router.post("/page-sync", summary="进页同步：后台触发一次比对同步，立即返回")
def order_page_sync(cu: OperatorDep) -> dict:
    return {"started": orders.page_entry_sync(cu)}


@router.post("/rebuild", summary="全量重建快照（首次上线导入、怀疑快照与金蝶不一致时）")
def order_rebuild(cu: AdminDep) -> dict:
    return orders.rebuild(cu)


@router.put("/attach", summary="填写附件项次（金蝶为空时必填；物料行有号后不能改）；不写回金蝶，不进推送和领取文件")
def order_attach(body: AttachIn, cu: OperatorDep) -> dict:
    return orders.fill_attach(cu, body.bill_no, body.seq, body.value)


@router.put(
    "/length",
    summary="填写米数（金蝶为空时选填，留空用默认 000；金蝶值含中文时须改成不含中文的米数；物料行有号后不能改）",
)
def order_length(body: AttachIn, cu: OperatorDep) -> dict:
    return orders.fill_length(cu, body.bill_no, body.seq, body.value)


@router.put(
    "/unit",
    summary="填写产品单位（金蝶为空或含中文时；规则带单位时必填，不能含中文，如 M、m、L；留空清除；有号后不能改）",
)
def order_unit(body: AttachIn, cu: OperatorDep) -> dict:
    return orders.fill_unit(cu, body.bill_no, body.seq, body.value)


@router.get("/alerts", summary="订单变更提醒；status = OPEN（默认）/ CLOSED / ALL")
def order_alerts(
    cu: OperatorDep,
    status: str | None = None,
    bill_no: str | None = None,
    alert_type: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return changes.list_alerts(cu, status, bill_no, alert_type, page_of(page, page_size))


@router.post("/alerts/{id}/handle", summary="处理提醒：CONFIRM / MAP（target_seq）/ UNRELATED / VOID（note 必填）/ ACK")
def order_alert_handle(id: int, body: AlertHandleIn, cu: OperatorDep) -> dict:
    return changes.handle(cu, id, body.action, body.note, body.target_seq)
