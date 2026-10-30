"""转单（F-15）、转组织（F-16）与记录页（F-17）：系统管理员、普通操作员直接转移，原因必填
（普通操作员的来源须在授权范围内，目标不限）。"""

from fastapi import APIRouter

from app.api.deps import OperatorDep, download, page_of
from app.schemas.common import OptInt
from app.schemas.transfer import TransferIn
from app.services import transfers

router = APIRouter(prefix="/transfers", tags=["transfers"])


@router.get("", summary="转移记录：转单与转组织合并，kind = BILL / ORG")
def all_list(
    cu: OperatorDep,
    kind: str | None = None,
    factory_code: str | None = None,
    pi: str | None = None,
    bill_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return transfers.list_all(cu, kind, factory_code, pi, bill_no, page_of(page, page_size))


@router.get(
    "/sources", summary="来源候选：PI 下各单据、各物料的号数（kind = BILL 转单 / ORG 转组织，转组织限来源生产组织）"
)
def sources(
    cu: OperatorDep, kind: str = transfers.KIND_ORG, pi: str | None = None, factory_code: str | None = None
) -> dict:
    return transfers.sources(cu, kind, factory_code, pi)


@router.post("/bill/preview", summary="转单确认框：枚数、各状态、不可转及原因、来源和目标已生成数的变化")
def bill_preview(body: TransferIn, cu: OperatorDep) -> dict:
    return transfers.preview(cu, transfers.KIND_BILL, body.req())


@router.post("/bill", summary="转单：整张单据、单据 + 物料或单枚 / 号段改挂到目标物料行，原因必填；操作员限来源组织")
def bill_transfer(body: TransferIn, cu: OperatorDep) -> dict:
    return transfers.transfer_bill(cu, body.req())


@router.get("/bill", summary="转单单列表")
def bill_list(
    cu: OperatorDep,
    factory_code: str | None = None,
    pi: str | None = None,
    bill_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return transfers.list_bill(cu, factory_code, pi, bill_no, page_of(page, page_size))


@router.get("/bill/{id:int}")
def bill_get(id: int, cu: OperatorDep) -> dict:
    return transfers.bill_transfer_view(cu, id)


@router.get("/bill/{id}/items")
def bill_items(id: int, cu: OperatorDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return transfers.items(cu, transfers.KIND_BILL, id, page_of(page, page_size))


@router.post("/org/candidates", summary="转组织确认框：各状态枚数、可转枚数、不可转原因、已生成数的变化、转出枚数")
def org_candidates(body: TransferIn, cu: OperatorDep) -> dict:
    return transfers.preview(cu, transfers.KIND_ORG, body.req())


@router.post("/org/direct", summary="转组织（直接执行），原因必填；普通操作员的来源组织须在授权范围内，目标组织不限")
def org_direct(body: TransferIn, cu: OperatorDep) -> dict:
    return transfers.direct(cu, body.req())


@router.get("/org", summary="转组织单列表")
def org_list(
    cu: OperatorDep,
    factory_code: str | None = None,
    pi: str | None = None,
    bill_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return transfers.list_org(cu, factory_code, pi, bill_no, page_of(page, page_size))


@router.get("/org/{id:int}")
def org_get(id: int, cu: OperatorDep) -> dict:
    return transfers.org_view(cu, id)


@router.get("/org/{id}/items")
def org_items(id: int, cu: OperatorDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return transfers.items(cu, transfers.KIND_ORG, id, page_of(page, page_size))


@router.get("/org/{id}/out-list", summary="转出号清单（完整 SN、流水、原批次）：来源现场据此作废旧标签")
def org_out_list(id: int, cu: OperatorDep):
    t, body = transfers.out_list(cu, id)
    return download("out_" + t["transfer_no"], body)
