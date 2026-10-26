"""痕迹查询与导出（F-21）。绑定生产组织的账户只看本组织相关的痕迹。
时间范围默认最近 30 天（按账户显示时区的自然日），便于分区裁剪。

查询的时间范围覆盖到已归档的痕迹时，同时查归档表（sn_audit_archive），结果标 archived，页面照常可查、可导出。
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import factories, files

HEADERS = [
    "时间",
    "工号",
    "姓名",
    "角色",
    "来源",
    "动作",
    "结果",
    "生产组织",
    "PI",
    "客户",
    "物料",
    "单据编号",
    "起始SN",
    "结束SN",
    "数量",
    "操作前状态",
    "操作后状态",
    "批次号",
    "请求号",
    "转单 / 转组织单号",
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
    bill_no: str | None = None


COLS = ", ".join(ROW_FIELDS)


def meta() -> dict:
    d = settings.audit_retention_days
    a = db.one("SELECT MIN(created_at) lo, MAX(created_at) hi FROM sn_audit_archive")
    return {
        "retention_days": d,
        "permanent": d == 0,
        "archive_retention_days": settings.audit_archive_retention_days,
        "archive_from": util.fmt(a["lo"]) if a else None,
        "archive_to": util.fmt(a["hi"]) if a else None,
        "export_limit": settings.audit_export_limit,
    }


def _with_archive(args: list) -> bool:
    """查询起点早于归档表里最新一条痕迹时，归档表里可能有命中的痕迹，要一起查。"""
    hi = db.scalar("SELECT MAX(created_at) FROM sn_audit_archive")
    return hi is not None and hi >= args[0]


def _source(sql: str, args: list, extra: str = "", extra_args: tuple = ()) -> tuple[str, list]:
    """在线表（加上需要时的归档表）按同一条件取痕迹，archived = 是否来自归档表。"""
    main = f"SELECT {COLS}, 0 archived FROM sn_audit{sql}{extra}"
    if not _with_archive(args):
        return main, [*args, *extra_args]
    arc = f"SELECT {COLS}, 1 archived FROM sn_audit_archive{sql}{extra}"
    return f"{main} UNION ALL {arc}", [*args, *extra_args, *args, *extra_args]


def _where(cu: CurrentUser, f: Filter) -> tuple[str, list]:
    # 日期按账户显示时区的自然日，换算为 UTC 区间
    off = timedelta(minutes=factories.tz_offset(cu.factories))
    to = f.date_to or (util.now() + off).date()
    frm = f.date_from or to - timedelta(days=30)
    if frm > to:
        raise biz(ErrorCode.VALIDATION, detail="from > to")
    sql = " WHERE created_at >= %s AND created_at < %s"
    args: list = [datetime.combine(frm, time()) - off, datetime.combine(to + timedelta(days=1), time()) - off]
    facs = cu.read_factories(f.factory_code)
    if facs is not None and cu.bound:
        # 绑定的账户：本组织的痕迹，加上转入本组织的转组织痕迹（记在转出组织名下）
        own, oa = factories.scope_sql(facs)
        into, ia = factories.scope_sql(facs, "to_factory")
        sql += (
            f" AND ((1=1{own}) OR (transfer_no IS NOT NULL AND transfer_no IN "
            f"(SELECT transfer_no FROM sn_transfer WHERE 1=1{into})))"
        )
        args += [*oa, *ia]
    else:
        fsql, fa = factories.scope_sql(facs)
        sql += fsql
        args += fa
    for col, v in (
        ("action", f.action),
        ("operator", f.operator),
        ("pi_no", f.pi_no),
        ("source", f.source),
        ("result", f.result),
        ("batch_no", f.batch_no),
        ("bill_no", f.bill_no),
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


def _count(sql: str, args: list) -> int:
    n = db.count("SELECT COUNT(*) FROM sn_audit" + sql, *args)
    if _with_archive(args):
        n += db.count("SELECT COUNT(*) FROM sn_audit_archive" + sql, *args)
    return n


def list_audits(cu: CurrentUser, f: Filter, page: util.Page) -> dict:
    sql, args = _where(cu, f)
    total = _count(sql, args)
    src, sargs = _source(sql, args)
    sql_page = f"SELECT * FROM ({src}) u ORDER BY created_at DESC, id DESC LIMIT %s, %s"
    rows = db.all(sql_page, *sargs, page.offset, page.size)
    return util.page_result([{**util.jrow(r, ROW_FIELDS), "archived": bool(r["archived"])} for r in rows], total, page)


def export_check(cu: CurrentUser, f: Filter) -> dict:
    """导出前：超过上限（默认 20 万条）只导出前 N 条并提示。"""
    sql, args = _where(cu, f)
    n = _count(sql, args)
    limit = settings.audit_export_limit
    return {"limit": limit, "truncated": n > limit, "rows": min(n, limit)}


def _rows(cu: CurrentUser, f: Filter) -> Iterator[list]:
    sql, args = _where(cu, f)
    last_id = 2**63 - 1
    left = settings.audit_export_limit
    while left > 0:
        # 归档的痕迹保留原 id，与在线表同一序列，可按 id 游标倒序连续读
        src, sargs = _source(sql, args, " AND id < %s", (last_id,))
        page = db.all(f"SELECT * FROM ({src}) u ORDER BY id DESC LIMIT 5000", *sargs)
        if not page:
            return
        for r in page:
            if left <= 0:
                return
            left -= 1
            yield [util.fmt(r["created_at"])] + [r[c] for c in ROW_FIELDS[2:]]
            last_id = r["id"]


def export(cu: CurrentUser, f: Filter) -> Iterator[bytes]:
    return files.xlsx_bytes("audit", HEADERS, _rows(cu, f))
