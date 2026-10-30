"""SN 导出（F-14，页面在 SN 分配页的「已分配」页签）：已分配的号按「生产组织 + PI + 单据」查看与下载，
不看推送、拉取状态；只读，全部页面账户可用（按授权的生产组织；审计员在 SN 查询导出）。"""

from fastapi import APIRouter

from app.api.deps import UserDep, download, page_of
from app.schemas.common import Flag, OptInt
from app.services import sn_export

router = APIRouter(prefix="/export", tags=["export"])


@router.get(
    "/bills", summary="每个「生产组织 + PI + 单据」一行；q 按 PI 或单据编号前缀，pi 按 PI 精确；mes = 是否对接 MES"
)
def export_bills(
    cu: UserDep,
    factory_code: str | None = None,
    q: str | None = None,
    pi: str | None = None,
    mes: Flag = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return sn_export.bills(cu, factory_code, q, mes, page_of(page, page_size), pi)


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


@router.get(
    "/items",
    summary="明细：逐枚列出该单据的全部已分配号（不按号段合并），顺序同下载文件；bill_seq + material 只看一个物料行，"
    "只给 material 看该物料编码的号；sn_from / sn_to 按 SN 起止号（含两端）；q 按完整 SN 模糊匹配",
)
def export_items(
    cu: UserDep,
    pi: str | None = None,
    bill_no: str | None = None,
    factory_code: str | None = None,
    bill_seq: OptInt = None,
    material: str | None = None,
    q: str | None = None,
    sn_from: str | None = None,
    sn_to: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return sn_export.items(
        cu, factory_code, pi, bill_no, page_of(page, page_size), bill_seq, material, q, sn_from, sn_to
    )


@router.get("/file", summary="下载一张单据的已分配号 xlsx（列：PI、单据、物料编码、SN）；不改状态")
def export_file(
    cu: UserDep,
    pi: str | None = None,
    bill_no: str | None = None,
    factory_code: str | None = None,
):
    name, body = sn_export.file(cu, factory_code, pi, bill_no)
    return download(name, body)
