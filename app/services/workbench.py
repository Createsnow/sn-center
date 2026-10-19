"""生成与分配页（F-10）：以 PI 为单位的工作台。

每张 PI 一行：客户、单据数、生产组织、状态、订单总数、已生成（各订单已生成数之和；转组织改 PI 的号仍计入原 PI）、
转入号（挂在本 PI 下、由别的 PI 生成的号）、已分配（含已推送、已拉取）；行上按钮按 F-10 第 3 条的顺序判断。
详情：步骤条「SN 规则 → 生成 → 分配」+ 订单（到物料行）/ 生成任务（最近 20 条）/ 分配记录（最近 100 条，推送与拉取批次）。
"""

from __future__ import annotations

from app.core import utils as util
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import changes, delivery, factories, generate, orders, rules

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


def _pi_rows(q: str | None) -> list[dict]:
    where = " WHERE m.customer_code <> '' AND m.pi <> ''"
    args: list = []
    text = util.trim(q)
    if text:
        like = util.like_any(text)
        where += (
            " AND m.pi IN (SELECT x.pi FROM sn_prd_mo x WHERE x.pi LIKE %s OR x.bill_no LIKE %s OR x.po LIKE %s "
            "OR x.customer_code LIKE %s OR x.material_code LIKE %s)"
        )
        args += [like] * 5
    return db.all(
        "SELECT m.pi, MAX(m.customer_code) customer_code, COUNT(DISTINCT m.bill_no) bills, "
        "GROUP_CONCAT(DISTINCT m.org_code ORDER BY m.org_code) orgs, SUM(FLOOR(m.qty)) total_qty, "
        "SUM(COALESCE(c.gen_count, 0)) generated, SUM(GREATEST(FLOOR(m.qty) - COALESCE(c.own_generated, 0), 0)) quota, "
        "MAX(m.bill_no) last_bill FROM sn_prd_mo m LEFT JOIN sn_line_count c ON c.bill_no = m.bill_no AND c.seq = m.seq"
        + where
        + " GROUP BY m.pi ORDER BY last_bill DESC LIMIT %s",
        *args,
        LIST_LIMIT,
    )


def _item_stats(pis: list[str]) -> dict[str, dict]:
    if not pis:
        return {}
    ph = marks(len(pis))
    out: dict[str, dict] = {}
    for r in db.all(
        "SELECT pi_no, SUM(status = 'PENDING_ALLOC') pending, SUM(status <> 'PENDING_ALLOC') allocated, "
        "SUM(seq_pi_no <> pi_no) transferred_in, SUM(status = 'TO_PUSH' AND delivery_batch_no IS NULL "
        "AND suspend IS NULL AND acquire_batch_no IS NULL) unbatched, SUM(suspend IS NOT NULL) suspended "
        f"FROM sn_item WHERE pi_no IN ({ph}) GROUP BY pi_no",
        *pis,
    ):
        out[r["pi_no"]] = {
            k: int(r[k] or 0) for k in ("pending", "allocated", "transferred_in", "unbatched", "suspended")
        }
    for r in db.all(
        "SELECT pi_no, COUNT(*) n FROM sn_delivery_batch WHERE batch_type = 'DELIVERY' AND pi_no IN (" + ph + ") "
        "AND (result = 'FAILED' OR (result = 'UNCONFIRMED' AND push_state = 'EXHAUSTED')) GROUP BY pi_no",
        *pis,
    ):
        out.setdefault(r["pi_no"], {})["interrupted"] = int(r["n"])
    for r in db.all(
        f"SELECT task_key, COUNT(*) n FROM sn_push_task WHERE task_key IN ({ph}) AND state IN ('QUEUED','RUNNING','WAITING') "
        "GROUP BY task_key",
        *pis,
    ):
        out.setdefault(r["task_key"], {})["tasks"] = int(r["n"])
    return out


def _gen_own(pis: list[str]) -> dict[str, int]:
    """已生成（F-10 第 1 条）：本 PI 各订单的已生成数之和，按号上的生成 PI 计（转组织改 PI 的号仍计入原 PI）。"""
    if not pis:
        return {}
    return {
        r["seq_pi_no"]: int(r["n"])
        for r in db.all(
            f"SELECT seq_pi_no, COUNT(*) n FROM sn_item WHERE seq_pi_no IN ({marks(len(pis))}) GROUP BY seq_pi_no", *pis
        )
    }


