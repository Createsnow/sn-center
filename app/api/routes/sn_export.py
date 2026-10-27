"""SN 导出（F-14）：已分配的号按「生产组织 + PI + 单据」查看与下载，不看推送、拉取状态；
只读，全部账户可用（按可看的生产组织）。"""

from fastapi import APIRouter

from app.api.deps import UserDep, download, page_of
from app.schemas.common import Flag, OptInt
from app.services import sn_export

router = APIRouter(prefix="/export", tags=["export"])


@router.get("/bills", summary="每个「生产组织 + PI + 单据」一行；q 按 PI 或单据编号前缀；mes = 是否对接 MES")
def export_bills(
    cu: UserDep,
    factory_code: str | None = None,
    q: str | None = None,
    mes: Flag = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return sn_export.bills(cu, factory_code, q, mes, page_of(page, page_size))


@router.get("/lines", summary="明细顶部：每个单据序号 + 物料一行，枚数、米数、暂停枚数、申请中枚数")
def export_lines(
    cu: UserDep, pi: str | None = None, bill_no: str | None = None, factory_code: str | None = None
) -> list:
    return sn_export.lines(cu, factory_code, pi, bill_no)


@router.get("/segments", summary="明细：单据序号 + 物料 + 连续号段（不按推送状态拆段）")
def export_segments(
    cu: UserDep,
    pi: str | None = None,
    bill_no: str | None = None,
    factory_code: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return sn_export.segments(cu, factory_code, pi, bill_no, page_of(page, page_size))


@router.get("/file", summary="下载一张单据的已分配号 xlsx（列：PI、单据、物料编码、SN）；不改状态")
def export_file(
    cu: UserDep,
    pi: str | None = None,
    bill_no: str | None = None,
    factory_code: str | None = None,
):
    name, body = sn_export.file(cu, factory_code, pi, bill_no)
    return download(name, body)
