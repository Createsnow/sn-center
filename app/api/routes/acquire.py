"""历史领取批次（只读）：页面领取已改为 SN 导出，不再新建领取批次；SN 查询的「领取批次」页签用这些接口。"""

from fastapi import APIRouter

from app.api.deps import UserDep, download, page_of
from app.schemas.common import OptInt
from app.services import acquire, files

router = APIRouter(prefix="/acquire", tags=["acquire"])


@router.get("/batches", summary="历史领取批次：按 PI、批次号、请求号查找")
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
