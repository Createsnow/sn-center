"""金蝶生产订单快照：按物料明细一行，不存单据头与内码。

全量同步 = 先完整拉取，再在一个事务里清空并写入；按单据编号同步只替换该单。金蝶失败时快照不动。
"""

from __future__ import annotations

from dataclasses import dataclass

import structlog

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, factories, k3

log = structlog.get_logger()

PRD_MO_FIELDS = (
    "id",
    "bill_no",
    "line_seq",
    "customer_number",
    "po",
    "pi",
    "material_number",
    "material_name",
    "qty",
    "status",
    "demand_bill_no",
    "prd_org_name",
    "synced_at",
)

_INSERT = (
    "INSERT INTO sn_prd_mo(bill_no, line_seq, customer_number, po, pi, material_number, material_name, qty, status, "
    "demand_bill_no, prd_org_name, synced_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
)


def prd_mo_json(r: dict) -> dict:
    return util.jrow(r, PRD_MO_FIELDS)


# ------------------------------------------------------------------ 同步


def _to_args(rows: list[k3.Row], ts) -> list[tuple]:
    """保留金蝶返回顺序作为单内物料行顺序（号段按此切分）。"""
    seq: dict[str, int] = {}
    out = []
    for r in rows:
        seq[r.bill_no] = seq.get(r.bill_no, 0) + 1
        out.append(
            (
                r.bill_no,
                seq[r.bill_no],
                r.customer_number,
                r.po,
                r.pi,
                r.material_number,
                util.cut(r.material_name, 255),
                r.qty,
                r.status,
                r.demand_bill_no,
                r.prd_org_name,
                ts,
            )
        )
    return out


def _batch_insert(args: list[tuple]) -> None:
    for i in range(0, len(args), 1000):
        db.exec_many(_INSERT, args[i : i + 1000])


def sync_all(cu: CurrentUser) -> dict:
    rows = k3.fetch_all()
    try:
        factories.sync_from_k3(cu)
    except Exception as e:  # noqa: BLE001
        # 工厂同步失败不影响订单快照，页面会提示未建档的生产组织
        log.warning("factory_sync_during_order_sync_failed", err=str(e))
    ts = util.now()
    args = _to_args(rows, ts)
    bills = list(dict.fromkeys(r.bill_no for r in rows))
    with db.tx():
        db.exec("DELETE FROM sn_prd_mo")
        _batch_insert(args)
        audit.record(cu, audit.entry(audit.ORDER_SYNC).count(len(bills)).info(f"mode=all rows={len(rows)}"))
    return {
        "mode": "all",
        "bill_no": "",
        "rows": len(rows),
        "bills": len(bills),
        "synced_at": util.fmt(ts),
        "removed": False,
        "unmapped_orgs": unmapped_orgs(),
    }


def sync_bill(cu: CurrentUser, bill_no: str | None) -> dict:
    no = util.trim(bill_no)
    if not no:
        raise biz(ErrorCode.ORDER_BILL_REQUIRED)
    rows = [r for r in k3.fetch_bill(no) if r.bill_no == no]
    ts = util.now()
    args = _to_args(rows, ts)
    with db.tx():
        db.exec("DELETE FROM sn_prd_mo WHERE bill_no = %s", no)
        _batch_insert(args)
        audit.record(
            cu, audit.entry(audit.ORDER_SYNC).bill(no).count(1 if rows else 0).info(f"mode=one rows={len(rows)}")
        )
    return {
        "mode": "one",
        "bill_no": no,
        "rows": len(rows),
        "bills": 1 if rows else 0,
        "synced_at": util.fmt(ts),
        "removed": not rows,
        "unmapped_orgs": unmapped_orgs(),
    }


def unmapped_orgs() -> list[str]:
    """快照里出现、但工厂表没有同名工厂的生产组织。"""
    return db.column(
        "SELECT DISTINCT m.prd_org_name FROM sn_prd_mo m LEFT JOIN sn_factory f ON f.factory_name = m.prd_org_name "
        "WHERE f.factory_code IS NULL AND m.customer_number <> '' ORDER BY m.prd_org_name"
    )


# ------------------------------------------------------------------ 查询


def meta() -> dict:
    m = db.one(
        "SELECT MAX(synced_at) ts, COUNT(DISTINCT CASE WHEN customer_number <> '' THEN bill_no END) bills, "
        "COUNT(*) row_count, COUNT(DISTINCT CASE WHEN customer_number = '' THEN bill_no END) excluded FROM sn_prd_mo"
    )
    return {
        "synced_at": util.fmt(m["ts"]),
        "bills": int(m["bills"]),
        "rows": int(m["row_count"]),
        "excluded_empty_customer": int(m["excluded"]),
        "unmapped_orgs": unmapped_orgs(),
    }


