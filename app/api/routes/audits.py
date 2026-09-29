"""操作痕迹：绑厂账户只看本厂相关的痕迹。"""

from fastapi import APIRouter, Query

from app.api.deps import UserDep, download, page_of
from app.schemas.common import OptDate, OptInt
from app.services import audit_query, files

router = APIRouter(prefix="/audits", tags=["audit"])


@router.get("")
def audit_list(
    cu: UserDep,
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
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    f = audit_query.Filter(date_from, date_to, action, operator, factory_code, pi, source, result, batch_no, keyword)
    return audit_query.list_audits(cu, f, page_of(page, page_size))


@router.get("/export")
def audit_export(
    cu: UserDep,
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
    format: str = "xlsx",
):
    f = audit_query.Filter(date_from, date_to, action, operator, factory_code, pi, source, result, batch_no, keyword)
    audit_query.check_exportable(cu, f)
    fmt = files.fmt_of(format)
    return download("audit_export", fmt, files.content_type(fmt), audit_query.export(cu, f, fmt))


@router.get("/meta")
def audit_meta(cu: UserDep) -> dict:
    return audit_query.meta()
