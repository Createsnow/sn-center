"""首页看板：在途状态枚数（避免对历史已打印的大量号做全表计数）、待处理转厂、生成任务、今日动作。"""

from __future__ import annotations

from datetime import datetime, time

from app.core import utils as util
from app.core.security import CurrentUser
from app.db.session import db
from app.services.factories import scope_sql


def load(cu: CurrentUser) -> dict:
    facs = cu.factories or None
    counts = {s: 0 for s in ("PENDING_ALLOC", "TO_ACQUIRE", "TO_PRINT", "APPLYING")}
    fsql, args = scope_sql(facs)
    where = " WHERE status IN ('PENDING_ALLOC','TO_ACQUIRE','TO_PRINT','APPLYING')" + fsql
    for r in db.all("SELECT status, COUNT(*) c FROM sn_item" + where + " GROUP BY status", *args):
        counts[r["status"]] = int(r["c"])
    total = db.count("SELECT COALESCE(SUM(generated_qty), 0) FROM sn_pi_counter")
    if facs is None:
        pending = db.count("SELECT COUNT(*) FROM sn_transfer WHERE status = 'PENDING'")
    else:
        fr, fa = scope_sql(facs, "from_factory")
        to, ta = scope_sql(facs, "to_factory")
        pending = db.count(
            f"SELECT COUNT(*) FROM sn_transfer WHERE status = 'PENDING' AND ((1=1{fr}) OR (1=1{to}))", *fa, *ta
        )
    running = db.count("SELECT COUNT(*) FROM sn_gen_job WHERE status = 'RUNNING'")
    tw = (
        " WHERE created_at >= %s AND result = 'OK' AND action IN "
        "('SN_GENERATE','SN_ALLOCATE','SN_ACQUIRE','SN_PRINT','SN_CALLBACK')"
    )
    fsql, fargs = scope_sql(facs)
    tw += fsql
    targs: list = [datetime.combine(util.today(), time()), *fargs]
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
