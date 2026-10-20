"""SN 生成（F-09、F-10）与分配（F-11 分配）：只有总部。"""

from fastapi import APIRouter

from app.api.deps import AdminDep
from app.schemas.common import OptInt
from app.schemas.gen import AllocateIn, PreviewIn, RevokeIn, RevokeLinesIn, RunIn
from app.services import generate, workbench

router = APIRouter(prefix="/generate", tags=["generate"])


@router.get(
    "/pis",
    summary="PI 列表：状态、行上按钮依据、上方汇总；生成页 tab = TO_GENERATE（含生成中）/ GENERATED，"
    "分配页 tab = TO_ALLOCATE（含推送中断）/ ALLOCATED",
)
def gen_pis(cu: AdminDep, q: str | None = None, tab: str | None = None) -> dict:
    return workbench.pis(cu, q, tab)


@router.get("/context", summary="生成详情（只给单据时取其 PI）：单据与物料行、规则、生成任务、可撤销的号段")
def gen_context(cu: AdminDep, pi: str | None = None, bill_no: str | None = None) -> dict:
    return workbench.context(cu, pi, bill_no)


@router.get("/alloc-context", summary="分配详情：待分配的物料行（号段、目标生产组织）、推送进度、分配记录")
def gen_alloc_context(cu: AdminDep, pi: str | None = None) -> dict:
    return workbench.alloc_context(cu, pi)


@router.post("/preview", summary="预演：范围内物料行整行生成 → 起止号、各物料行数量与号段；不能生成时逐行列出原因")
def gen_preview(body: PreviewIn, cu: AdminDep) -> dict:
    lines = [x.model_dump() for x in body.lines or []]
    return generate.preview(cu, body.pi_no, body.scope, body.bills, lines)


@router.post("/run", summary="凭预演令牌生成：数量不大时当场完成，否则返回 RUNNING，轮询进度")
def gen_run(body: RunIn, cu: AdminDep) -> dict:
    return generate.generate(cu, body.token)


@router.get("/jobs", summary="生成任务（最近 N 条），revocable 标出可整次撤销的那一次，物料行上标出可单独撤销的那一行")
def gen_jobs(cu: AdminDep, pi: str, limit: OptInt = None) -> list:
    return generate.jobs(pi, 20 if limit is None else limit)


@router.get("/batches/{batch}", summary="一次生成的进度：百分比、已生成 / 总数、已写到的号、速度、预计剩余时间")
def gen_progress(batch: str, cu: AdminDep) -> dict:
    return generate.progress(batch)


@router.post("/batches/{batch}/cancel", summary="取消生成：已写入的号全部删除，任务记为已取消")
def gen_cancel(batch: str, cu: AdminDep) -> dict:
    return generate.cancel(cu, batch)


@router.post(
    "/batches/{batch}/revoke",
    summary="撤销生成（最小单位物料行）：给单据与序号只撤这一行，否则撤这次剩下的全部行；"
    "只能从 PI 流水末尾往前撤，号须全部仍待分配且没转过单；原因必填",
)
def gen_revoke(batch: str, body: RevokeIn, cu: AdminDep) -> dict:
    return generate.revoke(cu, batch, body.reason, body.bill_no, body.seq)


@router.post(
    "/revoke",
    summary="撤到所选物料行为止：从 PI 流水末尾往前撤，流水排在所选行之后的物料行一并撤销，一个事务内完成；"
    "号须全部仍待分配且没转过单；原因必填",
)
def gen_revoke_lines(body: RevokeLinesIn, cu: AdminDep) -> dict:
    return generate.revoke_lines(cu, body.pi_no, [x.model_dump() for x in body.lines or []], body.reason)


@router.post("/alloc-preview", summary="分配面板：按生产组织汇总枚数、单据和物料、是否对接 MES、推送次数")
def gen_alloc_preview(body: AllocateIn, cu: AdminDep) -> dict:
    return generate.alloc_preview(body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None)


@router.post("/allocate", summary="分配到订单的生产组织：对接 MES 的改为待推送并自动推送，未对接的改为待领取")
def gen_allocate(body: AllocateIn, cu: AdminDep) -> dict:
    return generate.allocate(cu, body.pi_no, body.bills, [x.model_dump() for x in body.lines or []] or None)
