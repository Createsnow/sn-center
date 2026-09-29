"""对外接口（MES / 打印系统）：领取、批次明细、回调、只读拉取。没有转厂接口。

痕迹来源记为 API。厂区账户只能操作本厂；总部账户需指定工厂。
"""

from fastapi import APIRouter, Query

from app.api.deps import AdminOrFactoryDep, UserDep, page_of
from app.core import utils as util
from app.schemas.common import OptInt, blank_to_none
from app.schemas.sn import CallbackIn, OpenAcquireIn
from app.services import acquire, query

router = APIRouter(prefix="/open", tags=["open"])


@router.post(
    "/acquire",
    summary="按 PI 整批领取：传出该厂该 PI 全部待领取的号并改为待打印。request_no 必填，"
    "同一请求号重复调用返回同一批次、不再改动任何号；返回批次号、本批数量与第一页明细",
)
def open_acquire(body: OpenAcquireIn, cu: AdminOrFactoryDep) -> dict:
    b = acquire.acquire(cu, body.factory_code, body.pi, body.request_no)
    size = 1000 if body.page_size is None else body.page_size
    return {"batch": b, "items": acquire.batch_items(cu, b["batch_no"], util.Page.of(1, size))}


@router.get("/batches/{batch_no}/items", summary="按批次号分页取明细（可反复读取）：完整 SN、进制流水、十进制数")
def open_batch_items(batch_no: str, cu: UserDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return acquire.batch_items(cu, batch_no, page_of(page, page_size, 1000))


@router.post(
    "/callback",
    summary="打印回调：SN 清单或起止号 + 批次号 + 目标状态 PRINTED。只改属于该厂、该批次、"
    "当前待打印的号；已打印的不重复改；已转走 / 申请中的号不改并逐一列出",
)
def open_callback(body: CallbackIn, cu: AdminOrFactoryDep) -> dict:
    ranges = None if body.ranges is None else [r.model_dump() for r in body.ranges]
    return acquire.callback(cu, body.batch_no, body.target_status, body.sns, ranges)


@router.get("/sn", summary="按生产组织、客户、PI 只读拉取（物料可不传），不改变领取 / 打印状态")
def open_pull(
    cu: UserDep,
    factory_code: str = Query(...),
    pi: str = Query(...),
    customer_code: str | None = None,
    material_code: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    f = query.Filter(factory_code, customer_code, pi, blank_to_none(material_code))
    return query.list_items(cu, f, page_of(page, page_size, 1000))