def _running(pis: list[str]) -> dict[str, dict]:
    if not pis:
        return {}
    return {
        j["pi_no"]: {"gen_batch": j["gen_batch"], "qty": j["qty"], "done_qty": j["done_qty"]}
        for j in db.all(f"SELECT * FROM sn_gen_job WHERE running_pi IN ({marks(len(pis))})", *pis)
    }


def _line_issue_summary(pi: str) -> tuple[bool, bool, dict]:
    """（有额度的物料行中有未定规则的，全部不能生成，各原因的行数）。"""
    keys = [
        (r["bill_no"], r["seq"])
        for r in db.all(
            "SELECT bill_no, seq FROM sn_prd_mo WHERE pi = %s AND customer_code <> '' ORDER BY bill_no, seq", pi
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


def pis(cu: CurrentUser, q: str | None, tab: str | None) -> dict:
    """PI 列表与上方汇总（还可生成、待分配、待推送）。tab：ALL / TO_GENERATE / GENERATING / TO_ALLOCATE / DONE。"""
    rows = _pi_rows(q)
    names = [r["pi"] for r in rows]
    stats = _item_stats(names)
    gen_own = _gen_own(names)
    running = _running(names)
    frozen = {
        r["pi"]: r["n"]
        for r in db.all(
            "SELECT l.pi, COUNT(*) n FROM sn_bill_ledger l WHERE (l.pi_frozen = 1 OR l.suspend IS NOT NULL) GROUP BY l.pi"
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
        elif quota > 0 and i < DETAIL_ROWS:
            need_rule, cannot, reasons = _line_issue_summary(r["pi"])
            status = CANNOT if cannot and not need_rule else (NEED_RULE if need_rule else None)
        if status is None:
            if interrupted:
                status = PUSH_INTERRUPTED
            elif quota > 0:
                status = PARTIAL if int(r["generated"] or 0) > 0 else TO_GENERATE
            elif pending > 0:
                status = TO_ALLOCATE
            else:
                status = DONE
        out.append(
            {
                "pi_no": r["pi"],
                "customer_code": r["customer_code"],
                "bills": int(r["bills"]),
                "orgs": [o for o in (r["orgs"] or "").split(",") if o],
                "total_qty": int(r["total_qty"] or 0),
                "generated_qty": gen_own.get(r["pi"], 0),
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
    tabs = {
        "TO_GENERATE": lambda x: x["quota"] > 0 and x["status"] != GENERATING,
        "GENERATING": lambda x: x["status"] == GENERATING,
        "TO_ALLOCATE": lambda x: x["pending_alloc"] > 0 or x["push_interrupted"],
        "DONE": lambda x: x["status"] == DONE,
    }
    t = util.trim(tab) or "ALL"
    shown = [x for x in out if tabs[t](x)] if t in tabs else out
    return {
        "items": shown,
        "capped": len(rows) >= LIST_LIMIT,
        "summary": {
            "quota": sum(x["quota"] for x in out),
            "pending_alloc": sum(x["pending_alloc"] for x in out),
            "unbatched": sum(x["unbatched"] for x in out),
        },
        "synced_at": util.fmt(db.scalar("SELECT MAX(synced_at) FROM sn_prd_mo")),
    }


def context(cu: CurrentUser, pi_in: str | None, bill_no: str | None = None) -> dict:
    """详情：PI 头、各单据（含已离开快照的已出号单据）及物料行、规则选项、计数、任务、分配面板、分配记录。"""
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
    snap = db.all("SELECT * FROM sn_prd_mo WHERE pi = %s AND customer_code <> '' ORDER BY bill_no, seq", pi)
    keys = [(r["bill_no"], r["seq"]) for r in snap]
    counts, fills = orders.line_extras(keys)
    customer = snap[0]["customer_code"] if snap else (orders.customer_of_pi(pi) or "")
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
        x = ls[k]
        v = orders.line_view(r, counts.get(k), fills.get(k), rules.info(x.res, x.length, last + 1))
        v["issues"] = x.issues
        b["lines"].append(v)
    # 已出号但不在快照（或号挂在本 PI 下）的单据
    other = db.column(
        "SELECT DISTINCT bill_no FROM sn_item WHERE pi_no = %s AND bill_no NOT IN "
        "(SELECT DISTINCT bill_no FROM sn_prd_mo WHERE pi = %s)",
        pi,
        pi,
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
    states = changes.bill_states(list(bills))
    pending = {
        r["bill_no"]: int(r["n"])
        for r in db.all(
            "SELECT bill_no, COUNT(*) n FROM sn_item WHERE pi_no = %s AND status = 'PENDING_ALLOC' GROUP BY bill_no", pi
        )
    }
    for no, b in bills.items():
        b["state"] = states.get(no, changes.EMPTY_STATE)
        b["pending_alloc"] = pending.get(no, 0)
        b["total_qty"] = sum(x["qty"] for x in b["lines"])
        b["generated_qty"] = sum(x["generated_qty"] for x in b["lines"])
        b["quota"] = sum(x["quota"] for x in b["lines"])
    stats = _item_stats([pi]).get(pi, {})
    running = db.one("SELECT * FROM sn_gen_job WHERE running_pi = %s", pi)
    return {
        "pi_no": pi,
        "customer_code": customer,
        "bills": sorted(bills.values(), key=lambda x: x["bill_no"]),
        "counter": {"last_seq_dec": last, "next_seq": last + 1},
        "summary": {
            "total_qty": sum(b["total_qty"] for b in bills.values()),
            "generated_qty": _gen_own([pi]).get(pi, 0),
            "transferred_in": stats.get("transferred_in", 0),
            "allocated_qty": stats.get("allocated", 0),
            "pending_alloc": stats.get("pending", 0),
            "quota": sum(b["quota"] for b in bills.values()),
            "suspended": stats.get("suspended", 0),
        },
        "pi_rule": rules.info(rules.pi_default(pi, customer)),
        "customer_rule": _customer_rule(customer),
        "rule_options": rules.options(pi, customer),
        "limits": generate.limits(),
        "running_job": None if running is None else generate.progress(running["gen_batch"]),
        "jobs": generate.jobs(pi, 20),
        "allocation": generate.alloc_preview(pi, None, None),
        "push": push_status(pi),
        "batches": delivery.list_batches(cu, None, pi, None, None, "DELIVERY", None, None, util.Page.of(1, 100))[
            "items"
        ],
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


def push_status(pi: str) -> list[dict]:
    """按生产组织：推送任务状态、「第 i / N 次」、已推送枚数、剩余次数、失败原因。"""
    orgs = db.column(
        "SELECT DISTINCT factory_code FROM sn_item WHERE pi_no = %s AND factory_code IS NOT NULL "
        "UNION SELECT DISTINCT factory_code FROM sn_delivery_batch WHERE pi_no = %s AND batch_type = 'DELIVERY'",
        pi,
        pi,
    )
    out = []
    for org in sorted(o for o in orgs if o):
        f = factories.get(org)
        if not (f and f["mes_enabled"]) and not db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_delivery_batch WHERE factory_code = %s AND pi_no = %s)", org, pi
        ):
            continue
        t = delivery.task_state(org, pi)
        rem = delivery.remaining_pushes(org, pi)
        pushed = db.count(
            "SELECT COUNT(*) FROM sn_item WHERE factory_code = %s AND pi_no = %s AND status = 'PUSHED'", org, pi
        )
        done_batches = db.count(
            "SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND pi_no = %s AND batch_type = 'DELIVERY' "
            "AND result IN ('SUCCESS','SUCCESS_CONFLICT')",
            org,
            pi,
        )
        last_fail = db.one(
            "SELECT batch_no, fail_reason, result, push_state FROM sn_delivery_batch WHERE factory_code = %s AND pi_no = %s "
            "AND batch_type = 'DELIVERY' AND (result = 'FAILED' OR push_state IN ('WAIT_RETRY','EXHAUSTED')) "
            "ORDER BY id DESC LIMIT 1",
            org,
            pi,
        )
        out.append(
            {
                "factory_code": org,
                "mes_enabled": bool(f and f["mes_enabled"]),
                "task": t,
                "remaining": rem,
                "done_batches": done_batches,
                "total_batches": done_batches + rem["pushes"],
                "pushed_qty": pushed,
                "last_problem": None if last_fail is None else {k: util.jv(v) for k, v in last_fail.items()},
            }
        )
    return out
