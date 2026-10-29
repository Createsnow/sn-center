"""SN 分配（F-11 分配部分）与分配页详情。

号只能分到订单的生产组织（快照中订单的生产组织；订单已不在快照时取号上的生成时订单生产组织；
反审核中、PI 变更待确认、暂停交付的单据跳过）。对接 MES 的改为待推送（未入批）并自动开始推送；
未对接的改为已分配。分配与 SN 导出相互独立：号一经分配即可在分配页「已分配单据」页签下载，不看推送结果。
分配与生成、撤销共用 PI 操作锁。
分配页详情：待分配的物料行（号段、目标生产组织）、推送进度、分配记录（最近 100 条，推送与拉取批次）。
PI 列表与生成页共用（workbench.pis）。
普通操作员只能分配到授权组织：指定的单据、物料行须属于授权组织；整张 PI 分配时只分授权组织的号。
"""

from __future__ import annotations

import math

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks
from app.services import audit, changes, delivery, factories, locks, orders, workbench


def target_org(bill_no: str) -> tuple[str | None, str | None]:
    """分配到哪个生产组织：快照中订单的生产组织；订单已不在快照时为 None（取号上的生成时订单生产组织）。
    返回（组织，不能分配的原因）。"""
    block = changes.allocation_block(bill_no)
    if block:
        return None, block
    so = orders.snapshot_org(bill_no)
    return (so[0] if so else None), None


def _scope(pi: str, bills_in: list[str] | None, lines_in: list[dict] | None) -> tuple[str, list]:
    """分配范围（整张 PI / 单据 / 物料行）的 SQL 条件。"""
    if lines_in:
        keys = [(util.trim(x.get("bill_no")), int(x.get("seq") or 0)) for x in lines_in]
        cond = " OR ".join(["(bill_no = %s AND bill_seq = %s)"] * len(keys))
        return f" AND ({cond})", [v for k in keys for v in k]
    nos = [util.trim(b) for b in bills_in or [] if util.trim(b)]
    if nos:
        return f" AND bill_no IN ({marks(len(nos))})", nos
    return "", []


def _check_picked(cu: CurrentUser, bills_in: list[str] | None, lines_in: list[dict] | None) -> None:
    """指定的单据、物料行须属于授权组织。"""
    picked = [util.trim(x.get("bill_no")) for x in lines_in or []] or [util.trim(b) for b in bills_in or []]
    if picked:
        cu.check_write(*orders.bill_orgs(picked))


def _groups(pi: str, bills_in, lines_in, facs: tuple[str, ...] | None = None) -> tuple[list[dict], list[dict]]:
    """待分配号按（单据、目标组织）分组；返回（可分配组，跳过的单据及原因）。
    facs = 授权范围（None = 全部）：目标组织不在范围内的不列出。"""
    cond, args = _scope(pi, bills_in, lines_in)
    rows = db.all(
        "SELECT bill_no, gen_org_code, COUNT(*) n, MIN(seq_dec) s, MAX(seq_dec) e FROM sn_item "
        "WHERE pi_no = %s AND status = 'PENDING_ALLOC'" + cond + " GROUP BY bill_no, gen_org_code ORDER BY bill_no",
        pi,
        *args,
    )
    groups, skipped = [], []
    for r in rows:
        org, block = target_org(r["bill_no"])
        if facs is not None and (org or r["gen_org_code"]) not in facs:
            continue
        if block:
            skipped.append({"bill_no": r["bill_no"], "qty": int(r["n"]), "reason": block})
            continue
        groups.append(
            {
                "bill_no": r["bill_no"],
                "org": org or r["gen_org_code"],
                "gen_org": r["gen_org_code"],
                "qty": int(r["n"]),
                "snapshot": org is not None,
            }
        )
    return groups, skipped


