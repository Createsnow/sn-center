"""SN 查询 / 导出 / 只读拉取。

按条件分页读取，不把全部号装入内存：列表总数封顶计数；导出按 id 游标分块读、流式写文件。
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import acquire, files, sn_items

COUNT_CAP = 100_000


@dataclass(frozen=True)
class Filter:
    """查询条件。pi_all = 厂区账户切换为只读，查看同一 PI 已分到各厂的号。"""

    factory_code: str | None = None
    customer_code: str | None = None
    pi_no: str | None = None
    material_code: str | None = None
    status: str | None = None
    sn: str | None = None
    bill_no: str | None = None
    batch_no: str | None = None
    pi_all: bool = False


def _where(cu: CurrentUser, f: Filter) -> tuple[str, list, bool]:
    sql = " WHERE 1=1"
    args: list = []
    sc = acquire.read_scope(cu, f.factory_code, f.pi_no, f.pi_all)
    if sc.all_factories_of_pi:
        # 未分配（工厂为空）的号不在只读范围内
        sql += " AND factory_code IS NOT NULL"
    elif sc.factory is not None:
        sql += " AND factory_code = %s"
        args.append(sc.factory)
    if not util.blank(f.customer_code):
        sql += " AND customer_code = %s"
        args.append(f.customer_code.strip())
    if not util.blank(f.pi_no):
        sql += " AND pi_no = %s"
        args.append(f.pi_no.strip())
    if f.material_code is not None and not (f.material_code == "" and util.blank(f.pi_no)):
        sql += " AND material_code = %s"
        args.append(f.material_code.strip())
    if not util.blank(f.status) and f.status.strip() in util.SN_STATUSES:
        sql += " AND status = %s"
        args.append(f.status.strip())
    for col, v in (("sn", f.sn), ("bill_no", f.bill_no), ("batch_no", f.batch_no)):
        if not util.blank(v):
            sql += f" AND {col} = %s"
            args.append(v.strip())
    return sql, args, not util.blank(f.pi_no)


def list_items(cu: CurrentUser, f: Filter, page: util.Page) -> dict:
    sql, args, has_pi = _where(cu, f)
    total = db.count(f"SELECT COUNT(*) FROM (SELECT 1 FROM sn_item{sql} LIMIT %s) c", *args, COUNT_CAP + 1)
    order = " ORDER BY material_code, seq_pi_no, seq_dec, id" if has_pi else " ORDER BY id DESC"
    rows = db.all(f"SELECT {sn_items.COLS} FROM sn_item{sql}{order} LIMIT %s, %s", *args, page.offset, page.size)
    return util.page_capped([sn_items.view(r) for r in rows], total, page, COUNT_CAP)


def check_exportable(cu: CurrentUser, f: Filter) -> None:
    """导出前的行数检查（让页面在下载前就能提示超限）。"""
    sql, args, _ = _where(cu, f)
    limit = settings.sn_export_limit
    total = db.count(f"SELECT COUNT(*) FROM (SELECT 1 FROM sn_item{sql} LIMIT %s) c", *args, limit + 1)
    if total > limit:
        raise biz(ErrorCode.EXPORT_TOO_LARGE, max=limit)


def _export_rows(cu: CurrentUser, f: Filter) -> Iterator[list]:
    sql, args, _ = _where(cu, f)
    last_id = 0
    no = 0
    while True:
        page = db.all(f"SELECT {sn_items.COLS} FROM sn_item{sql} AND id > %s ORDER BY id LIMIT 5000", *args, last_id)
        if not page:
            return
        for r in page:
            no += 1
            yield sn_items.file_row(no, sn_items.view(r))
            last_id = r["id"]


def export(cu: CurrentUser, f: Filter, fmt: str) -> Iterator[bytes]:
    return files.stream(fmt, "SN", sn_items.FILE_HEADERS, _export_rows(cu, f))
