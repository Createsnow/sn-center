"""交付批次（F-11、F-18）：系统管理员、普通操作员（授权组织）可查询、续推、人工确认、作废、处理告警。"""

from fastapi import APIRouter

from app.api.deps import OperatorDep, download, page_of
from app.schemas.common import OptInt
from app.schemas.misc import BatchManualIn, DeliveryAlertIn, ResumeIn
from app.services import delivery, files

router = APIRouter(prefix="/delivery", tags=["delivery"])


@router.get("/batches", summary="交付批次（推送、拉取、转出合并显示），最新的在前")
def batch_list(
    cu: OperatorDep,
    factory_code: str | None = None,
    pi: str | None = None,
    batch_no: str | None = None,
    source: str | None = None,
    batch_type: str | None = None,
    result: str | None = None,
    transfer_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    return delivery.list_batches(
        cu, factory_code, pi, batch_no, source, batch_type, result, transfer_no, page_of(page, page_size)
    )


@router.get("/batches/{batch_no}", summary="批次：结果、确认方式、失败原因、人工处理、号段、回执存档")
def batch_get(batch_no: str, cu: OperatorDep) -> dict:
    return delivery.batch_detail(cu, batch_no)


@router.get("/batches/{batch_no}/items", summary="批次明细与逐枚回执")
def batch_items(batch_no: str, cu: OperatorDep, page: OptInt = None, page_size: OptInt = None) -> dict:
    return delivery.batch_items_page(cu, batch_no, page_of(page, page_size))


@router.get("/batches/{batch_no}/export", summary="导出批次明细与逐枚回执 xlsx")
def batch_export(batch_no: str, cu: OperatorDep):
    b = delivery.must_batch(batch_no)
    cu.check_read(b["factory_code"])
    return download(
        "batch_" + b["batch_no"], files.xlsx_bytes(b["batch_no"], delivery.EXPORT_HEADERS, delivery.export_rows(b))
    )


@router.post("/batches/{batch_no}/confirm", summary="人工确认（MES 方确认已全部入库）：原因、依据必填")
def batch_confirm(batch_no: str, body: BatchManualIn, cu: OperatorDep) -> dict:
    return delivery.manual_confirm(cu, batch_no, body.reason, body.basis)


@router.post("/batches/{batch_no}/void", summary="作废（MES 没有处理的批次）：原因、依据必填")
def batch_void(batch_no: str, body: BatchManualIn, cu: OperatorDep) -> dict:
    return delivery.void(cu, batch_no, body.reason, body.basis)


@router.post("/resume", summary="继续推送：先用原批次号、原内容重推未确认或失败的批次，再认领后续的号")
def push_resume(body: ResumeIn, cu: OperatorDep) -> dict:
    return delivery.resume(cu, body.factory_code, body.key)


@router.get("/remaining", summary="剩余推送次数")
def push_remaining(cu: OperatorDep, factory_code: str, key: str) -> dict:
    cu.check_read(factory_code)
    return {**delivery.remaining_pushes(factory_code, key), "task": delivery.task_state(factory_code, key)}


@router.get("/alerts", summary="交付告警：疑似重复交付（冲突）、版本过期、自检问题")
def alert_list(cu: OperatorDep, status: str | None = None, page: OptInt = None, page_size: OptInt = None) -> dict:
    return delivery.list_alerts(cu, status, page_of(page, page_size))


@router.post("/alerts/{id}/handle", summary="处理交付告警：RESEND 以本系统为准重推 / CLOSE 关闭；note 必填")
def alert_handle(id: int, body: DeliveryAlertIn, cu: OperatorDep) -> dict:
    return delivery.handle_alert(cu, id, body.action, body.note)
