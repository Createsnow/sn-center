"""痕迹查询与导出。绑厂账户只看本厂相关的痕迹。时间范围默认最近 30 天，便于分区裁剪。"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import files

HEADERS = [
    "时间",
    "工号",
    "姓名",
    "角色",
    "来源",
    "动作",
    "结果",
    "工厂",
    "PI",
    "客户",
    "物料",
    "来源订单",
    "起始SN",
    "结束SN",
    "数量",
    "操作前状态",
    "操作后状态",
    "批次号",
    "请求号",
    "转厂单号",
    "原因",
    "失败原因",
    "请求ID",
    "详情",
]
ROW_FIELDS = (
    "id",
    "created_at",
    "operator",
    "operator_name",
    "role",
    "source",
    "action",
    "result",
    "factory_code",
    "pi_no",
    "customer_code",
    "material_code",
    "bill_no",
    "start_sn",
    "end_sn",
    "qty",
    "before_status",
    "after_status",
    "batch_no",
    "request_no",
    "transfer_no",
    "reason",
    "error_msg",
    "trace_id",
    "detail",
)


@dataclass(frozen=True)
class Filter:
    date_from: date | None = None
    date_to: date | None = None
    action: str | None = None
    operator: str | None = None
    factory_code: str | None = None
    pi_no: str | None = None
    source: str | None = None
    result: str | None = None
    batch_no: str | None = None
    keyword: str | None = None


def meta() -> dict:
    d = settings.audit_retention_days
    return {"retention_days": d, "permanent": d == 0, "export_limit": settings.audit_export_limit}


def _where(cu: CurrentUser, f: Filter) -> tuple[str, list]:
    to = f.date_to or util.today()
    frm = f.date_from or to - timedelta(days=30)
    if frm > to:
        raise biz(ErrorCode.VALIDATION, detail="from > to")
    sql = " WHERE created_at >= %s AND created_at < %s"
    args: list = [datetime.combine(frm, time()), datetime.combine(to + timedelta(days=1), time())]
    fac = cu.read_factory(f.factory_code)
    if fac is not None and cu.bound:
        # 绑厂账户：本厂的痕迹，加上转入本厂的转厂痕迹（转厂痕迹记在转出厂名下）
        sql += (
            " AND (factory_code = %s OR (transfer_no IS NOT NULL AND transfer_no IN "
            "(SELECT transfer_no FROM sn_transfer WHERE to_factory = %s)))"
        )
        args += [fac, fac]
    elif fac is not None:
        sql += " AND factory_code = %s"
        args.append(fac)
    for col, v in (
        ("action", f.action),
        ("operator", f.operator),
        ("pi_no", f.pi_no),
        ("source", f.source),
        ("result", f.result),
        ("batch_no", f.batch_no),
    ):
        if not util.blank(v):
            sql += f" AND {col} = %s"
            args.append(v.strip())
    if not util.blank(f.keyword):
        k = util.like_any(f.keyword.strip())
        sql += (
            " AND (start_sn LIKE %s OR end_sn LIKE %s OR request_no LIKE %s OR transfer_no LIKE %s "
            "OR bill_no LIKE %s OR detail LIKE %s)"
        )
        args += [k] * 6
    return sql, args


def list_audits(cu: CurrentUser, f: Filter, page: util.Page) -> dict:
    sql, args = _where(cu, f)
    total = db.count("SELECT COUNT(*) FROM sn_audit" + sql, *args)
    rows = db.all(
        f"SELECT * FROM sn_audit{sql} ORDER BY created_at DESC, id DESC LIMIT %s, %s", *args, page.offset, page.size
    )
    return util.page_result([util.jrow(r, ROW_FIELDS) for r in rows], total, page)


def check_exportable(cu: CurrentUser, f: Filter) -> None:
    sql, args = _where(cu, f)
    if db.count("SELECT COUNT(*) FROM sn_audit" + sql, *args) > settings.audit_export_limit:
        raise biz(ErrorCode.EXPORT_TOO_LARGE, max=settings.audit_export_limit)


def _rows(cu: CurrentUser, f: Filter) -> Iterator[list]:
    sql, args = _where(cu, f)
    last_id = 2**63 - 1
    while True:
        page = db.all(f"SELECT * FROM sn_audit{sql} AND id < %s ORDER BY id DESC LIMIT 5000", *args, last_id)
        if not page:
            return
        for r in page:
            yield [util.fmt(r["created_at"])] + [r[c] for c in ROW_FIELDS[2:]]
            last_id = r["id"]


def export(cu: CurrentUser, f: Filter, fmt: str) -> Iterator[bytes]:
    return files.stream(fmt, "audit", HEADERS, _rows(cu, f))
