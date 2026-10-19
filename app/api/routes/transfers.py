"""转单（F-15，只有总部）、转组织（F-16）与记录页（F-17）。"""

from fastapi import APIRouter

from app.api.deps import AdminDep, AdminOrFactoryDep, FactoryDep, UserDep, download, page_of
from app.schemas.common import OptInt
from app.schemas.transfer import BillTransferIn, NoteIn, OrgTransferIn
from app.services import files, transfers

router = APIRouter(prefix="/transfers", tags=["transfers"])


@router.post("/bill/preview", summary="转单确认框：枚数、各状态、不可转及原因、来源和目标已生成数的变化")
def bill_preview(body: BillTransferIn, cu: AdminDep) -> dict:
    return transfers.bill_preview(body.req())


@router.post("/bill", summary="转单：整单作废（VOID）或拆单（SPLIT），原因必填")
def bill_transfer(body: BillTransferIn, cu: AdminDep) -> dict:
    return transfers.transfer_bill(cu, body.req())


@router.get("/bill", summary="转单单列表")
def bill_list(
    cu: UserDep,
    factory_code: str | None = None,
    pi: str | None = None,
    bill_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return transfers.list_bill(cu, factory_code, pi, bill_no, page_of(page, page_size))


@router.get("/bill/{id:int}")
def bill_get(id: int, cu: UserDep) -> dict:
    return transfers.bill_transfer_view(cu, id)


@router.get("/bill/{id}/items")
def bill_items(id: int, cu: UserDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return transfers.items(cu, transfers.KIND_BILL, id, page_of(page, page_size))


@router.post("/org/candidates", summary="转组织确认框：各状态枚数、可转枚数、不可转原因、物料清单")
def org_candidates(body: OrgTransferIn, cu: UserDep) -> dict:
    return transfers.org_candidates(cu, body.req())


@router.post("/org", summary="生产组织操作员提交转组织申请")
def org_apply(body: OrgTransferIn, cu: FactoryDep) -> dict:
    return transfers.apply(cu, body.req())


@router.post("/org/direct", summary="总部直接转组织（不经申请），原因必填")
def org_direct(body: OrgTransferIn, cu: AdminDep) -> dict:
    return transfers.direct(cu, body.req())


@router.get("/org", summary="转组织单：status = PENDING / APPROVED / REJECTED / WITHDRAWN；返回待处理数")
def org_list(
    cu: UserDep,
    status: str | None = None,
    factory_code: str | None = None,
    pi: str | None = None,
    bill_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return transfers.list_org(cu, status, factory_code, pi, bill_no, page_of(page, page_size))


@router.get("/org/{id:int}")
def org_get(id: int, cu: UserDep) -> dict:
    return transfers.org_view(cu, id)


@router.get("/org/{id}/items")
def org_items(id: int, cu: UserDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return transfers.items(cu, transfers.KIND_ORG, id, page_of(page, page_size))


@router.post("/org/{id}/approve")
def org_approve(id: int, body: NoteIn, cu: AdminDep) -> dict:
    return transfers.approve(cu, id, body.note)


@router.post("/org/{id}/reject")
def org_reject(id: int, body: NoteIn, cu: AdminDep) -> dict:
    return transfers.reject(cu, id, body.note)


@router.post("/org/{id}/withdraw")
def org_withdraw(id: int, cu: AdminOrFactoryDep) -> dict:
    return transfers.withdraw(cu, id)


@router.get("/org/{id}/out-list", summary="转出号清单（完整 SN、流水、原批次）：来源现场据此作废旧标签")
def org_out_list(id: int, cu: UserDep, format: str = "xlsx"):
    fmt = files.fmt_of(format)
    t, body = transfers.out_list(cu, id, fmt)
    return download("out_" + t["transfer_no"], fmt, files.content_type(fmt), body)
