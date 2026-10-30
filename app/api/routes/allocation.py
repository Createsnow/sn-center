"""SN 分配（F-11 分配）：系统管理员、普通操作员（授权组织）。左侧按单据列出，右侧为所选单据所在 PI 的详情。"""

from fastapi import APIRouter

from app.api.deps import OperatorDep
from app.schemas.allocation import AllocateIn
from app.services import allocation

router = APIRouter(prefix="/allocation", tags=["allocation"])


@router.get(
    "/bills",
    summary="分配页单据列表（按号取）：SN 枚数、状态（待分配 / 待处理 / 已分配）；q 按 PI 或单据编号；"
    "tab = TO_ALLOCATE（待分配、待处理）/ ALLOCATED（已分配）",
)
def alloc_bills(cu: OperatorDep, q: str | None = None, tab: str | None = None) -> dict:
    return allocation.bills(cu, q, tab)


@router.get("/context", summary="分配详情：各单据物料行（枚数、号段、目标组织、推送进度）、各组织推送进度、推送批次")
def alloc_context(cu: OperatorDep, pi: str | None = None) -> dict:
    return allocation.context(cu, pi)


@router.post("/preview", summary="分配面板：按生产组织汇总枚数、单据和物料、是否对接 MES、推送次数")
def alloc_preview(body: AllocateIn, cu: OperatorDep) -> dict:
    return allocation.preview(cu, body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None)


@router.post("/allocate", summary="分配到订单的生产组织：对接 MES 的改为待推送并自动推送，未对接的改为已分配")
def alloc_run(body: AllocateIn, cu: OperatorDep) -> dict:
    return allocation.allocate(cu, body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None)
