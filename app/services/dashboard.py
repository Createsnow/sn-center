"""首页看板（F-20）。只读；每日交付自检在 delivery.self_check。

在途：待推送、待领取、申请中、暂停交付各多少枚（待分配只给总部看）；推送中断的批次（含改挂、转出、整批拒绝）；
拉取后超过告警时长仍未回执的批次；待处理的转组织申请；交付告警（疑似重复交付、版本过期、自检问题）；订单变更提醒。
今日（按账户显示时区）：生成、分配、推送与拉取（按确认方式）、MES 取批、领取（对接 MES 组织单独标出）、转单。
"""

from __future__ import annotations

from datetime import timedelta

import structlog

from app.core import utils as util
from app.core.config import settings
from app.core.security import CurrentUser
from app.db.session import db
from app.services import changes, delivery, factories

log = structlog.get_logger()


def _interrupted_sql(fsql: str) -> str:
    return (
        "FROM sn_delivery_batch WHERE (result = 'FAILED' OR (result = 'UNCONFIRMED' AND push_state = 'EXHAUSTED'))"
        + fsql
    )


def load(cu: CurrentUser) -> dict:
    facs = cu.factories or None
    fsql, fargs = factories.scope_sql(facs)
    counts = dict.fromkeys(("PENDING_ALLOC", "TO_PUSH", "TO_ACQUIRE", "APPLYING"), 0)
    for r in db.all(
        "SELECT status, COUNT(*) c FROM sn_item WHERE status IN ('PENDING_ALLOC','TO_PUSH','TO_ACQUIRE','APPLYING')"
        + fsql
        + " GROUP BY status",
        *fargs,
    ):
        counts[r["status"]] = int(r["c"])
    if not cu.is_admin:
        counts.pop("PENDING_ALLOC")
    suspended = db.count("SELECT COUNT(*) FROM sn_item WHERE suspend IS NOT NULL" + fsql, *fargs)
    interrupted = db.count("SELECT COUNT(*) " + _interrupted_sql(fsql), *fargs)
    cutoff = util.now() - timedelta(minutes=settings.batch_unconfirmed_alert_minutes)
    stale_pull = db.count(
        "SELECT COUNT(*) FROM sn_delivery_batch WHERE result = 'UNCONFIRMED' AND source = 'PULL' AND created_at < %s"
        + fsql,
        cutoff,
        *fargs,
    )
    if facs is None:
        pending_tr = db.count("SELECT COUNT(*) FROM sn_transfer WHERE status = 'PENDING'")
    else:
        fr, fa = factories.scope_sql(facs, "from_factory")
        to, ta = factories.scope_sql(facs, "to_factory")
        pending_tr = db.count(
            f"SELECT COUNT(*) FROM sn_transfer WHERE status = 'PENDING' AND ((1=1{fr}) OR (1=1{to}))", *fa, *ta
        )
    alerts = {
        r["alert_type"]: int(r["n"])
        for r in db.all(
            "SELECT alert_type, COUNT(*) n FROM sn_delivery_alert WHERE status = 'OPEN'"
            + fsql
            + " GROUP BY alert_type",
            *fargs,
        )
    }
    # 今日：按账户显示时区的自然日
    start = util.local_day_start(factories.tz_offset(cu.factories))
    today = {
        r["action"]: int(r["q"] or 0)
        for r in db.all(
            "SELECT action, SUM(qty) q FROM sn_audit WHERE created_at >= %s AND result = 'OK' AND action IN "
            "('SN_GENERATE','SN_ALLOCATE','SN_PULL','TRANSFER_BILL')" + fsql + " GROUP BY action",
            start,
            *fargs,
        )
    }
    for r in db.all(
        "SELECT SUM(qty) q, detail LIKE %s mes FROM sn_audit WHERE created_at >= %s AND result = 'OK' "
        "AND action = 'SN_ACQUIRE'" + fsql + " GROUP BY mes",
        "%mes_org=1%",
        start,
        *fargs,
    ):
        today["SN_ACQUIRE_MES" if r["mes"] else "SN_ACQUIRE"] = int(r["q"] or 0)
    for r in db.all(
        "SELECT confirm_method, SUM(qty) q FROM sn_delivery_batch WHERE batch_type = 'DELIVERY' AND confirmed_at >= %s"
        + fsql
        + " GROUP BY confirm_method",
        start,
        *fargs,
    ):
        today[f"CONFIRM_{r['confirm_method']}"] = int(r["q"] or 0)
    out = {
        "status_counts": counts,
        "suspended": suspended,
        "push_interrupted": interrupted,
        "pull_unconfirmed": stale_pull,
        "pending_transfers": pending_tr,
        "delivery_alerts": alerts,
        "order_alerts": changes.open_alert_count(cu),
        "today": today,
        "today_start": util.fmt(start),
        "tz_offset_min": factories.tz_offset(cu.factories),
        "total_generated": db.count("SELECT COALESCE(SUM(generated_qty), 0) FROM sn_pi_counter")
        if not cu.bound
        else None,
    }
    if cu.is_admin:
        out["running_jobs"] = db.count("SELECT COUNT(*) FROM sn_gen_job WHERE status = 'RUNNING'")
        out["recent_jobs"] = [
            {k: util.jv(v) for k, v in r.items()}
            for r in db.all(
                "SELECT gen_batch, pi_no, qty, done_qty, status, error_msg, created_by, created_at FROM sn_gen_job "
                "ORDER BY id DESC LIMIT 8"
            )
        ]
        out["recent_failures"] = [
            delivery.batch_json(b)
            for b in db.all(
                "SELECT * "
                + _interrupted_sql("")
                + " UNION ALL SELECT * FROM sn_delivery_batch WHERE result = 'UNCONFIRMED' "
                "AND source = 'PULL' AND created_at < %s ORDER BY updated_at DESC LIMIT 20",
                cutoff,
            )
        ]
        out["open_delivery_alerts"] = [
            {k: util.jv(v) for k, v in r.items()}
            for r in db.all("SELECT * FROM sn_delivery_alert WHERE status = 'OPEN' ORDER BY id DESC LIMIT 20")
        ]
    return out
