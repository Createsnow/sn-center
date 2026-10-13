"""生成与分配：只有总部。

生成只能按整张 PI（/generate/pi-preview → /generate/pi）：不提供按单张订单生成，
避免绕开「有订单的生产组织未建档时整张 PI 不能生成」「按单据编号顺序占用额度」。
"""

from fastapi import APIRouter, Query

from app.api.deps import AdminDep
from app.schemas.common import OptInt
from app.schemas.sn import AllocateIn, ManualRuleIn, PiAllocateIn, PiGenerateIn, PiPreviewIn, RevokeIn, RulePlanIn
from app.services import generate, rules

router = APIRouter(tags=["generate"])


@router.get("/generate/context", summary="订单生成上下文：物料行、额度、「工厂 + PI」汇总、规则、计数器、任务")
def gen_context(cu: AdminDep, bill_no: str = Query(...)) -> dict:
    return generate.context(bill_no)


@router.get(
    "/generate/pi-context",
    summary="按 PI 的生成上下文：PI 流水与规则、各订单额度 / 已生成 / 已分配 / 待分配、任务与分配记录；"
    "只给单据时取其 PI",
)
def gen_pi_context(cu: AdminDep, pi: str | None = None, bill_no: str | None = None) -> dict:
    return generate.pi_context(pi, bill_no)


@router.post(
    "/generate/pi-preview",
    summary="按 PI 预演：数量按单据号顺序占用各订单额度，号段接续；"
    "每张订单各返回一个预演令牌，凭这些令牌调用 /generate/pi",
)
def gen_pi_preview(body: PiPreviewIn, cu: AdminDep) -> dict:
    return generate.pi_preview(cu, body.pi_no, body.qty, body.rule_id, _manual(body.manual))


@router.post(
    "/generate/rule-plan",
    summary="选模板 / 手动设置时的即时判断：本 PI 将用哪一版（沿用时另出派生版，后缀加 _v版本号）、第一个号；"
    "手动设置的参数不合法或前缀冲突时报错",
)
def gen_rule_plan(body: RulePlanIn, cu: AdminDep) -> dict:
    return generate.rule_plan(body.pi_no, body.rule_id, _manual(body.manual))


def _manual(m: ManualRuleIn | None) -> rules.ManualIn | None:
    return None if m is None else rules.ManualIn(m.prefix, m.seq_len, m.suffix)


@router.post(
    "/generate/pi",
    summary="按 PI 生成：凭按 PI 预演的各订单令牌（按原顺序），同一个事务里依次生成，任何一张失败整张 PI 回滚；"
    "数量大时返回 RUNNING，轮询各任务进度",
)
def gen_pi_generate(body: PiGenerateIn, cu: AdminDep) -> dict:
    items = [x.model_dump() for x in body.items or []]
    return generate.generate_pi(cu, body.pi_no, items)


@router.post("/generate/pi-allocate", summary="整张 PI 分配：各订单待分配号分到各自的生产组织，同一事务")
def gen_pi_allocate(body: PiAllocateIn, cu: AdminDep) -> dict:
    return generate.allocate_pi(cu, body.pi_no)


@router.get("/generate/jobs/{id}")
def gen_job(id: int, cu: AdminDep) -> dict:
    return generate.job(id)


@router.get("/generate/jobs")
def gen_jobs(cu: AdminDep, bill_no: str | None = None, pi: str | None = None, limit: OptInt = None) -> list:
    return generate.jobs(bill_no, pi, 50 if limit is None else limit)


@router.post(
    "/generate/jobs/{id}/revoke",
    summary="撤销该任务所属的整次生成（同 /generate/batches/{batch}/revoke）",
)
def gen_revoke(id: int, body: RevokeIn, cu: AdminDep) -> dict:
    return generate.revoke(cu, id, body.reason)


@router.get("/generate/batches/{batch}", summary="一次生成的整体进度：已写入枚数 / 总数、已写到的号、状态")
def gen_batch(batch: str, cu: AdminDep) -> dict:
    return generate.batch_progress(batch)


@router.post(
    "/generate/batches/{batch}/cancel",
    summary="取消正在进行的生成：整批回滚，已写入的号全部删除，任务记为 CANCELED；取消留痕",
)
def gen_cancel(batch: str, cu: AdminDep) -> dict:
    return generate.cancel(cu, batch)


@router.post(
    "/generate/batches/{batch}/revoke",
    summary="撤销一次生成（整次）：只能撤销该 PI 流水排在最后、号全部仍待分配的一次成功生成；"
    "号与查重护栏删除，PI 最大号与各订单额度退回，原因必填",
)
def gen_revoke_batch(batch: str, body: RevokeIn, cu: AdminDep) -> dict:
    return generate.revoke_batch(cu, batch, body.reason)


@router.post("/generate/allocate", summary="确认分配：整单分到订单的生产组织，待分配 → 待领取（不能只分一部分）")
def gen_allocate(body: AllocateIn, cu: AdminDep) -> dict:
    return generate.allocate(cu, body.bill_no, body.factory_code)


@router.get("/generate/allocations")
def gen_allocations(cu: AdminDep, bill_no: str = Query(...)) -> list:
    return generate.allocations([bill_no])
