"""SN 生成与撤销（F-09、F-10）：系统管理员、普通操作员（只含授权组织的单据）。分配见 allocation。"""

from fastapi import APIRouter

from app.api.deps import OperatorDep
from app.schemas.common import OptInt
from app.schemas.gen import PreviewIn, RevokeConfigIn, RevokeIn, RevokeLinesIn, RunIn
from app.services import generate, orders, workbench

router = APIRouter(prefix="/generate", tags=["generate"])


@router.get(
    "/pis",
    summary="生成页 PI 列表：状态、行上按钮依据、上方汇总；tab = TO_GENERATE（含生成中）/ GENERATED；"
    "与订单页同一排序（单据修改日期新的在前）",
)
def gen_pis(cu: OperatorDep, q: str | None = None, tab: str | None = None) -> dict:
    return workbench.pis(cu, q, tab, by_modify=True)


@router.get("/context", summary="生成详情（只给单据时取其 PI）：单据与物料行、规则、生成任务、可撤销的号段")
def gen_context(cu: OperatorDep, pi: str | None = None, bill_no: str | None = None) -> dict:
    return workbench.context(cu, pi, bill_no)


@router.post("/preview", summary="预演：范围内物料行整行生成 → 起止号、各物料行数量与号段；不能生成时逐行列出原因")
def gen_preview(body: PreviewIn, cu: OperatorDep) -> dict:
    lines = [x.model_dump() for x in body.lines or []]
    return generate.preview(cu, body.pi_no, body.scope, body.bills, lines)


@router.post("/run", summary="凭预演令牌生成：数量不大时当场完成，否则返回 RUNNING，轮询进度")
def gen_run(body: RunIn, cu: OperatorDep) -> dict:
    return generate.generate(cu, body.token)


@router.get("/jobs", summary="生成任务（最近 N 条），revocable 标出可整次撤销的那一次，物料行上标出可单独撤销的那一行")
def gen_jobs(cu: OperatorDep, pi: str, limit: OptInt = None) -> list:
    return generate.jobs(pi, 20 if limit is None else limit, cu.read_factories(None))


@router.get("/batches/{batch}", summary="一次生成的进度：百分比、已生成 / 总数、已写到的号、速度、预计剩余时间")
def gen_progress(batch: str, cu: OperatorDep) -> dict:
    return generate.progress(batch, cu)


@router.post("/batches/{batch}/cancel", summary="取消生成：已写入的号全部删除，任务记为已取消")
def gen_cancel(batch: str, cu: OperatorDep) -> dict:
    return generate.cancel(cu, batch)


@router.post(
    "/batches/{batch}/revoke",
    summary="撤销生成（最小单位物料行）：给单据与序号只撤这一行，否则撤这次剩下的全部行；"
    "只能从 PI 流水末尾往前撤，号须全部仍待分配且没转过单；原因必填",
)
def gen_revoke(batch: str, body: RevokeIn, cu: OperatorDep) -> dict:
    return generate.revoke(cu, batch, body.reason, body.bill_no, body.seq)


@router.post(
    "/revoke",
    summary="撤到所选物料行为止：从 PI 流水末尾往前撤，流水排在所选行之后的物料行一并撤销，一个事务内完成；"
    "号须全部仍待分配且没转过单；原因选填（不填记「生成页撤销生成」）",
)
def gen_revoke_lines(body: RevokeLinesIn, cu: OperatorDep) -> dict:
    return generate.revoke_lines(cu, body.pi_no, [x.model_dump() for x in body.lines or []], body.reason)


@router.post(
    "/revoke-config",
    summary="撤销物料行配置（待处理、可生成页签）：所选物料行中还没有号的，物料行选定的规则、手填的附件项次、米数、产品单位"
    "恢复为未配置；"
    "有号的行跳过（有号的行撤销生成）",
)
def gen_revoke_config(body: RevokeConfigIn, cu: OperatorDep) -> dict:
    return orders.revoke_config(cu, body.pi_no, [x.model_dump() for x in body.lines or []])
