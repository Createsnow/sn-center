"""SN 分配（F-11 分配部分）与分配页详情。

号只能分到订单的生产组织（快照中订单的生产组织；订单已不在快照时取号上的生成时订单生产组织；
反审核中、PI 变更待确认、暂停交付的单据跳过）。对接 MES 的改为待推送（未入批）并自动开始推送；
未对接的改为已分配。生产组织后开启「对接 MES」时，已分配的号改为待推送（未入批）、还没开始推送：
分配时一并重新分配（启动推送），已生成待分配的号照常分配推送；推送中断的（有未确认或失败的批次、推送任务已停止）
仍由「继续推送」处理。分配与 SN 导出相互独立：号一经分配即可在分配页「已分配」页签下载，不看推送结果。
分配与生成、撤销共用 PI 操作锁。
分配页左侧按单据列出（bills）：SN 枚数与状态（待分配 / 待处理 / 已分配），只按 PI、单据编号筛选。
分配页详情（所选单据所在的 PI）：各物料行的枚数、号段、目标生产组织与推送进度，各组织推送进度，
推送批次分页另取（/api/delivery/batches?pi=…&batch_type=DELIVERY&asc=1）。
普通操作员只能分配到授权组织：指定的单据、物料行须属于授权组织；整张 PI 分配时只分授权组织的号。
SN 导入的号（origin = IMPORT）照常在这里分配：挂在单据序号 0 上（详情里标为「导入」行），分到单据的生产组织。
"""

from __future__ import annotations

import math

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, marks, retry_deadlock
from app.services import audit, bill_stats, changes, delivery, factories, locks, orders, workbench

#: 分配页左侧页签：待分配（含待处理）/ 已分配
TO_ALLOCATE = "TO_ALLOCATE"
ALLOCATED = "ALLOCATED"
#: 单据状态：待分配（还有待分配的号）、待处理（号都已分配，但还有待推送的：推送中、等回执、推送失败等）、已分配
BILL_PENDING = "PENDING"
BILL_PROCESSING = "PROCESSING"
BILL_ALLOCATED = "ALLOCATED"
#: 推送失败或重试用尽仍未确认的批次（与 workbench.item_stats 的「推送中断」同一口径）
_BROKEN = "(d.result = 'FAILED' OR (d.result = 'UNCONFIRMED' AND d.push_state = 'EXHAUSTED'))"


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


#: 推送任务进行中
_ACTIVE = ("QUEUED", "RUNNING", "WAITING")


def push_idle(org: str, pi: str, task: dict | None, rem: dict) -> bool:
    """号已分配、待推送（未入批），但本组织本 PI 还没开始推送：没有推送任务（或已推完），也没有未确认或失败的批次。
    组织后开启「对接 MES」时已分配的号即如此；分配时一并启动推送（重新分配）。"""
    return (
        rem["unbatched"] > 0
        and rem["batches"] == 0
        and (task is None or task["state"] == "DONE")
        and factories.is_mes(org)
    )


def push_interrupted(task: dict | None, rem: dict) -> bool:
    """推送中断（由「继续推送」处理）：还有要推的，没有推送任务在跑，且有未确认或失败的批次、或推送任务已停止。"""
    state = None if task is None else task["state"]
    return rem["pushes"] > 0 and state not in _ACTIVE and (rem["batches"] > 0 or state == "STOPPED")


def _idle(pi: str, bills_in, lines_in, facs: tuple[str, ...] | None) -> list[dict]:
    """范围内已分配、还没开始推送的号（见 push_idle），按（组织、单据）列出枚数与物料。"""
    cond, args = _scope(pi, bills_in, lines_in)
    rows = db.all(
        "SELECT factory_code, bill_no, COUNT(*) n, GROUP_CONCAT(DISTINCT material_code) m FROM sn_item "
        "WHERE pi_no = %s AND status = 'TO_PUSH' AND delivery_batch_no IS NULL AND suspend IS NULL"
        + cond
        + " GROUP BY factory_code, bill_no ORDER BY bill_no",
        pi,
        *args,
    )
    idle: dict[str, bool] = {}
    out = []
    for r in rows:
        org = r["factory_code"]
        if facs is not None and org not in facs:
            continue
        if org not in idle:
            idle[org] = push_idle(org, pi, delivery.task_state(org, pi), delivery.remaining_pushes(org, pi))
        if idle[org]:
            materials = (r["m"] or "").split(",")
            out.append({"org": org, "bill_no": r["bill_no"], "qty": int(r["n"]), "materials": materials})
    return out


