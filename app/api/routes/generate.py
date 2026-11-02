"""SN 生成与删号（F-09、F-10）：系统管理员、普通操作员（只含授权组织的单据）。分配见 allocation。"""

from fastapi import APIRouter

from app.api.deps import OperatorDep
from app.schemas.common import OptInt
from app.schemas.gen import DeleteIn, PreviewIn, RevokeConfigIn, RunIn
from app.services import deletion, generate, orders, workbench

router = APIRouter(prefix="/generate", tags=["generate"])


@router.get(
    "/pis",
    summary="生成页 PI 列表：状态、行上按钮依据、上方汇总；tab = TO_GENERATE（含生成中）/ GENERATED；"
    "与订单页同一排序（单据修改日期新的在前）",
)
def gen_pis(cu: OperatorDep, q: str | None = None, tab: str | None = None) -> dict:
    return workbench.pis(cu, q, tab, by_modify=True)


@router.get("/context", summary="生成详情（只给单据时取其 PI）：单据与物料行、规则、生成任务、空号段")
def gen_context(cu: OperatorDep, pi: str | None = None, bill_no: str | None = None) -> dict:
    return workbench.context(cu, pi, bill_no)


@router.post(
    "/preview",
    summary="预演：范围内物料行整行生成 → 起止号、各物料行数量与号段；不能生成时逐行列出原因。"
    "流水来源 seq_mode = MAX（从最大号 + 1 起）/ RANGE（seq_start～seq_end 内的空号段与未使用的号，"
    "不够时从最大号之后接着编）",
)
def gen_preview(body: PreviewIn, cu: OperatorDep) -> dict:
    lines = [x.model_dump() for x in body.lines or []]
    return generate.preview(cu, body.pi_no, body.scope, body.bills, lines, body.seq_mode, body.seq_start, body.seq_end)


@router.get("/gaps", summary="空号段：PI 流水 1～最大号之间没有号的连续段（删号腾出），最大号与下一个号")
def gen_gaps(cu: OperatorDep, pi: str | None = None) -> dict:
    return generate.gaps(pi, cu)


@router.post("/run", summary="凭预演令牌生成：数量不大时当场完成，否则返回 RUNNING，轮询进度")
def gen_run(body: RunIn, cu: OperatorDep) -> dict:
    return generate.generate(cu, body.token)


@router.get("/jobs", summary="生成任务（最近 N 条）")
def gen_jobs(cu: OperatorDep, pi: str, limit: OptInt = None) -> list:
    return generate.jobs(pi, 20 if limit is None else limit, cu.read_factories(None))


@router.get("/batches/{batch}", summary="一次生成的进度：百分比、已生成 / 总数、已写到的号、速度、预计剩余时间")
def gen_progress(batch: str, cu: OperatorDep) -> dict:
    return generate.progress(batch, cu)


@router.post("/batches/{batch}/cancel", summary="取消生成：已写入的号全部删除，任务记为已取消")
def gen_cancel(batch: str, cu: OperatorDep) -> dict:
    return generate.cancel(cu, batch)


@router.post(
    "/delete/preview",
    summary="删号确认框：范围（单据 / 物料行 / SN 起止号）内要删的枚数、各状态枚数、生产组织、物料行、要作废重建的批次",
)
def gen_delete_preview(body: DeleteIn, cu: OperatorDep) -> dict:
    lines = [x.model_dump() for x in body.lines or []]
    return deletion.preview(cu, body.pi_no, body.bills, lines, body.start_sn, body.end_sn)


@router.post(
    "/delete",
    summary="删号：删除范围内挂在本 PI 下的号（状态不限），额度还给号现在挂的物料行，最大流水号不退回（留下空号段）；"
    "号所在的未确认或失败批次作废重建；原因选填（不填记「生成页删除 SN」）",
)
def gen_delete(body: DeleteIn, cu: OperatorDep) -> dict:
    lines = [x.model_dump() for x in body.lines or []]
    return deletion.delete(cu, body.pi_no, body.bills, lines, body.start_sn, body.end_sn, body.reason)


@router.get("/deleted", summary="删号记录（新的在前）：删除单号、枚数、起止号、单据、原因、操作人与时间")
def gen_deleted(cu: OperatorDep, pi: str | None = None) -> list:
    return deletion.deleted(pi)


@router.post(
    "/revoke-config",
    summary="撤销物料行配置（待处理、可生成页签）：所选物料行中还没有号的，物料行选定的规则、手填的附件项次、米数、产品单位"
    "恢复为未配置；"
    "有号的行跳过（有号的行先删号）",
)
def gen_revoke_config(body: RevokeConfigIn, cu: OperatorDep) -> dict:
    return orders.revoke_config(cu, body.pi_no, [x.model_dump() for x in body.lines or []])