def preview(cu: CurrentUser, pi_in: str | None, bills: list[str] | None, lines: list[dict] | None) -> dict:
    """分配面板：按生产组织汇总枚数、单据和物料，标出是否对接 MES；按钮写「分配 N 枚（推送 M 次）」。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    _check_picked(cu, bills, lines)
    groups, skipped = _groups(pi, bills, lines, cu.read_factories(None))
    by_org: dict[str, dict] = {}
    orgs_of_bill: dict[str, set[str]] = {}
    for g in groups:
        o = by_org.setdefault(g["org"], {"org_code": g["org"], "qty": 0, "bills": [], "materials": set()})
        o["qty"] += g["qty"]
        o["bills"].append(g["bill_no"])
        orgs_of_bill.setdefault(g["bill_no"], set()).add(g["org"])
    cond, args = _scope(pi, bills, lines)
    for r in db.all(
        "SELECT bill_no, GROUP_CONCAT(DISTINCT material_code) m FROM sn_item WHERE pi_no = %s AND status = "
        "'PENDING_ALLOC'" + cond + " GROUP BY bill_no",
        pi,
        *args,
    ):
        for org in orgs_of_bill.get(r["bill_no"], ()):
            by_org[org]["materials"].update((r["m"] or "").split(","))
    names = factories.names()
    out_orgs, pushes = [], 0
    for o in by_org.values():
        f = factories.get(o["org_code"])
        mes = bool(f and f["mes_enabled"])
        n_push = math.ceil(o["qty"] / max(1, int(f["batch_size"]))) if mes else 0
        pushes += n_push
        out_orgs.append(
            {
                "org_code": o["org_code"],
                "org_name": names.get(o["org_code"], ""),
                "mes_enabled": mes,
                "qty": o["qty"],
                "pushes": n_push,
                "bills": sorted(set(o["bills"])),
                "materials": sorted(o["materials"]),
            }
        )
    return {
        "pi_no": pi,
        "qty": sum(o["qty"] for o in out_orgs),
        "pushes": pushes,
        "orgs": sorted(out_orgs, key=lambda x: x["org_code"]),
        "skipped": skipped,
    }


def allocate(
    cu: CurrentUser, pi_in: str | None, bills: list[str] | None = None, lines: list[dict] | None = None
) -> dict:
    """分配：范围内待分配的号一次全部挂到生产组织；对接 MES 的改为待推送（未入批）并自动推送，未对接的改为已分配。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    _check_picked(cu, bills, lines)
    with locks.with_pi_lock(pi):
        with db.tx():
            groups, skipped = _groups(pi, bills, lines, cu.read_factories(None))
            if not groups:
                raise biz(ErrorCode.ALLOC_NOTHING)
            cond, args = _scope(pi, bills, lines)
            now = util.now()
            total = 0
            started: set[str] = set()
            for g in groups:
                mes = factories.is_mes(g["org"])
                status = util.TO_PUSH if mes else util.ALLOCATED
                gen_cond = "" if g["snapshot"] else " AND gen_org_code = %s"
                gen_args = [] if g["snapshot"] else [g["gen_org"]]
                # 在 PI 操作锁与事务内，这里统计到的就是下面要改的号
                per_line = db.all(
                    "SELECT bill_seq, COUNT(*) n, MIN(sn) a, MAX(sn) b FROM sn_item WHERE pi_no = %s AND bill_no = %s "
                    "AND status = 'PENDING_ALLOC'" + gen_cond + cond + " GROUP BY bill_seq",
                    pi,
                    g["bill_no"],
                    *gen_args,
                    *args,
                )
                if not per_line:
                    # 同一单据有多个生成时组织、按快照组织分配时，前一组已一并分配
                    continue
                n = db.exec(
                    "UPDATE sn_item SET status = %s, factory_code = %s, push_type = 'NEW', allocated_at = %s, "
                    "updated_at = %s WHERE pi_no = %s AND bill_no = %s AND status = 'PENDING_ALLOC'" + gen_cond + cond,
                    status,
                    g["org"],
                    now,
                    now,
                    pi,
                    g["bill_no"],
                    *gen_args,
                    *args,
                )
                for pl in per_line:
                    db.exec(
                        "UPDATE sn_line_count SET alloc_count = alloc_count + %s, updated_at = %s WHERE bill_no = %s "
                        "AND seq = %s",
                        int(pl["n"]),
                        now,
                        g["bill_no"],
                        pl["bill_seq"],
                    )
                audit.record(
                    cu,
                    audit.entry(audit.SN_ALLOCATE)
                    .factory(g["org"])
                    .pi(pi)
                    .bill(g["bill_no"])
                    .range(min(pl["a"] for pl in per_line), max(pl["b"] for pl in per_line))
                    .count(n)
                    .status(util.PENDING_ALLOC, status)
                    .info(f"mes={int(mes)}"),
                )
                total += n
                if mes:
                    started.add(g["org"])
            changes.refresh_items({g["bill_no"] for g in groups})
        for org in sorted(started):
            delivery.start_task(cu, org, pi)
    return {"pi_no": pi, "qty": total, "skipped": skipped, "orgs": sorted({g["org"] for g in groups})}


# ================================================================== 分配页详情