def _bill_gens(bills: list[str]) -> dict[str, dict]:
    if not bills:
        return {}
    return {
        g["bill_no"]: g for g in db.all(f"SELECT * FROM sn_bill_gen WHERE bill_no IN ({marks(len(bills))})", *bills)
    }


def list_bills(
    cu: CurrentUser, q: str | None, customer: str | None, pi: str | None, factory: str | None, page: util.Page
) -> dict:
    """订单页左表。客户编码为空的单据不显示；绑厂查询员只看本厂。"""
    where = " WHERE m.customer_number <> ''"
    args: list = []
    text = util.trim(q)
    if text:
        where += " AND (m.bill_no LIKE %s OR m.pi LIKE %s OR m.po LIKE %s OR m.material_number LIKE %s)"
        like = util.like_any(text)
        args += [like, like, like, like]
    if not util.blank(customer):
        where += " AND m.customer_number = %s"
        args.append(util.trim(customer))
    if not util.blank(pi):
        where += " AND m.pi = %s"
        args.append(util.trim(pi))
    fac = cu.read_factory(factory)
    if fac is not None:
        where += " AND m.prd_org_name = (SELECT factory_name FROM sn_factory WHERE factory_code = %s)"
        args.append(fac)
    total = db.count("SELECT COUNT(DISTINCT m.bill_no) FROM sn_prd_mo m" + where, *args)
    rows = db.all(
        "SELECT m.bill_no, MAX(m.customer_number) customer_number, MAX(m.po) po, MAX(m.pi) pi, "
        "MAX(m.prd_org_name) prd_org_name, GROUP_CONCAT(DISTINCT m.status ORDER BY m.status) statuses, "
        "SUM(FLOOR(m.qty)) total_qty, COUNT(*) line_count, MAX(m.synced_at) synced_at FROM sn_prd_mo m"
        + where
        + " GROUP BY m.bill_no ORDER BY m.bill_no DESC LIMIT %s, %s",
        *args,
        page.offset,
        page.size,
    )
    gen = _bill_gens([r["bill_no"] for r in rows])
    codes = factories.code_by_name()
    items = []
    for r in rows:
        g = gen.get(r["bill_no"])
        total_qty = int(r["total_qty"])
        generated = 0 if g is None else g["generated_qty"]
        items.append(
            {
                "bill_no": r["bill_no"],
                "customer_number": r["customer_number"],
                "po": r["po"],
                "pi": r["pi"],
                "prd_org_name": r["prd_org_name"],
                "factory_code": codes.get(r["prd_org_name"]),
                "statuses": r["statuses"],
                "total_qty": total_qty,
                "line_count": int(r["line_count"]),
                "generated_qty": generated,
                "allocated_qty": 0 if g is None else g["allocated_qty"],
                "quota": max(0, total_qty - generated),
                "synced_at": util.fmt(r["synced_at"]),
            }
        )
    return util.page_result(items, total, page)


def lines(bill_no: str | None) -> list[dict]:
    return db.all("SELECT * FROM sn_prd_mo WHERE bill_no = %s ORDER BY line_seq ASC", util.trim(bill_no))


def _own_org(cu: CurrentUser) -> str | None:
    """绑厂账户本厂的生产组织名称；未绑厂返回 None（可看全部）。"""
    if not cu.bound:
        return None
    f = factories.get(cu.factory_code)
    return "" if f is None else f["factory_name"]


def lines_of(cu: CurrentUser, bill_no: str | None) -> list[dict]:
    """订单页右表：绑厂查询员只能看本厂（生产组织 = 本厂）的订单，与左表范围一致。"""
    ls = lines(bill_no)
    org = _own_org(cu)
    if org is not None and ls and ls[0]["prd_org_name"] != org:
        raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=cu.factory_code)
    return ls


# ------------------------------------------------------------------ 生成用的订单视图


@dataclass(frozen=True)
class Bill:
    """一张生产订单（快照中）：表头取自各行，物料行按顺序。"""

    bill_no: str
    customer_code: str
    po: str
    pi: str
    prd_org_name: str
    factory_code: str | None
    lines: list[dict]
    total_qty: int

    def as_json(self) -> dict:
        return {
            "bill_no": self.bill_no,
            "customer_code": self.customer_code,
            "po": self.po,
            "pi": self.pi,
            "prd_org_name": self.prd_org_name,
            "factory_code": self.factory_code,
            "lines": [prd_mo_json(x) for x in self.lines],
            "total_qty": self.total_qty,
        }


