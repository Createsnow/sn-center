"""生成与分配：只有总部。"""

from fastapi import APIRouter, Query

from app.api.deps import AdminDep
from app.schemas.common import OptInt
from app.schemas.sn import AllocateIn, GenerateIn, PiAllocateIn, PiGenerateIn, PiPreviewIn, PreviewIn
from app.services import generate

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
    return generate.pi_preview(cu, body.pi_no, body.qty, body.rule_id)


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


@router.post(
    "/generate/preview",
    summary="预演：只给起止号、数量与号段，不落库；返回预演令牌。PI 没有绑定规则且未选定过时须传 rule_id",
)
def gen_preview(body: PreviewIn, cu: AdminDep) -> dict:
    return generate.preview(cu, body.bill_no, body.qty, body.rule_id)


@router.post("/generate", summary="凭本次预演令牌生成；数量大时返回 RUNNING，轮询任务进度")
def gen_generate(body: GenerateIn, cu: AdminDep) -> dict:
    return generate.generate(cu, body.preview_token, body.bill_no, body.qty)


@router.get("/generate/jobs/{id}")
def gen_job(id: int, cu: AdminDep) -> dict:
    return generate.job(id)


@router.get("/generate/jobs")
def gen_jobs(cu: AdminDep, bill_no: str | None = None, pi: str | None = None, limit: OptInt = None) -> list:
    return generate.jobs(bill_no, pi, 50 if limit is None else limit)


@router.post("/generate/allocate", summary="确认分配：整单分到订单的生产组织，待分配 → 待领取")
def gen_allocate(body: AllocateIn, cu: AdminDep) -> dict:
    return generate.allocate(cu, body.bill_no, body.qty, body.factory_code)


@router.get("/generate/allocations")
def gen_allocations(cu: AdminDep, bill_no: str = Query(...)) -> list:
    return generate.allocations([bill_no])