def context(cu: CurrentUser, pi_in: str | None) -> dict:
    """分配详情：待分配的号按物料行列出（枚数、号段、分到哪个生产组织、是否对接 MES、不能分配的原因），
    以及各组织的推送进度与分配记录。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    facs = cu.read_factories(None)
    isql, iargs = factories.item_scope_sql(facs)
    rows = db.all(
        "SELECT bill_no, bill_seq, gen_org_code, MAX(material_code) material_code, MAX(attach_no) attach_no, "
        "MAX(length_text) length, "
        "COUNT(*) n, MIN(sn) a, MAX(sn) b FROM sn_item WHERE pi_no = %s AND status = 'PENDING_ALLOC'"
        + isql
        + " GROUP BY bill_no, bill_seq, gen_org_code ORDER BY bill_no, bill_seq",
        pi,
        *iargs,
    )
    names = factories.names()
    targets: dict[str, tuple[str | None, str | None]] = {}
    lines = []
    for r in rows:
        if r["bill_no"] not in targets:
            targets[r["bill_no"]] = target_org(r["bill_no"])
        org, block = targets[r["bill_no"]]
        org = None if block else org or r["gen_org_code"]
        f = factories.get(org) if org else None
        lines.append(
            {
                "bill_no": r["bill_no"],
                "seq": r["bill_seq"],
                "material_code": r["material_code"],
                "attach_no": r["attach_no"],
                "length": r["length"],
                "qty": int(r["n"]),
                "start_sn": r["a"],
                "end_sn": r["b"],
                "org_code": org,
                "org_name": names.get(org, "") if org else "",
                "mes_enabled": bool(f and f["mes_enabled"]),
                "skip": block,
            }
        )
    stats = workbench.item_stats([pi], facs).get(pi, {})
    msql, margs = factories.scope_sql(facs, "m.org_code")
    quota = db.scalar(
        "SELECT SUM(GREATEST(FLOOR(m.qty) - COALESCE(c.own_generated, 0), 0)) FROM sn_prd_mo m "
        "LEFT JOIN sn_line_count c ON c.bill_no = m.bill_no AND c.seq = m.seq WHERE m.pi = %s" + msql,
        pi,
        *margs,
    )
    return {
        "pi_no": pi,
        "customer_code": orders.customer_of_pi(pi) or "",
        "summary": {
            "generated_qty": workbench.gen_own([pi], facs).get(pi, 0),
            "transferred_in": stats.get("transferred_in", 0),
            "pending_alloc": stats.get("pending", 0),
            "allocated_qty": stats.get("allocated", 0),
            "unbatched": stats.get("unbatched", 0),
            "suspended": stats.get("suspended", 0),
            "quota": int(quota or 0),
        },
        "lines": lines,
        "push": push_status(pi, facs),
        "batches": delivery.list_batches(cu, None, pi, None, None, "DELIVERY", None, None, util.Page.of(1, 100))[
            "items"
        ],
    }


def push_status(pi: str, facs: tuple[str, ...] | None = None) -> list[dict]:
    """按生产组织：推送任务状态、「第 i / N 次」（i 为已确认的批次数，有一批正在推送或等回执时再加 1）、
    是否在等 MES 回执、已推送枚数、剩余次数、失败原因；只列授权范围 facs 内的组织。"""
    orgs = db.column(
        "SELECT DISTINCT factory_code FROM sn_item WHERE pi_no = %s AND factory_code IS NOT NULL "
        "UNION SELECT DISTINCT factory_code FROM sn_delivery_batch WHERE pi_no = %s AND batch_type <> 'TRANSFER_OUT'",
        pi,
        pi,
    )
    out = []
    for org in sorted(o for o in orgs if o and (facs is None or o in facs)):
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
            "SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND pi_no = %s "
            "AND batch_type <> 'TRANSFER_OUT' AND result = 'SUCCESS'",
            org,
            pi,
        )
        last_fail = db.one(
            "SELECT batch_no, fail_reason, result, push_state FROM sn_delivery_batch WHERE factory_code = %s AND "
            "pi_no = %s "
            "AND batch_type <> 'TRANSFER_OUT' AND (result = 'FAILED' OR push_state IN ('WAIT_RETRY','EXHAUSTED') "
            "OR (push_state = 'WAIT_RECEIPT' AND receipt_timeouts > 0)) "
            "ORDER BY id DESC LIMIT 1",
            org,
            pi,
        )
        # 正在推送或等回执、等重试的那一批算作当前这一次
        current = (
            db.one(
                "SELECT push_state FROM sn_delivery_batch WHERE batch_no = %s AND result = 'UNCONFIRMED'",
                t["current_batch"],
            )
            if t and t["state"] in ("RUNNING", "WAITING") and t["current_batch"]
            else None
        )
        total = done_batches + rem["pushes"]
        out.append(
            {
                "factory_code": org,
                "mes_enabled": bool(f and f["mes_enabled"]),
                "task": t,
                "remaining": rem,
                "done_batches": done_batches,
                "current_push": min(total, done_batches + (1 if current else 0)),
                "waiting_receipt": bool(current and current["push_state"] == "WAIT_RECEIPT"),
                "total_batches": total,
                "pushed_qty": pushed,
                "last_problem": None if last_fail is None else {k: util.jv(v) for k, v in last_fail.items()},
            }
        )
    return out
