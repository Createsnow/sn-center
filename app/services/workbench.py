"""SN 生成页与分配页共用的 PI 工作台（F-10），以及生成页详情。

每张 PI 一行：客户、单据数、生产组织、状态、订单总数、已生成（各订单已生成数之和；转组织改 PI 的号仍计入原 PI）、
转入号（挂在本 PI 下、由别的 PI 生成的号）、已分配（含已推送、已拉取）；行上按钮按 F-10 第 3 条的顺序判断。
生成页、分配页各用自己的页签筛选同一份列表；各 PI 的号数统计（item_stats、gen_own）两页共用。
生成详情：单据 → 物料行（规则、附件项次、米数、号段）、生成任务（最近 20 条）、可撤销的号段。
分配详情见 allocation.context。
"""

from __future__ import annotations

import json
from datetime import datetime

from app.core import utils as util
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import changes, factories, generate, orders, rules

#: 列表一次最多取的 PI 数；逐行判断「需先选规则 / 不能生成」只对前 DETAIL_ROWS 行做
LIST_LIMIT = 500
DETAIL_ROWS = 60

#: 状态
GENERATING = "GENERATING"
CANNOT = "CANNOT"
NEED_RULE = "NEED_RULE"
TO_GENERATE = "TO_GENERATE"
PARTIAL = "PARTIAL"
TO_ALLOCATE = "TO_ALLOCATE"
PUSH_INTERRUPTED = "PUSH_INTERRUPTED"
DONE = "DONE"


#: 生成页与订单页同一排序：金蝶修改日期新的在前（取不到的排后），同一时间单据编号大的在前
_BY_MODIFY = "MAX(m.modify_time) IS NULL, MAX(m.modify_time) DESC, last_bill DESC"
#: 快照按 PI 汇总（订单数、已生成、额度、最大单据编号、最新修改日期）
_PI_SUM = (
    "SELECT m.pi, MAX(m.customer_code) customer_code, COUNT(DISTINCT m.bill_no) bills, "
    "GROUP_CONCAT(DISTINCT m.org_code ORDER BY m.org_code) orgs, SUM(FLOOR(m.qty)) total_qty, "
    "SUM(COALESCE(c.gen_count, 0)) gen_sum, SUM(GREATEST(FLOOR(m.qty) - COALESCE(c.own_generated, 0), 0)) quota, "
    "MAX(m.bill_no) last_bill, MAX(m.modify_time) modify_time "
    "FROM sn_prd_mo m LEFT JOIN sn_line_count c ON c.bill_no = m.bill_no AND c.seq = m.seq"
)


def _pi_rows(q: str | None, by_modify: bool = False, facs: tuple[str, ...] | None = None) -> list[dict]:
    """生成页：快照中的 PI。by_modify：按 PI 下单据的最新修改日期排；否则按最大单据编号排。
    facs = 授权范围（None = 全部）：只取范围内的单据，PI 的订单数、额度等也只按这些单据汇总。"""
    where = " WHERE m.pi <> ''"
    args: list = []
    fsql, fargs = factories.scope_sql(facs, "m.org_code")
    where += fsql
    args += fargs
    text = util.trim(q)
    if text:
        like = util.like_any(text)
        where += (
            " AND m.pi IN (SELECT x.pi FROM sn_prd_mo x WHERE x.pi LIKE %s OR x.bill_no LIKE %s OR x.po LIKE %s "
            "OR x.customer_code LIKE %s OR x.material_code LIKE %s)"
        )
        args += [like] * 5
    return db.all(
        _PI_SUM + where + " GROUP BY m.pi ORDER BY " + (_BY_MODIFY if by_modify else "last_bill DESC") + " LIMIT %s",
        *args,
        LIST_LIMIT,
    )


def _to_alloc_pis(facs: tuple[str, ...] | None) -> tuple[str, list]:
    """分配页「待分配」页签的候选 PI：有待分配的号、有未入批的待推送号，或有推送失败 / 重试用尽的批次。"""
    isql, iargs = factories.item_scope_sql(facs)
    bsql, bargs = factories.scope_sql(facs)
    return (
        "SELECT DISTINCT pi_no FROM sn_item WHERE status = 'PENDING_ALLOC'" + isql + " "
        "UNION SELECT DISTINCT pi_no FROM sn_item WHERE status = 'TO_PUSH' AND delivery_batch_no IS NULL "
        "AND suspend IS NULL AND acquire_batch_no IS NULL" + isql + " "
        "UNION SELECT DISTINCT pi_no FROM sn_delivery_batch WHERE batch_type <> 'TRANSFER_OUT' "
        "AND (result = 'FAILED' OR (result = 'UNCONFIRMED' AND push_state = 'EXHAUSTED'))" + bsql,
        [*iargs, *iargs, *bargs],
    )