def bill(bill_no: str | None) -> Bill:
    """快照中的订单；不在快照 = 已不是计划 / 计划确认。"""
    ls = lines(bill_no)
    if not ls:
        raise biz(ErrorCode.ORDER_NOT_FOUND, bill_no=util.trim(bill_no))
    from app.services.sn_items import sn_qty

    h = ls[0]
    total = sum(sn_qty(x) for x in ls)
    code = factories.code_by_name().get(h["prd_org_name"])
    return Bill(h["bill_no"], h["customer_number"], h["po"], h["pi"], h["prd_org_name"], code, ls, total)


def pi_summary(factory_code: str | None, prd_org_name: str, pi: str) -> list[dict]:
    """「工厂 + PI」汇总：快照里同厂同 PI 的订单，加上已生成过但已离开快照的订单。"""
    out: dict[str, dict] = {}
    for r in db.all(
        "SELECT bill_no, SUM(FLOOR(qty)) total_qty FROM sn_prd_mo WHERE pi = %s AND prd_org_name = %s "
        "AND customer_number <> '' GROUP BY bill_no ORDER BY bill_no",
        pi,
        prd_org_name,
    ):
        total = int(r["total_qty"])
        out[r["bill_no"]] = {
            "bill_no": r["bill_no"],
            "in_snapshot": True,
            "total_qty": total,
            "generated_qty": 0,
            "allocated_qty": 0,
            "quota": total,
        }
    gens = (
        []
        if factory_code is None
        else db.all(
            "SELECT * FROM sn_bill_gen WHERE pi_no = %s AND factory_code = %s ORDER BY bill_no ASC", pi, factory_code
        )
    )
    for g in gens:
        cur = out.get(g["bill_no"])
        total = 0 if cur is None else cur["total_qty"]
        out[g["bill_no"]] = {
            "bill_no": g["bill_no"],
            "in_snapshot": cur is not None,
            "total_qty": total,
            "generated_qty": g["generated_qty"],
            "allocated_qty": g["allocated_qty"],
            "quota": 0 if cur is None else max(0, total - g["generated_qty"]),
        }
    return list(out.values())


def pi_in_snapshot(pi: str) -> bool:
    return db.exists("SELECT EXISTS(SELECT 1 FROM sn_prd_mo WHERE pi = %s AND customer_number <> '')", pi)


def targets(q: str | None, limit: int) -> list[dict]:
    """转厂目标候选：快照中的「生产组织 + 客户 + PI + 物料」。"""
    text = util.trim(q)
    args: list = []
    where = " WHERE m.customer_number <> '' AND m.pi <> ''"
    if text:
        where += " AND (m.pi LIKE %s OR m.bill_no LIKE %s OR m.material_number LIKE %s OR m.customer_number LIKE %s)"
        like = util.like_any(text)
        args += [like, like, like, like]
    args.append(min(max(limit, 1), 200))
    rows = db.all(
        "SELECT f.factory_code, m.prd_org_name, m.customer_number, m.pi, m.material_number, "
        "MAX(m.material_name) material_name FROM sn_prd_mo m LEFT JOIN sn_factory f ON f.factory_name = m.prd_org_name"
        + where
        + " GROUP BY f.factory_code, m.prd_org_name, m.customer_number, m.pi, m.material_number "
        "ORDER BY m.pi, m.material_number LIMIT %s",
        *args,
    )
    return [
        {
            "factory_code": r["factory_code"],
            "prd_org_name": r["prd_org_name"],
            "customer_code": r["customer_number"],
            "pi": r["pi"],
            "material_code": r["material_number"],
            "material_name": r["material_name"],
        }
        for r in rows
    ]


def customers(cu: CurrentUser, q: str | None) -> list[str]:
    """快照中出现过的客户编码（规则绑定选择用）；绑厂查询员只看本厂订单的客户。"""
    sql = "SELECT DISTINCT customer_number FROM sn_prd_mo WHERE customer_number <> ''"
    args: list = []
    text = util.trim(q)
    if text:
        sql += " AND customer_number LIKE %s"
        args.append(util.like_any(text))
    org = _own_org(cu)
    if org is not None:
        sql += " AND prd_org_name = %s"
        args.append(org)
    return db.column(sql + " ORDER BY customer_number LIMIT 200", *args)


def customer_of_pi(pi: str) -> str | None:
    """PI 在快照中的客户编码（取第一个）。"""
    v = db.column(
        "SELECT customer_number FROM sn_prd_mo WHERE pi = %s AND customer_number <> '' ORDER BY bill_no LIMIT 1", pi
    )
    return v[0] if v else None