def preview(cu: CurrentUser, pi_in: str | None, bills: list[str] | None, lines: list[dict] | None) -> dict:
    """分配面板：按生产组织汇总枚数、单据和物料，标出是否对接 MES；按钮写「分配 N 枚（推送 M 次）」。
    N 含已分配、还没开始推送的号（重新分配，requeue 为其枚数）。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    _check_picked(cu, bills, lines)
    facs = cu.read_factories(None)
    groups, skipped = _groups(pi, bills, lines, facs)
    idle = _idle(pi, bills, lines, facs)
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
    for x in idle:
        o = by_org.setdefault(x["org"], {"org_code": x["org"], "qty": 0, "bills": [], "materials": set()})
        o["qty"] += x["qty"]
        o["bills"].append(x["bill_no"])
        o["materials"].update(x["materials"])
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
        "requeue": sum(x["qty"] for x in idle),
    }


def allocate(
    cu: CurrentUser, pi_in: str | None, bills: list[str] | None = None, lines: list[dict] | None = None
) -> dict:
    """分配：范围内待分配的号一次全部挂到生产组织；对接 MES 的改为待推送（未入批）并自动推送，未对接的改为已分配。
    范围内已分配、还没开始推送的号（组织后开启「对接 MES」）一并重新分配：启动所在组织本 PI 的推送。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    _check_picked(cu, bills, lines)
    with locks.with_pi_lock(pi):
        facs = cu.read_factories(None)
        # 几张 PI 同时生成、分配时号池上可能死锁（MySQL 1213，整笔已回滚）：PI 操作锁仍在手里，整笔重做
        total, started, requeue, groups, skipped = retry_deadlock(lambda: _allocate_tx(cu, pi, bills, lines, facs))
        for org in sorted(started | set(requeue)):
            delivery.start_task(cu, org, pi)
    return {
        "pi_no": pi,
        "qty": total + sum(requeue.values()),
        "requeue": sum(requeue.values()),
        "skipped": skipped,
        "orgs": sorted({g["org"] for g in groups} | set(requeue)),
    }


def _allocate_tx(
    cu: CurrentUser, pi: str, bills: list[str] | None, lines: list[dict] | None, facs: tuple[str, ...] | None
) -> tuple[int, set[str], dict[str, int], list[dict], list[dict]]:
    """分配的事务部分（在 PI 操作锁内）；返回（分配枚数，要启动推送的组织，重新分配的组织与枚数，分组，跳过的单据）。"""
    with db.tx():
        groups, skipped = _groups(pi, bills, lines, facs)
        idle = _idle(pi, bills, lines, facs)
        if not groups and not idle:
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
                f"SELECT bill_seq, COUNT(*) n, {util.sn_min()} a, {util.sn_max()} b FROM sn_item "
                "WHERE pi_no = %s AND bill_no = %s "
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
            bill_stats.touch(pi, g["bill_no"])
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
        requeue: dict[str, int] = {}
        for x in idle:
            requeue[x["org"]] = requeue.get(x["org"], 0) + x["qty"]
        for org, n in sorted(requeue.items()):
            audit.record(
                cu,
                audit.entry(audit.SN_PUSH_RESUME).factory(org).pi(pi).count(n).info(f"key={pi} from=allocate"),
            )
        if groups:
            changes.refresh_items({g["bill_no"] for g in groups}, quick=True)
    return total, started, requeue, groups, skipped


# ================================================================== 分配页左侧单据列表


def bill_state(pending: int, to_push: int) -> str:
    """单据状态：有待分配的号 = 待分配；号都已分配但还有待推送的（推送中、等回执、推送失败、暂停等）= 待处理；
    否则已分配。"""
    if pending:
        return BILL_PENDING
    return BILL_PROCESSING if to_push else BILL_ALLOCATED