def _item_pi_rows(q: str | None, tab: str | None, facs: tuple[str, ...] | None = None) -> tuple[list[dict], bool]:
    """分配页：按号取 PI（号现在挂的 PI），不受快照「前 LIST_LIMIT 张」的限制——快照里 PI 再多、
    或单据已移出快照，有号的 PI 都能列出；「待分配」页签只取可能待分配或推送中断的 PI。行的字段同 _pi_rows
    （已不在快照的 PI 订单数、额度为 0，单据、生产组织取号上的）。按最大单据编号排、新的在前，最多 LIST_LIMIT 张。
    facs = 授权范围（None = 全部）：只取范围内的号和单据。返回（行，是否截断）。"""
    isql, iargs = factories.item_scope_sql(facs)
    if util.trim(tab) == TO_ALLOCATE:
        sql, sargs = _to_alloc_pis(facs)
    else:
        sql, sargs = "SELECT DISTINCT pi_no FROM sn_item WHERE 1=1" + isql, iargs
    names = {p for p in db.column(sql, *sargs) if p}
    text = util.trim(q)
    if text and names:
        like = util.like_any(text)
        hit = set(
            db.column(
                "SELECT DISTINCT pi FROM sn_prd_mo WHERE pi LIKE %s OR bill_no LIKE %s OR po LIKE %s "
                "OR customer_code LIKE %s OR material_code LIKE %s "
                "UNION SELECT DISTINCT pi FROM sn_bill_ledger WHERE pi LIKE %s OR bill_no LIKE %s "
                "OR customer_code LIKE %s",
                *[like] * 8,
            )
        )
        names = {p for p in names if p in hit or text in p}
    if not names:
        return [], False
    msql, margs = factories.scope_sql(facs, "m.org_code")
    rows = {
        r["pi"]: r for r in db.all(f"{_PI_SUM} WHERE m.pi IN ({marks(len(names))}){msql} GROUP BY m.pi", *names, *margs)
    }
    gone = sorted(names - set(rows))
    if gone:
        gsql, gargs = factories.item_scope_sql(facs, "i.")
        for r in db.all(
            "SELECT i.pi_no pi, MAX(l.customer_code) customer_code, COUNT(DISTINCT i.bill_no) bills, "
            "GROUP_CONCAT(DISTINCT i.gen_org_code ORDER BY i.gen_org_code) orgs, MAX(i.bill_no) last_bill "
            "FROM sn_item i LEFT JOIN sn_bill_ledger l ON l.bill_no = i.bill_no "
            f"WHERE i.pi_no IN ({marks(len(gone))}){gsql} GROUP BY i.pi_no",
            *gone,
            *gargs,
        ):
            rows[r["pi"]] = r | {"total_qty": 0, "gen_sum": 0, "quota": 0, "modify_time": None}
    out = sorted(rows.values(), key=lambda r: r["last_bill"] or "", reverse=True)
    return out[:LIST_LIMIT], len(out) > LIST_LIMIT


def item_stats(pis: list[str], facs: tuple[str, ...] | None = None) -> dict[str, dict]:
    """各 PI 的号数统计；facs = 授权范围（None = 全部），只数范围内的号、批次和推送任务。"""
    if not pis:
        return {}
    ph = marks(len(pis))
    isql, iargs = factories.item_scope_sql(facs)
    fsql, fargs = factories.scope_sql(facs)
    out: dict[str, dict] = {}
    for r in db.all(
        "SELECT pi_no, SUM(status = 'PENDING_ALLOC') pending, SUM(status <> 'PENDING_ALLOC') allocated, "
        "SUM(seq_pi_no <> pi_no) transferred_in, SUM(status = 'TO_PUSH' AND delivery_batch_no IS NULL "
        "AND suspend IS NULL AND acquire_batch_no IS NULL) unbatched, SUM(suspend IS NOT NULL) suspended "
        f"FROM sn_item WHERE pi_no IN ({ph}){isql} GROUP BY pi_no",
        *pis,
        *iargs,
    ):
        out[r["pi_no"]] = {
            k: int(r[k] or 0) for k in ("pending", "allocated", "transferred_in", "unbatched", "suspended")
        }
    for r in db.all(
        "SELECT pi_no, COUNT(*) n FROM sn_delivery_batch WHERE batch_type <> 'TRANSFER_OUT' AND pi_no IN (" + ph + ") "
        "AND (result = 'FAILED' OR (result = 'UNCONFIRMED' AND push_state = 'EXHAUSTED'))" + fsql + " GROUP BY pi_no",
        *pis,
        *fargs,
    ):
        out.setdefault(r["pi_no"], {})["interrupted"] = int(r["n"])
    for r in db.all(
        f"SELECT task_key, COUNT(*) n FROM sn_push_task WHERE task_key IN ({ph}) AND state IN "
        "('QUEUED','RUNNING','WAITING')" + fsql + " GROUP BY task_key",
        *pis,
        *fargs,
    ):
        out.setdefault(r["task_key"], {})["tasks"] = int(r["n"])
    return out


