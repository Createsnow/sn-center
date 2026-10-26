"""SN 查询（F-18，只读，不能在这里转走号）。"""

from fastapi import APIRouter

from app.api.deps import UserDep, download, page_of
from app.schemas.common import Flag, OptInt
from app.services import query

router = APIRouter(prefix="/sn", tags=["query"])


def _filter(**kw) -> query.Filter:
    kw["pi_all"] = bool(kw.get("pi_all"))
    m = kw.get("material_code")
    # 物料为空也是一个分组：传 material_code= 时按空物料查
    kw["material_code"] = None if m is None else m
    return query.Filter(**kw)


@router.get(
    "",
    summary="SN 明细；status 另可为 SUSPENDED（暂停交付）；"
    "seq_from / seq_to 按 PI 自己的流水范围查（须带 pi），附 range 小计",
)
def sn_list(
    cu: UserDep,
    factory_code: str | None = None,
    customer_code: str | None = None,
    pi: str | None = None,
    bill_no: str | None = None,
    material_code: str | None = None,
    length: str | None = None,
    status: str | None = None,
    delivery_mode: str | None = None,
    sn: str | None = None,
    batch_no: str | None = None,
    pi_all: Flag = None,
    seq_from: str | None = None,
    seq_to: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    f = _filter(
        factory_code=factory_code,
        customer_code=customer_code,
        pi_no=pi,
        bill_no=bill_no,
        material_code=material_code,
        length=length,
        status=status,
        delivery_mode=delivery_mode,
        sn=sn,
        batch_no=batch_no,
        pi_all=pi_all,
        seq_from=seq_from,
        seq_to=seq_to,
    )
    return query.list_items(cu, f, page_of(page, page_size))


@router.get("/export-check", summary="导出前：是否超过上限（超过只导出前 N 行）")
def sn_export_check(
    cu: UserDep,
    factory_code: str | None = None,
    customer_code: str | None = None,
    pi: str | None = None,
    bill_no: str | None = None,
    material_code: str | None = None,
    length: str | None = None,
    status: str | None = None,
    delivery_mode: str | None = None,
    sn: str | None = None,
    batch_no: str | None = None,
    pi_all: Flag = None,
    seq_from: str | None = None,
    seq_to: str | None = None,
) -> dict:
    f = _filter(
        factory_code=factory_code,
        customer_code=customer_code,
        pi_no=pi,
        bill_no=bill_no,
        material_code=material_code,
        length=length,
        status=status,
        delivery_mode=delivery_mode,
        sn=sn,
        batch_no=batch_no,
        pi_all=pi_all,
        seq_from=seq_from,
        seq_to=seq_to,
    )
    return query.export_check(cu, f)


@router.get("/export", summary="导出 SN 明细 xlsx（列同领取文件，另加交付方式）")
def sn_export(
    cu: UserDep,
    factory_code: str | None = None,
    customer_code: str | None = None,
    pi: str | None = None,
    bill_no: str | None = None,
    material_code: str | None = None,
    length: str | None = None,
    status: str | None = None,
    delivery_mode: str | None = None,
    sn: str | None = None,
    batch_no: str | None = None,
    pi_all: Flag = None,
    seq_from: str | None = None,
    seq_to: str | None = None,
):
    f = _filter(
        factory_code=factory_code,
        customer_code=customer_code,
        pi_no=pi,
        bill_no=bill_no,
        material_code=material_code,
        length=length,
        status=status,
        delivery_mode=delivery_mode,
        sn=sn,
        batch_no=batch_no,
        pi_all=pi_all,
        seq_from=seq_from,
        seq_to=seq_to,
    )
    return download("sn_export", query.export(cu, f))
