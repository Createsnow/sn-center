"""首页看板：在途状态枚数（避免对历史已打印的大量号做全表计数）、待处理转厂、生成任务、今日动作。"""

from __future__ import annotations

from datetime import datetime, time

from app.core import utils as util
from app.core.security import CurrentUser
from app.db.session import db


def load(cu: CurrentUser) -> dict:
    fac = cu.factory_code if cu.bound else None
    counts = {s: 0 for s in ("PENDING_ALLOC", "TO_ACQUIRE", "TO_PRINT", "APPLYING")}
    where = " WHERE status IN ('PENDING_ALLOC','TO_ACQUIRE','TO_PRINT','APPLYING')"
    args: list = []
    if fac is not None:
        where += " AND factory_code = %s"
        args.append(fac)
    for r in db.all("SELECT status, COUNT(*) c FROM sn_item" + where + " GROUP BY status", *args):
        counts[r["status"]] = int(r["c"])
    total = db.count("SELECT COALESCE(SUM(generated_qty + imported_qty), 0) FROM sn_pi_counter")
    if fac is None:
        pending = db.count("SELECT COUNT(*) FROM sn_transfer WHERE status = 'PENDING'")
    else:
        pending = db.count(
            "SELECT COUNT(*) FROM sn_transfer WHERE status = 'PENDING' AND (from_factory = %s OR to_factory = %s)",
            fac,
            fac,
        )
    running = db.count("SELECT COUNT(*) FROM sn_gen_job WHERE status = 'RUNNING'")
    tw = (
        " WHERE created_at >= %s AND result = 'OK' AND action IN "
        "('SN_GENERATE','SN_ALLOCATE','SN_ACQUIRE','SN_PRINT','SN_CALLBACK')"
    )
    targs: list = [datetime.combine(util.today(), time())]
    if fac is not None:
        tw += " AND factory_code = %s"
        targs.append(fac)
    today = {
        r["action"]: int(r["q"])
        for r in db.all("SELECT action, COALESCE(SUM(qty), 0) q FROM sn_audit" + tw + " GROUP BY action", *targs)
    }
    jobs = []
    if cu.is_admin:
        jobs = [
            {k: util.jv(v) for k, v in r.items()}
            for r in db.all(
                "SELECT id, bill_no, pi_no, factory_code, qty, done_qty, status, error_msg, created_at "
                "FROM sn_gen_job ORDER BY id DESC LIMIT 8"
            )
        ]
    return {
        "status_counts": counts,
        "total_generated": total if cu.is_admin or not cu.bound else 0,
        "pending_transfers": pending,
        "running_jobs": running,
        "today_actions": today,
        "recent_jobs": jobs,
    }