def bills(cu: CurrentUser, q: str | None, tab: str | None, page: util.Page) -> dict:
    """分配页左侧：按号取单据（号现在挂的 PI + 单据），每行 SN 枚数与状态，分页列出全部单据；q 只按 PI、单据编号匹配。
    tab：TO_ALLOCATE = 待分配、待处理；ALLOCATED = 已分配；不传为全部。
    按处理时间（单据下的号最近一次变动：生成、分配、推送、回执、转移等）越近越前。
    普通操作员只看授权组织的号。返回分页结果，另带上方汇总：待分配枚数、待推送（未入批）枚数。
    单据各数查单据汇总（bill_stats，每个 PI + 单据 + 号上的组织一行），不对整个号池汇总；口径与按号汇总相同：
    授权范围条件照样按组织（待分配的按单据台账的组织）套在汇总行上，再按 PI + 单据相加。"""
    bill_stats.refresh()
    facs = cu.read_factories(None)
    isql, iargs = factories.item_scope_sql(facs)
    where, args = " WHERE 1=1" + isql, list(iargs)
    text = util.trim(q)
    if text:
        where += " AND (pi_no LIKE %s OR bill_no LIKE %s)"
        args += [util.like_any(text)] * 2
    open_ = "SUM(pending) > 0 OR SUM(to_push) > 0"
    having = ""
    t = util.trim(tab)
    if t == TO_ALLOCATE:
        having = f" HAVING {open_}"
    elif t == ALLOCATED:
        having = f" HAVING NOT ({open_})"
    grouped = (
        "SELECT pi_no, bill_no, SUM(qty) qty, SUM(pending) pending, SUM(to_push) to_push, "
        "SUM(suspended) suspended, MAX(handled_at) handled_at FROM sn_bill_stat"
        + where
        + " GROUP BY pi_no, bill_no"
        + having
    )
    total = db.count("SELECT COUNT(*) FROM (" + grouped + ") g", *args)
    rows = db.all(
        grouped + " ORDER BY handled_at DESC, bill_no DESC, pi_no LIMIT %s, %s", *args, page.offset, page.size
    )
    items = []
    for r in rows:
        pending, to_push = int(r["pending"] or 0), int(r["to_push"] or 0)
        items.append(
            {
                "pi_no": r["pi_no"],
                "bill_no": r["bill_no"],
                "qty": int(r["qty"]),
                "pending_alloc": pending,
                "to_push": to_push,
                "suspended": int(r["suspended"] or 0),
                "state": bill_state(pending, to_push),
                "handled_at": util.jv(r["handled_at"]),
            }
        )
    s = db.one(
        "SELECT SUM(status = 'PENDING_ALLOC') pending, "
        "SUM(status = 'TO_PUSH' AND delivery_batch_no IS NULL AND suspend IS NULL) unbatched "
        "FROM sn_item WHERE status IN ('PENDING_ALLOC', 'TO_PUSH')" + isql,
        *iargs,
    )
    return util.page_result(items, total, page) | {
        "summary": {"pending_alloc": int(s["pending"] or 0), "unbatched": int(s["unbatched"] or 0)}
    }


# ================================================================== 分配页详情


