"""首页看板（F-20）与每日交付自检（5.2.5 第 5 条）。

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
from app.services import audit, changes, delivery, factories

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


# ================================================================== 每日交付自检


def self_check() -> dict:
    """成功批次回执枚数与批次枚数是否一致；未经转移，同一 SN 是否在两个成功交付批次（推送、拉取）或交付批次与领取中；
    当天的冲突、过期回执；未确认超过告警时长的批次。人工确认的批次没有逐枚回执，单列。问题列入首页告警并留痕。"""
    now = util.now()
    since = now - timedelta(days=2)
    found: list[tuple[str, str, str | None, str | None]] = []
    for b in db.all(
        "SELECT b.factory_code, b.batch_no, b.qty, (SELECT COUNT(*) FROM sn_delivery_item d WHERE d.batch_id = b.id "
        "AND d.receipt_result IS NOT NULL) n FROM sn_delivery_batch b WHERE b.result IN ('SUCCESS','SUCCESS_CONFLICT') "
        "AND b.confirm_method <> 'MANUAL' AND b.confirmed_at >= %s",
        since,
    ):
        if int(b["n"]) != int(b["qty"]):
            found.append((b["factory_code"], b["batch_no"], None, f"回执枚数 {b['n']} 与批次枚数 {b['qty']} 不一致"))
    for r in db.all(
        "SELECT d.sn, d.rev, COUNT(DISTINCT d.batch_id) n, GROUP_CONCAT(DISTINCT b.batch_no) nos, "
        "MIN(b.factory_code) f "
        "FROM sn_delivery_item d JOIN sn_delivery_batch b ON b.id = d.batch_id WHERE b.batch_type = 'DELIVERY' "
        "AND b.result IN ('SUCCESS','SUCCESS_CONFLICT') AND d.sn IN (SELECT d2.sn FROM sn_delivery_item d2 "
        "JOIN sn_delivery_batch b2 ON b2.id = d2.batch_id WHERE b2.confirmed_at >= %s) GROUP BY d.sn, d.rev HAVING n "
        "> 1 "
        "LIMIT 500",
        since,
    ):
        found.append((r["f"], r["nos"][:40], r["sn"], f"同一 SN（rev {r['rev']}）出现在多个成功交付批次：{r['nos']}"))
    for r in db.all(
        "SELECT i.sn, i.factory_code, i.acquire_batch_no, b.batch_no FROM sn_item i JOIN sn_delivery_item d ON "
        "d.item_id = i.id "
        "AND d.rev = i.rev JOIN sn_delivery_batch b ON b.id = d.batch_id WHERE i.status = 'ACQUIRED' "
        "AND b.batch_type = 'DELIVERY' AND b.result IN ('SUCCESS','SUCCESS_CONFLICT') AND i.delivered_at >= %s LIMIT "
        "500",
        since,
    ):
        found.append(
            (r["factory_code"], r["batch_no"], r["sn"], f"同一 SN 既在成功交付批次又在领取批次 {r['acquire_batch_no']}")
        )
    cutoff = now - timedelta(minutes=settings.batch_unconfirmed_alert_minutes)
    unconfirmed = db.all(
        "SELECT factory_code, batch_no, created_at FROM sn_delivery_batch WHERE result = 'UNCONFIRMED' AND "
        "created_at < %s",
        cutoff,
    )
    for b in unconfirmed:
        found.append(
            (b["factory_code"], b["batch_no"], None, f"批次建立于 {util.fmt(b['created_at'])}，超过告警时长仍未确认")
        )
    day = util.local_day_start(util.DEFAULT_TZ_OFFSET_MIN)
    conflicts = db.count(
        "SELECT COUNT(*) FROM sn_delivery_alert WHERE alert_type IN ('CONFLICT','STALE') AND created_at >= %s", day
    )
    manual = db.count(
        "SELECT COUNT(*) FROM sn_delivery_batch WHERE confirm_method = 'MANUAL' AND confirmed_at >= %s", since
    )
    created = 0
    for org, batch_no, sn, detail in found:
        if db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_delivery_alert WHERE alert_type = 'CHECK' AND status = 'OPEN' "
            "AND batch_no = %s AND sn <=> %s)",
            batch_no,
            sn,
        ):
            continue
        db.exec(
            "INSERT INTO sn_delivery_alert(alert_type, factory_code, batch_no, sn, detail, created_at) VALUES "
            "('CHECK',%s,%s,%s,%s,%s)",
            org or "",
            batch_no,
            sn,
            util.cut(detail, 1000),
            now,
        )
        created += 1
    result = {
        "problems": len(found),
        "new_alerts": created,
        "conflict_stale_today": conflicts,
        "manual_confirmed": manual,
    }
    audit.record_isolated(
        CurrentUser.system(),
        audit.entry(audit.SELF_CHECK).count(len(found)).info(" ".join(f"{k}={v}" for k, v in result.items())),
    )
    log.info("delivery_self_check", **result)
    return result
