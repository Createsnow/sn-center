"""领取页（F-14）：全部账户可看（查询员只读）；领取只有总部（代任一组织）与本组织操作员。"""

from fastapi import APIRouter

from app.api.deps import AdminOrFactoryDep, UserDep, download, page_of
from app.schemas.common import Flag, OptInt
from app.schemas.misc import AcquireIn
from app.services import acquire, files

router = APIRouter(prefix="/acquire", tags=["acquire"])


@router.get("/pis", summary="每个「生产组织 + PI」一行；tab = ALL / ACQUIRABLE / APPLYING；mes = 是否对接 MES")
def acq_pis(
    cu: UserDep, factory_code: str | None = None, pi: str | None = None, tab: str | None = None, mes: Flag = None
) -> list:
    return acquire.pis(cu, factory_code, pi, tab, mes)


@router.get("/preview", summary="确认框：可领取、在未确认或失败批次中不能领取、暂停交付的枚数")
def acq_preview(cu: AdminOrFactoryDep, factory_code: str | None = None, pi: str | None = None) -> dict:
    return acquire.acquire_preview(cu, factory_code, pi)


@router.get(
    "/segments", summary="号段：生产组织 + PI + 单据 + 序号 + 物料 + 连续号段 + 状态；pi_all=true 时只读查看各组织"
)
def acq_segments(
    cu: UserDep,
    factory_code: str | None = None,
    pi: str | None = None,
    status: str | None = None,
    pi_all: Flag = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return acquire.segments(cu, factory_code, pi, status, bool(pi_all), page_of(page, page_size))


@router.post("/take", summary="按 PI 整批领取（请求号幂等；未完成的批次用原请求号重试）")
def acq_take(body: AcquireIn, cu: AdminOrFactoryDep) -> dict:
    return acquire.acquire(cu, body.factory_code, body.pi_no, body.request_no)


@router.get("/batches", summary="领取批次：按 PI、批次号、请求号查找")
def acq_batches(
    cu: UserDep,
    factory_code: str | None = None,
    pi: str | None = None,
    batch_no: str | None = None,
    request_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return acquire.batches(cu, factory_code, pi, page_of(page, page_size), batch_no, request_no)


@router.get("/batches/{batch_no}/items")
def acq_batch_items(batch_no: str, cu: UserDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return acquire.batch_items(cu, batch_no, page_of(page, page_size))


@router.get("/batches/{batch_no}/file", summary="领取文件（可反复下载，只读）；format=csv|xlsx")
def acq_batch_file(batch_no: str, cu: UserDep, format: str = "xlsx"):
    fmt = files.fmt_of(format)
    b, body = acquire.batch_file(cu, batch_no, fmt)
    return download("acquire_" + b["batch_no"], fmt, files.content_type(fmt), body)
