"""SN 分配（F-11 分配）：只有总部。PI 列表与生成页共用同一份工作台数据，按分配页签筛选。"""

from fastapi import APIRouter

from app.api.deps import AdminDep
from app.schemas.allocation import AllocateIn
from app.services import allocation, workbench

router = APIRouter(prefix="/allocation", tags=["allocation"])


@router.get("/pis", summary="分配页 PI 列表：tab = TO_ALLOCATE（有号待分配或推送中断）/ ALLOCATED（已分配完）")
def alloc_pis(cu: AdminDep, q: str | None = None, tab: str | None = None) -> dict:
    return workbench.pis(cu, q, tab)


@router.get("/context", summary="分配详情：待分配的物料行（号段、目标生产组织）、推送进度、分配记录")
def alloc_context(cu: AdminDep, pi: str | None = None) -> dict:
    return allocation.context(cu, pi)


@router.post("/preview", summary="分配面板：按生产组织汇总枚数、单据和物料、是否对接 MES、推送次数")
def alloc_preview(body: AllocateIn, cu: AdminDep) -> dict:
    return allocation.preview(body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None)


@router.post("/allocate", summary="分配到订单的生产组织：对接 MES 的改为待推送并自动推送，未对接的改为已分配")
def alloc_run(body: AllocateIn, cu: AdminDep) -> dict:
    return allocation.allocate(cu, body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None)