def gen_own(pis: list[str], facs: tuple[str, ...] | None = None) -> dict[str, int]:
    """已生成（F-10 第 1 条）：本 PI 各订单的已生成数之和，按号上的生成 PI 计（转组织改 PI 的号仍计入原 PI）。
    facs = 授权范围（None = 全部），只数范围内的号。"""
    if not pis:
        return {}
    isql, iargs = factories.item_scope_sql(facs)
    return {
        r["seq_pi_no"]: int(r["n"])
        for r in db.all(
            f"SELECT seq_pi_no, COUNT(*) n FROM sn_item WHERE seq_pi_no IN ({marks(len(pis))}){isql} "
            "GROUP BY seq_pi_no",
            *pis,
            *iargs,
        )
    }


def _running(pis: list[str]) -> dict[str, dict]:
    if not pis:
        return {}
    return {
        j["pi_no"]: {"gen_batch": j["gen_batch"], "qty": j["qty"], "done_qty": j["done_qty"]}
        for j in db.all(f"SELECT * FROM sn_gen_job WHERE running_pi IN ({marks(len(pis))})", *pis)
    }


def _line_issue_summary(pi: str, facs: tuple[str, ...] | None = None) -> tuple[bool, bool, dict]:
    """（有额度的物料行中有未定规则的，全部不能生成，各原因的行数）；只看授权范围 facs 内的单据。"""
    fsql, fargs = factories.scope_sql(facs, "org_code")
    keys = [
        (r["bill_no"], r["seq"])
        for r in db.all(
            "SELECT bill_no, seq FROM sn_prd_mo WHERE pi = %s" + fsql + " ORDER BY bill_no, seq", pi, *fargs
        )
    ]
    ls = generate._load_lines(pi, keys)
    todo = [x for x in ls if x.quota > 0]
    reasons: dict[str, int] = {}
    for x in todo:
        for i in x.issues:
            reasons[i] = reasons.get(i, 0) + 1
    need_rule = any("NO_RULE" in x.issues or "RULE_PREFIX" in x.issues for x in todo)
    cannot = bool(todo) and all(x.issues for x in todo)
    return need_rule, cannot, reasons


