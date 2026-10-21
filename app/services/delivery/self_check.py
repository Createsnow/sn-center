"""每日交付自检（5.2.5 第 5 条）：发现的问题记为交付告警（CHECK），列入首页并留痕。"""

from __future__ import annotations

from datetime import timedelta

import structlog

from app.core import utils as util
from app.core.config import settings
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit

log = structlog.get_logger()


def run() -> dict:
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
