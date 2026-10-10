"""SN 查询（只读，不能在这里转走号）。"""

from fastapi import APIRouter

from app.api.deps import UserDep, download, page_of
from app.schemas.common import Flag, OptInt, blank_to_none
from app.services import files, query

router = APIRouter(prefix="/sn", tags=["query"])


def _filter(
    factory_code, customer_code, pi, material_code, status, sn, bill_no, batch_no, pi_all, seq_from, seq_to
) -> query.Filter:
    return query.Filter(
        factory_code,
        customer_code,
        pi,
        blank_to_none(material_code),
        status,
        sn,
        bill_no,
        batch_no,
        bool(pi_all),
        seq_from,
        seq_to,
    )


@router.get(
    "",
    summary="SN 列表；pi_all=true 时厂区只读查看同一 PI 已分到各厂的号；"
    "seq_from / seq_to 按 PI 自己的流水号范围查（须带 pi，可填流水号或完整 SN），结果附 range 小计",
)
def sn_list(
    cu: UserDep,
    factory_code: str | None = None,
    customer_code: str | None = None,
    pi: str | None = None,
    material_code: str | None = None,
    status: str | None = None,
    sn: str | None = None,
    bill_no: str | None = None,
    batch_no: str | None = None,
    pi_all: Flag = None,
    seq_from: str | None = None,
    seq_to: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    f = _filter(factory_code, customer_code, pi, material_code, status, sn, bill_no, batch_no, pi_all, seq_from, seq_to)
    return query.list_items(cu, f, page_of(page, page_size))


@router.get("/export")
def sn_export(
    cu: UserDep,
    factory_code: str | None = None,
    customer_code: str | None = None,
    pi: str | None = None,
    material_code: str | None = None,
    status: str | None = None,
    sn: str | None = None,
    bill_no: str | None = None,
    batch_no: str | None = None,
    pi_all: Flag = None,
    seq_from: str | None = None,
    seq_to: str | None = None,
    format: str = "xlsx",
):
    f = _filter(factory_code, customer_code, pi, material_code, status, sn, bill_no, batch_no, pi_all, seq_from, seq_to)
    query.check_exportable(cu, f)
    fmt = files.fmt_of(format)
    return download("sn_export", fmt, files.content_type(fmt), query.export(cu, f, fmt))