def pis(cu: CurrentUser, q: str | None, tab: str | None, by_modify: bool = False, by_items: bool = False) -> dict:
    """PI 列表与上方汇总（还可生成、待分配、待推送）。tab：生成页 TO_GENERATE（含生成中）/ GENERATED（已生成完），
    分配页 TO_ALLOCATE（有号待分配或推送中断）/ ALLOCATED（已分配完）；不传为全部。
    by_modify：生成页按单据修改日期排（与订单页同一排序）。
    by_items：分配页按号取 PI（见 _item_pi_rows），不逐行判断能否生成。
    普通操作员只看授权组织的单据和号，汇总也只按这些算。"""
    facs = cu.read_factories(None)
    if by_items:
        rows, capped = _item_pi_rows(q, tab, facs)
    else:
        rows = _pi_rows(q, by_modify, facs)
        capped = len(rows) >= LIST_LIMIT
    names = [r["pi"] for r in rows]
    stats = item_stats(names, facs)
    own = gen_own(names, facs)
    running = _running(names)
    lsql, largs = factories.scope_sql(facs, "l.org_code")
    frozen = {
        r["pi"]: r["n"]
        for r in db.all(
            "SELECT l.pi, COUNT(*) n FROM sn_bill_ledger l WHERE (l.pi_frozen = 1 OR l.suspend IS NOT NULL)"
            + lsql
            + " GROUP BY l.pi",
            *largs,
        )
    }
    out = []
    for i, r in enumerate(rows):
        s = stats.get(r["pi"], {})
        quota = int(r["quota"] or 0)
        pending = s.get("pending", 0)
        interrupted = s.get("interrupted", 0) > 0 or (s.get("unbatched", 0) > 0 and not s.get("tasks"))
        status, reasons = None, {}
        if r["pi"] in running:
            status = GENERATING
        elif quota > 0 and i < DETAIL_ROWS and not by_items:
            need_rule, cannot, reasons = _line_issue_summary(r["pi"], facs)
            status = CANNOT if cannot and not need_rule else (NEED_RULE if need_rule else None)
        if status is None:
            if interrupted:
                status = PUSH_INTERRUPTED
            elif quota > 0:
                status = PARTIAL if int(r["gen_sum"] or 0) > 0 else TO_GENERATE
            elif pending > 0:
                status = TO_ALLOCATE
            else:
                status = DONE
        out.append(
            {
                "pi_no": r["pi"],
                "customer_code": r["customer_code"],
                "bills": int(r["bills"]),
                "modify_time": orders.k3_time(r["modify_time"]),
                "orgs": [o for o in (r["orgs"] or "").split(",") if o],
                "total_qty": int(r["total_qty"] or 0),
                "generated_qty": own.get(r["pi"], 0),
                "transferred_in": s.get("transferred_in", 0),
                "allocated_qty": s.get("allocated", 0),
                "pending_alloc": pending,
                "quota": quota,
                "unbatched": s.get("unbatched", 0),
                "suspended": s.get("suspended", 0),
                "frozen_bills": frozen.get(r["pi"], 0),
                "push_interrupted": interrupted,
                "running": running.get(r["pi"]),
                "status": status,
                "reasons": reasons,
            }
        )
    to_alloc = lambda x: x["pending_alloc"] > 0 or x["push_interrupted"]  # noqa: E731
    tabs = {
        "TO_GENERATE": lambda x: x["quota"] > 0 or x["status"] == GENERATING,
        "GENERATED": lambda x: x["quota"] == 0 and x["status"] != GENERATING,
        "TO_ALLOCATE": to_alloc,
        "ALLOCATED": lambda x: not to_alloc(x) and x["allocated_qty"] > 0,
    }
    t = util.trim(tab)
    shown = [x for x in out if tabs[t](x)] if t in tabs else out
    if t == "TO_GENERATE":
        # 生成中的排最前，不被「只列最近 3 页」截掉
        shown.sort(key=lambda x: x["status"] != GENERATING)
    return {
        "items": shown,
        "capped": capped,
        "summary": {
            "quota": sum(x["quota"] for x in out),
            "pending_alloc": sum(x["pending_alloc"] for x in out),
            "unbatched": sum(x["unbatched"] for x in out),
        },
        "synced_at": util.fmt(db.scalar("SELECT MAX(synced_at) FROM sn_prd_mo")),
    }


