"""生成与分配、起始号与历史导入：只有总部。"""

from fastapi import APIRouter, Query

from app.api.deps import AdminDep
from app.schemas.common import OptInt
from app.schemas.sn import AllocateIn, GenerateIn, PiInitIn, PreviewIn
from app.services import generate, pi_init

router = APIRouter(tags=["generate"])


@router.get("/generate/context", summary="订单生成上下文：物料行、额度、「工厂 + PI」汇总、规则、计数器、任务")
def gen_context(cu: AdminDep, bill_no: str = Query(...)) -> dict:
    return generate.context(bill_no)


@router.post(
    "/generate/preview",
    summary="预演：只给起止号、数量与号段，不落库；返回预演令牌。PI 没有绑定规则且未选定过时须传 rule_id",
)
def gen_preview(body: PreviewIn, cu: AdminDep) -> dict:
    return generate.preview(cu, body.bill_no, body.qty, body.start_seq, body.rule_id)


@router.post("/generate", summary="凭本次预演令牌生成；数量大时返回 RUNNING，轮询任务进度")
def gen_generate(body: GenerateIn, cu: AdminDep) -> dict:
    return generate.generate(cu, body.preview_token, body.bill_no, body.qty, body.start_seq)


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
    return generate.allocations(bill_no)


@router.get("/pi-init")
def pi_status(cu: AdminDep, pi: str = Query(...)) -> dict:
    return pi_init.status(pi)


@router.post("/pi-init", summary="首次生成前：指定一次起始号，并可导入历史已发出 SN（状态已打印，参与查重）")
def pi_init_post(body: PiInitIn, cu: AdminDep) -> dict:
    return pi_init.init(
        cu,
        body.pi_no,
        body.start_seq,
        body.sns,
        body.customer_code,
        body.factory_code,
        body.material_code,
        body.file_name,
    )
