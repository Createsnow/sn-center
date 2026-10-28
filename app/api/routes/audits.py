"""操作痕迹（F-21，审计报表）：系统管理员、审计员（授权组织）。"""

from fastapi import APIRouter, Query

from app.api.deps import AuditDep, download, page_of
from app.schemas.common import OptDate, OptInt
from app.services import audit_query

router = APIRouter(prefix="/audits", tags=["audit"])


def _f(date_from, date_to, action, operator, factory_code, pi, source, result, batch_no, keyword, bill_no):
    return audit_query.Filter(
        date_from, date_to, action, operator, factory_code, pi, source, result, batch_no, keyword, bill_no
    )


@router.get("")
def audit_list(
    cu: AuditDep,
    date_from: OptDate = Query(None, alias="from"),
    date_to: OptDate = Query(None, alias="to"),
    action: str | None = None,
    operator: str | None = None,
    factory_code: str | None = None,
    pi: str | None = None,
    source: str | None = None,
    result: str | None = None,
    batch_no: str | None = None,
    keyword: str | None = None,
    bill_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    f = _f(date_from, date_to, action, operator, factory_code, pi, source, result, batch_no, keyword, bill_no)
    return audit_query.list_audits(cu, f, page_of(page, page_size))


@router.get("/export-check")
def audit_export_check(
    cu: AuditDep,
    date_from: OptDate = Query(None, alias="from"),
    date_to: OptDate = Query(None, alias="to"),
    action: str | None = None,
    operator: str | None = None,
    factory_code: str | None = None,
    pi: str | None = None,
    source: str | None = None,
    result: str | None = None,
    batch_no: str | None = None,
    keyword: str | None = None,
    bill_no: str | None = None,
) -> dict:
    f = _f(date_from, date_to, action, operator, factory_code, pi, source, result, batch_no, keyword, bill_no)
    return audit_query.export_check(cu, f)


@router.get("/export", summary="导出 xlsx（默认最多 20 万条，超过只导出前 20 万条）")
def audit_export(
    cu: AuditDep,
    date_from: OptDate = Query(None, alias="from"),
    date_to: OptDate = Query(None, alias="to"),
    action: str | None = None,
    operator: str | None = None,
    factory_code: str | None = None,
    pi: str | None = None,
    source: str | None = None,
    result: str | None = None,
    batch_no: str | None = None,
    keyword: str | None = None,
    bill_no: str | None = None,
):
    f = _f(date_from, date_to, action, operator, factory_code, pi, source, result, batch_no, keyword, bill_no)
    return download("audit_export", audit_query.export(cu, f))


@router.get("/meta")
def audit_meta(cu: AuditDep) -> dict:
    return audit_query.meta()