def context(cu: CurrentUser, pi_in: str | None, bill_no: str | None = None) -> dict:
    """生成详情：PI 头、各单据（含已离开快照的已出号单据）及物料行、规则选项、计数、生成任务、可撤销的号段。
    普通操作员只看授权组织的单据（每张单据只属于一个生产组织），计数、任务、号段也只含这些单据的。"""
    from app.core.errors import ErrorCode, biz

    pi = util.trim(pi_in)
    if not pi and not util.blank(bill_no):
        b = orders.bill(bill_no)
        if b is None:
            led = changes.ledger(util.trim(bill_no))
            pi = "" if led is None else led["pi"]
        else:
            pi = b.pi
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    facs = cu.read_factories(None)
    fsql, fargs = factories.scope_sql(facs, "org_code")
    snap = db.all("SELECT * FROM sn_prd_mo WHERE pi = %s" + fsql + " ORDER BY bill_no, seq", pi, *fargs)
    keys = [(r["bill_no"], r["seq"]) for r in snap]
    counts, fills, lens, units = orders.line_extras(keys)
    # 表头客户取本 PI 第一个非空的客户编码（客户编码为空的单据照常列出）
    customer = next((r["customer_code"] for r in snap if r["customer_code"]), None) or orders.customer_of_pi(pi) or ""
    last = generate.last_seq(pi)
    ls = {(x.bill_no, x.seq): x for x in generate._load_lines(pi, keys)}
    bills: dict[str, dict] = {}
    for r in snap:
        k = (r["bill_no"], r["seq"])
        b = bills.setdefault(
            r["bill_no"],
            {
                "bill_no": r["bill_no"],
                "po": r["po"],
                "customer_code": r["customer_code"],
                "org_code": r["org_code"],
                "org_name": r["org_name"],
                "in_snapshot": True,
                "lines": [],
            },
        )
        if r["modify_time"] is not None and (b.get("_mt") is None or r["modify_time"] > b["_mt"]):
            b["_mt"] = r["modify_time"]
        x = ls[k]
        v = orders.line_view(
            r, counts.get(k), fills.get(k), rules.info(x.res, x.length, last + 1, x.unit), lens.get(k), units.get(k)
        )
        v["issues"] = x.issues
        b["lines"].append(v)
    # 已出号但不在快照（或号挂在本 PI 下）的单据
    isql, iargs = factories.item_scope_sql(facs)
    other = db.column(
        "SELECT DISTINCT bill_no FROM sn_item WHERE pi_no = %s AND bill_no NOT IN "
        "(SELECT DISTINCT bill_no FROM sn_prd_mo WHERE pi = %s)" + isql,
        pi,
        pi,
        *iargs,
    )
    for no in other:
        led = changes.ledger(no) or {}
        bills[no] = {
            "bill_no": no,
            "po": "",
            "customer_code": led.get("customer_code", ""),
            "org_code": led.get("org_code", ""),
            "org_name": led.get("org_name", ""),
            "in_snapshot": orders.bill(no) is not None,
            "lines": [],
        }
    if facs is not None and not bills:
        # 这张 PI 没有授权组织的单据
        raise biz(ErrorCode.FACTORY_FORBIDDEN, factory=cu.scope_text())
    states = changes.bill_states(list(bills))
    pending = {
        r["bill_no"]: int(r["n"])
        for r in db.all(
            "SELECT bill_no, COUNT(*) n FROM sn_item WHERE pi_no = %s AND status = 'PENDING_ALLOC'"
            + isql
            + " GROUP BY bill_no",
            pi,
            *iargs,
        )
    }
    for no, b in bills.items():
        b["modify_time"] = orders.k3_time(b.get("_mt"))
        b["state"] = states.get(no, changes.EMPTY_STATE)
        b["pending_alloc"] = pending.get(no, 0)
        b["total_qty"] = sum(x["qty"] for x in b["lines"])
        b["generated_qty"] = sum(x["generated_qty"] for x in b["lines"])
        b["quota"] = sum(x["quota"] for x in b["lines"])
    stats = item_stats([pi], facs).get(pi, {})
    running = db.one("SELECT * FROM sn_gen_job WHERE running_pi = %s", pi)
    if running is not None and not any(generate.in_scope(facs, x) for x in json.loads(running["plan_json"])["items"]):
        # 别的组织正在生成：不显示它的进度，生成、撤销仍会因 PI 操作锁被拒绝
        running = None
    revocable = generate.revocable(pi, facs)
    return {
        "pi_no": pi,
        "customer_code": customer,
        # 与订单页同一排序：修改日期新的在前（已不在快照、没有修改日期的排后），同一时间单据编号大的在前
        "bills": [
            {k: v for k, v in b.items() if k != "_mt"}
            for b in sorted(
                bills.values(),
                key=lambda x: (x.get("_mt") is not None, x.get("_mt") or datetime.min, x["bill_no"]),
                reverse=True,
            )
        ],
        "counter": {"last_seq_dec": last, "next_seq": last + 1},
        "summary": {
            "total_qty": sum(b["total_qty"] for b in bills.values()),
            "generated_qty": gen_own([pi], facs).get(pi, 0),
            "transferred_in": stats.get("transferred_in", 0),
            "allocated_qty": stats.get("allocated", 0),
            "pending_alloc": stats.get("pending", 0),
            "quota": sum(b["quota"] for b in bills.values()),
            "suspended": stats.get("suspended", 0),
        },
        "pi_rule": rules.info(rules.pi_default(pi, customer)),
        "customer_rule": _customer_rule(customer),
        "rule_options": rules.options(pi, customer, facs),
        "limits": generate.limits(),
        "running_job": None if running is None else generate.progress(running["gen_batch"]),
        "jobs": generate.jobs_with(pi, 20, revocable, facs),
        # 当前可撤销的物料行（流水排在最后的那一段）
        "revocable": revocable,
        # 未撤销的号段，从流水最末往前；物料行上的「撤销」= 撤到该行为止（流水在其后的行一并撤）
        "segments": generate.revoke_chain(pi, facs),
    }


def _customer_rule(customer: str) -> dict | None:
    if not customer:
        return None
    r = db.one(
        "SELECT r.id, r.rule_code, r.rule_name FROM sn_customer_rule c JOIN sn_rule r ON r.id = c.rule_id "
        "WHERE c.customer_code = %s",
        customer,
    )
    return (
        None
        if r is None
        else {"customer_code": customer, "rule_id": r["id"], "rule_code": r["rule_code"], "rule_name": r["rule_name"]}
    )
