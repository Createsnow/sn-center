"""生成与分配（F-09、F-10、F-11 分配）：只有总部。"""

from fastapi import APIRouter

from app.api.deps import AdminDep
from app.schemas.common import OptInt
from app.schemas.gen import AllocateIn, PreviewIn, RevokeIn, RunIn
from app.services import generate, workbench

router = APIRouter(prefix="/generate", tags=["generate"])


@router.get(
    "/pis", summary="PI 列表：状态、行上按钮依据、上方汇总；tab = ALL / TO_GENERATE / GENERATING / TO_ALLOCATE / DONE"
)
def gen_pis(cu: AdminDep, q: str | None = None, tab: str | None = None) -> dict:
    return workbench.pis(cu, q, tab)


@router.get("/context", summary="PI 详情（只给单据时取其 PI）")
def gen_context(cu: AdminDep, pi: str | None = None, bill_no: str | None = None) -> dict:
    return workbench.context(cu, pi, bill_no)


@router.post("/preview", summary="预演：范围、数量 → 起止号、各物料行数量与号段；不能生成时逐行列出原因")
def gen_preview(body: PreviewIn, cu: AdminDep) -> dict:
    lines = [x.model_dump() for x in body.lines or []]
    return generate.preview(cu, body.pi_no, body.scope, body.bills, lines, body.qty)


@router.post("/run", summary="凭预演令牌生成：数量不大时当场完成，否则返回 RUNNING，轮询进度")
def gen_run(body: RunIn, cu: AdminDep) -> dict:
    return generate.generate(cu, body.token)


@router.get("/jobs", summary="生成任务（最近 N 条），revocable 标出可撤销的最后一次")
def gen_jobs(cu: AdminDep, pi: str, limit: OptInt = None) -> list:
    return generate.jobs(pi, 20 if limit is None else limit)


@router.get("/batches/{batch}", summary="一次生成的进度：百分比、已生成 / 总数、已写到的号、速度、预计剩余时间")
def gen_progress(batch: str, cu: AdminDep) -> dict:
    return generate.progress(batch)


@router.post("/batches/{batch}/cancel", summary="取消生成：已写入的号全部删除，任务记为已取消")
def gen_cancel(batch: str, cu: AdminDep) -> dict:
    return generate.cancel(cu, batch)


@router.post(
    "/batches/{batch}/revoke", summary="撤销生成：只能撤这张 PI 流水排在最后、号全部仍待分配且没转过单的一次；原因必填"
)
def gen_revoke(batch: str, body: RevokeIn, cu: AdminDep) -> dict:
    return generate.revoke(cu, batch, body.reason)


@router.post("/alloc-preview", summary="分配面板：按生产组织汇总枚数、单据和物料、是否对接 MES、推送次数")
def gen_alloc_preview(body: AllocateIn, cu: AdminDep) -> dict:
    return generate.alloc_preview(body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None)


@router.post("/allocate", summary="分配到订单的生产组织：对接 MES 的改为待推送并自动推送，未对接的改为待领取")
def gen_allocate(body: AllocateIn, cu: AdminDep) -> dict:
    return generate.allocate(cu, body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None)