def context(cu: CurrentUser, pi_in: str | None) -> dict:
    """分配详情：本 PI 各单据的物料行（附件项次、订单数量、客户 P/N、十进制流水起止、SN 枚数、
    待分配 / 待推送 / 推送失败枚数、号段、分到哪个生产组织、是否对接 MES、不能分配的原因）及单据状态，
    各组织的推送进度（推送批次由页面分页另取）。
    页面按左侧页签只显示相应状态的单据；勾选的物料行只分配其中待分配的号。"""
    pi = util.trim(pi_in)
    if not pi:
        raise biz(ErrorCode.PI_REQUIRED)
    facs = cu.read_factories(None)
    isql, iargs = factories.item_scope_sql(facs, "i.")
    rows = db.all(
        # 导入行（单据序号 0）可含多个物料：列出全部（逗号分隔）；生成的物料行一行一个物料
        "SELECT i.bill_no, i.bill_seq, IF(MAX(i.origin = 'IMPORT'), COALESCE(GROUP_CONCAT(DISTINCT "
        "NULLIF(i.material_code, '') ORDER BY i.material_code SEPARATOR ','), ''), MAX(i.material_code)) "
        "material_code, COUNT(*) n, "
        "SUM(i.status = 'PENDING_ALLOC') pending, SUM(i.status = 'TO_PUSH') to_push, "
        f"SUM(i.status = 'TO_PUSH' AND d.id IS NOT NULL AND {_BROKEN}) failed, "
        "MAX(CASE WHEN i.status = 'PENDING_ALLOC' THEN i.gen_org_code END) gen_org, MAX(i.factory_code) org, "
        "MIN(i.sn) a, MAX(i.sn) b, MAX(i.origin = 'IMPORT') imported, "
        # 附件项次：号上的值（生成时写定）；导入行可有多个，同物料一样列出全部
        "IF(MAX(i.origin = 'IMPORT'), COALESCE(GROUP_CONCAT(DISTINCT NULLIF(i.attach_no, '') "
        "ORDER BY i.attach_no SEPARATOR ','), ''), MAX(i.attach_no)) attach_no, "
        # 十进制流水号：本行生成的号中最小、最大的十进制流水（导入的号没有流水）
        "MIN(CASE WHEN i.origin = 'GEN' THEN i.seq_dec END) s, MAX(CASE WHEN i.origin = 'GEN' THEN i.seq_dec END) e "
        "FROM sn_item i LEFT JOIN sn_delivery_batch d ON d.batch_no = i.delivery_batch_no "
        "WHERE i.pi_no = %s" + isql + " GROUP BY i.bill_no, i.bill_seq ORDER BY i.bill_no, i.bill_seq",
        pi,
        *iargs,
    )
    mo = _order_lines(sorted({r["bill_no"] for r in rows}))
    names = factories.names()
    targets: dict[str, tuple[str | None, str | None]] = {}
    lines = []
    for r in rows:
        pending = int(r["pending"] or 0)
        block = None
        if pending:
            if r["bill_no"] not in targets:
                targets[r["bill_no"]] = target_org(r["bill_no"])
            org, block = targets[r["bill_no"]]
            org = None if block else org or r["gen_org"]
        else:
            org = r["org"]
        f = factories.get(org) if org else None
        k3 = mo.get((r["bill_no"], int(r["bill_seq"])))
        lines.append(
            {
                "bill_no": r["bill_no"],
                "seq": r["bill_seq"],
                "material_code": r["material_code"],
                "attach_no": r["attach_no"] or "",
                # 订单数量、客户 P/N 取金蝶快照的物料行（只显示）；导入行、已不在快照的物料行为空
                "order_qty": None if k3 is None else orders.sn_qty(k3),
                "customer_pn": "" if k3 is None else k3["customer_pn"],
                "start_seq": None if r["s"] is None else int(r["s"]),
                "end_seq": None if r["e"] is None else int(r["e"]),
                "qty": int(r["n"]),
                "pending_alloc": pending,
                "to_push": int(r["to_push"] or 0),
                "failed": int(r["failed"] or 0),
                "start_sn": r["a"],
                "end_sn": r["b"],
                "org_code": org,
                "org_name": names.get(org, "") if org else "",
                "mes_enabled": bool(f and f["mes_enabled"]),
                "skip": block,
                # SN 导入的号（挂在单据序号 0 上；物料为导入时填的，可能没有）
                "imported": bool(r["imported"]),
            }
        )
    states: dict[str, list[int]] = {}
    for x in lines:
        st = states.setdefault(x["bill_no"], [0, 0])
        st[0] += x["pending_alloc"]
        st[1] += x["to_push"]
    bill_states = {no: bill_state(*v) for no, v in states.items()}
    for x in lines:
        x["bill_state"] = bill_states[x["bill_no"]]
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
    }


def _order_lines(bill_nos: list[str]) -> dict[tuple[str, int], dict]:
    """这些单据在金蝶快照中的物料行：(单据编号, 序号) → 订单数量、客户 P/N（分配页物料行只显示）。"""
    if not bill_nos:
        return {}
    return {
        (r["bill_no"], int(r["seq"])): r
        for r in db.all(
            f"SELECT bill_no, seq, qty, customer_pn FROM sn_prd_mo WHERE bill_no IN ({marks(len(bill_nos))})",
            *bill_nos,
        )
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
                # 推送中断：底部「分配」变为「继续推送」；idle：已分配还没开始推送的枚数，点「分配」一并推送
                "interrupted": bool(f and f["mes_enabled"]) and push_interrupted(t, rem),
                "idle": rem["unbatched"] if push_idle(org, pi, t, rem) else 0,
            }
        )
    return out
