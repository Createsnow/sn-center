"""SN 查询与导出（F-18）。只读；按账户可看范围过滤；按条件分页读取，不把全部号装入内存。

SN 明细：按生产组织、客户、PI、单据编号、物料、米数、状态、完整 SN 精确筛选，另可按流水范围查
（须填 PI；起止可填流水号或完整 SN，两端都含；只查本 PI 自己生成的流水；附小计）。
页面只列号本身的信息（流水、PI、单据、物料、米数、客户、生产组织、状态、生成 / 分配时间），
不列交付方式、交付批次、推送类型等交付业务信息（交付批次在「交付批次」页签看；导出列同 SN 导出，不变）。
列表总数超过 10 万时只统计到 10 万并注明；导出默认最多 100 万行，超过只导出前 100 万行并提示。
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass

from app.core import encoding as codec
from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import factories, files, rules, sn_sheet

COUNT_CAP = 100_000

COLS = (
    "i.id, i.sn, i.rule_version_id, i.seq_dec, i.seq_pi_no, i.pi_no, i.bill_no, i.bill_seq, i.material_code, "
    "i.length_text, i.customer_code, i.factory_code, i.status, i.suspend, i.created_at, i.attach_no"
)


@dataclass(frozen=True)
class Filter:
    factory_code: str | None = None
    customer_code: str | None = None
    pi_no: str | None = None
    bill_no: str | None = None
    material_code: str | None = None
    length: str | None = None
    status: str | None = None
    sn: str | None = None
    pi_all: bool = False
    seq_from: str | None = None
    seq_to: str | None = None

    @property
    def has_range(self) -> bool:
        return not (util.blank(self.seq_from) and util.blank(self.seq_to))


@dataclass(frozen=True)
class Scope:
    """可读范围：factories = None 为全部；all_factories_of_pi = 只读查看同一 PI 在各组织的号。"""

    factories: tuple[str, ...] | None
    all_factories_of_pi: bool

    def sql(self, col: str = "i.factory_code") -> tuple[str, list]:
        return factories.scope_sql(self.factories, col)


def read_scope(cu: CurrentUser, factory: str | None, pi: str | None, pi_all: bool) -> Scope:
    """普通操作员切换为只读查看同一 PI 各组织的号：只限授权组织也有号的 PI（Q2）。"""
    if pi_all and cu.is_operator:
        if util.blank(pi):
            raise biz(ErrorCode.QUERY_PI_REQUIRED)
        fsql, fargs = factories.scope_sql(cu.factories)
        if not db.exists("SELECT EXISTS(SELECT 1 FROM sn_item WHERE pi_no = %s" + fsql + ")", pi.strip(), *fargs):
            raise biz(ErrorCode.QUERY_PI_NOT_OWNED, pi=pi.strip())
        return Scope(None, True)
    return Scope(cu.read_factories(factory), False)


def _seq_of(text: str, specs: list[codec.Spec]) -> int | None:
    """流水号（可省略左侧补位）或完整 SN → 十进制流水；按 PI 用过的规则版本逐个试。"""
    for s in specs:
        if len(text) <= s.seq_len and all(c in s.charset for c in text):
            n = codec.decode_seq(text, s)
            if n is not None:
                return n
        hit = codec.parse(text, s)
        if hit is not None:
            return hit
    return None


def seq_range(f: Filter) -> tuple[str, int | None, int | None] | None:
    if not f.has_range:
        return None
    if util.blank(f.pi_no):
        raise biz(ErrorCode.QUERY_RANGE_PI_REQUIRED)
    pi = f.pi_no.strip()
    versions = db.column(
        "SELECT DISTINCT rule_version_id FROM sn_item WHERE seq_pi_no = %s ORDER BY rule_version_id DESC", pi
    )
    specs = [s for s in (rules.cached_spec(v) for v in versions) if s is not None]
    bounds: list[int | None] = []
    for v in (f.seq_from, f.seq_to):
        if util.blank(v):
            bounds.append(None)
            continue
        n = _seq_of(v.strip(), specs)
        if n is None:
            raise biz(ErrorCode.QUERY_RANGE_INVALID, value=v.strip(), pi=pi)
        bounds.append(n)
    lo, hi = bounds
    if lo is not None and hi is not None and lo > hi:
        raise biz(ErrorCode.QUERY_RANGE_REVERSED, start=f.seq_from.strip(), end=f.seq_to.strip())
    return pi, lo, hi


def _where(cu: CurrentUser, f: Filter) -> tuple[str, list, tuple | None]:
    sql = " WHERE 1=1"
    args: list = []
    # 生产组织筛选须在授权范围内（不选 = 全部授权的组织）
    sc = read_scope(cu, f.factory_code, f.pi_no, f.pi_all)
    if sc.all_factories_of_pi:
        sql += " AND i.factory_code IS NOT NULL"
    else:
        fsql, fargs = sc.sql()
        sql += fsql
        args += fargs
    for col, v in (
        ("i.customer_code", f.customer_code),
        # 流水范围按流水归属 PI 查（含已改 PI 转走的号），不再按当前 PI 过滤
        ("i.pi_no", None if f.has_range else f.pi_no),
        ("i.bill_no", f.bill_no),
        ("i.length_text", f.length),
        ("i.sn", f.sn),
    ):
        if not util.blank(v):
            sql += f" AND {col} = %s"
            args.append(v.strip())
    if f.material_code is not None:
        sql += " AND i.material_code = %s"
        args.append(f.material_code.strip())
    if not util.blank(f.status):
        st = f.status.strip()
        if st == "SUSPENDED":
            sql += " AND i.suspend IS NOT NULL"
        elif st in util.SN_STATUSES:
            sql += " AND i.status = %s"
            args.append(st)
    rg = seq_range(f)
    if rg is not None:
        sql += " AND i.seq_pi_no = %s"
        args.append(rg[0])
        for op, n in ((">=", rg[1]), ("<=", rg[2])):
            if n is not None:
                sql += f" AND i.seq_dec {op} %s"
                args.append(n)
    return sql, args, rg


def _bill_pis(bills: list[str]) -> dict[str, str]:
    """单据当前的 PI（快照，离开快照的取台账）：与号上 PI 不同的标「已改 PI」。"""
    if not bills:
        return {}
    ub = sorted(set(bills))
    out = {
        r["bill_no"]: r["pi"]
        for r in db.all(f"SELECT bill_no, pi FROM sn_bill_ledger WHERE bill_no IN ({marks(len(ub))})", *ub)
    }
    for r in db.all(f"SELECT DISTINCT bill_no, pi FROM sn_prd_mo WHERE bill_no IN ({marks(len(ub))})", *ub):
        out[r["bill_no"]] = r["pi"]
    return out


def view(r: dict, bill_pi: dict[str, str]) -> dict:
    return {
        "id": r["id"],
        "sn": r["sn"],
        "seq_text": rules.seq_text(r["rule_version_id"], r["seq_dec"]),
        "seq_dec": str(r["seq_dec"]),
        "seq_pi_no": r["seq_pi_no"],
        "pi_no": r["pi_no"],
        "bill_no": r["bill_no"],
        "bill_seq": r["bill_seq"],
        "material_code": r["material_code"],
        "attach_no": r["attach_no"],
        "length": r["length_text"],
        "customer_code": r["customer_code"],
        "factory_code": r["factory_code"],
        "status": r["status"],
        "suspend": r["suspend"],
        "pi_changed": bill_pi.get(r["bill_no"], r["pi_no"]) != r["pi_no"],
        "transferred_in": r["seq_pi_no"] != r["pi_no"],
        "created_at": util.fmt(r["created_at"]),
    }


def _range_summary(sql: str, args: list, rg: tuple) -> dict:
    rows = db.all(f"SELECT i.status, COUNT(*) n FROM sn_item i{sql} GROUP BY i.status", *args)
    by_status = {r["status"]: int(r["n"]) for r in rows}
    found = sum(by_status.values())
    expected = None if rg[1] is None or rg[2] is None else rg[2] - rg[1] + 1
    return {
        "found": found,
        "expected": expected,
        "missing": None if expected is None else expected - found,
        "by_status": by_status,
    }


def list_items(cu: CurrentUser, f: Filter, page: util.Page) -> dict:
    sql, args, rg = _where(cu, f)
    total = db.count(f"SELECT COUNT(*) FROM (SELECT 1 FROM sn_item i{sql} LIMIT %s) c", *args, COUNT_CAP + 1)
    if rg is not None:
        order = " ORDER BY i.seq_dec, i.id"
    elif not util.blank(f.pi_no):
        order = " ORDER BY i.bill_no, i.bill_seq, i.seq_pi_no, i.seq_dec, i.id"
    else:
        order = " ORDER BY i.id DESC"
    rows = db.all(f"SELECT {COLS} FROM sn_item i{sql}{order} LIMIT %s, %s", *args, page.offset, page.size)
    bp = _bill_pis([r["bill_no"] for r in rows])
    out = util.page_capped([view(r, bp) for r in rows], total, page, COUNT_CAP)
    if rg is not None:
        out["range"] = _range_summary(sql, args, rg)
    return out


def export_check(cu: CurrentUser, f: Filter) -> dict:
    """导出前：行数是否超过上限（超过只导出前 N 行）。"""
    sql, args, _ = _where(cu, f)
    limit = settings.sn_export_limit
    total = db.count(f"SELECT COUNT(*) FROM (SELECT 1 FROM sn_item i{sql} LIMIT %s) c", *args, limit + 1)
    return {"limit": limit, "truncated": total > limit, "rows": min(total, limit)}


#: SN 查询导出的列（见 sn_sheet；SN 分配页「已分配」下载另有列，见 sn_export.FILE_HEADERS）
EXPORT_HEADERS = sn_sheet.HEADERS


def _export_rows(cu: CurrentUser, f: Filter) -> Iterator[list]:
    sql, args, _ = _where(cu, f)
    limit = settings.sn_export_limit
    last_id = 0
    no = 0
    while no < limit:
        page = db.all(
            f"SELECT {sn_sheet.COLS} FROM {sn_sheet.FROM}{sql} AND i.id > %s ORDER BY i.id LIMIT %s",
            *args,
            last_id,
            min(5000, limit - no),
        )
        if not page:
            return
        for r in page:
            no += 1
            last_id = r["id"]
            yield sn_sheet.row(no, r)


def export(cu: CurrentUser, f: Filter) -> Iterator[bytes]:
    return files.xlsx_bytes("SN", EXPORT_HEADERS, _export_rows(cu, f))
