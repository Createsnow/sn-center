"""领取页：所有账户可看（查询员只读）；领取、打印只有总部（代任一厂）与本厂操作员。"""

from fastapi import APIRouter

from app.api.deps import AdminOrFactoryDep, UserDep, download, page_of
from app.schemas.common import Flag, OptInt, blank_to_none
from app.schemas.sn import AcquireIn
from app.services import acquire, files

router = APIRouter(prefix="/acquire", tags=["acquire"])


@router.get("/pis")
def acq_pis(cu: UserDep, factory_code: str | None = None, pi: str | None = None) -> list:
    return acquire.pis(cu, factory_code, pi)


@router.get("/segments", summary="按「工厂 + PI + 物料 + 连续号段 + 状态」分行；pi_all=true 时厂区只读查看同 PI 各厂")
def acq_segments(
    cu: UserDep,
    factory_code: str | None = None,
    pi: str | None = None,
    material_code: str | None = None,
    status: str | None = None,
    pi_all: Flag = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return acquire.segments(
        cu, factory_code, pi, blank_to_none(material_code), status, bool(pi_all), page_of(page, page_size)
    )


@router.post("/take", summary="点「待领取」：该厂该 PI 全部待领取 → 待打印（请求号幂等）")
def acq_take(body: AcquireIn, cu: AdminOrFactoryDep) -> dict:
    return acquire.acquire(cu, body.factory_code, body.pi_no, body.request_no)


@router.post("/print", summary="点「待打印」：该厂该 PI 全部待打印 → 已打印，返回打印单号")
def acq_print(body: AcquireIn, cu: AdminOrFactoryDep) -> dict:
    return acquire.print_pi(cu, body.factory_code, body.pi_no, body.request_no)


@router.get("/prints", summary="打印记录；可按打印单号、请求号精确查")
def acq_prints(
    cu: UserDep,
    factory_code: str | None = None,
    pi: str | None = None,
    print_no: str | None = None,
    request_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return acquire.prints(cu, factory_code, pi, page_of(page, page_size), print_no, request_no)


@router.get("/prints/{print_no}/file", summary="打印文件（完整 SN、进制流水、十进制数）；format=csv|xlsx")
def acq_print_file(print_no: str, cu: UserDep, format: str = "xlsx"):
    p = acquire.print_record(cu, print_no)
    fmt = files.fmt_of(format)
    return download("print_" + p["print_no"], fmt, files.content_type(fmt), acquire.print_file(p, fmt))


@router.get("/batches", summary="领取批次；可按批次号、请求号、来源（PAGE / API）精确查")
def acq_batches(
    cu: UserDep,
    factory_code: str | None = None,
    pi: str | None = None,
    batch_no: str | None = None,
    request_no: str | None = None,
    source: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return acquire.batches(cu, factory_code, pi, page_of(page, page_size), batch_no, request_no, source)


@router.get("/batches/{batch_no}/items")
def acq_batch_items(batch_no: str, cu: UserDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return acquire.batch_items(cu, batch_no, page_of(page, page_size))


@router.get("/batches/{batch_no}/file", summary="批次导出（格式同打印文件）；只读，不改状态；format=csv|xlsx")
def acq_batch_file(batch_no: str, cu: UserDep, format: str = "xlsx"):
    b = acquire.batch(cu, batch_no)
    fmt = files.fmt_of(format)
    return download("batch_" + b["batch_no"], fmt, files.content_type(fmt), acquire.batch_file(b, fmt))
