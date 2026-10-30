"""首页看板（F-20）。只读；每日交付自检在 delivery.self_check。

在途：待推送、已分配、申请中、暂停交付各多少枚（待分配不给审计员看）；推送中断的批次（含改挂、转出、整批拒绝）；
拉取后超过告警时长仍未回执的批次；待处理的转组织申请；交付告警（每日自检问题）；订单变更提醒。
今日（按账户显示时区）：生成、分配、推送与拉取（按确认方式）、MES 取批、转单。
"""

from __future__ import annotations

import json
from datetime import timedelta

import structlog

from app.core import utils as util
from app.core.config import settings
from app.core.security import CurrentUser
from app.db.session import db
from app.services import changes, delivery, factories, generate

log = structlog.get_logger()


def _interrupted_sql(fsql: str) -> str:
    return (
        "FROM sn_delivery_batch WHERE (result = 'FAILED' OR (result = 'UNCONFIRMED' AND push_state = 'EXHAUSTED'))"
        + fsql
    )


def load(cu: CurrentUser) -> dict:
    facs = cu.read_factories(None)
    fsql, fargs = factories.scope_sql(facs)
    # 号按所在组织：待分配的号还没有组织，按所在单据的组织算
    isql, iargs = factories.item_scope_sql(facs)
    counts = dict.fromkeys(("PENDING_ALLOC", "TO_PUSH", "ALLOCATED"), 0)
    for r in db.all(
        "SELECT status, COUNT(*) c FROM sn_item WHERE status IN ('PENDING_ALLOC','TO_PUSH','ALLOCATED')"
        + isql
        + " GROUP BY status",
        *iargs,
    ):
        counts[r["status"]] = int(r["c"])
    if cu.is_auditor:
        counts.pop("PENDING_ALLOC")
    suspended = db.count("SELECT COUNT(*) FROM sn_item WHERE suspend IS NOT NULL" + isql, *iargs)
    interrupted = db.count("SELECT COUNT(*) " + _interrupted_sql(fsql), *fargs)
    cutoff = util.now() - timedelta(minutes=settings.batch_unconfirmed_alert_minutes)
    stale_pull = db.count(
        "SELECT COUNT(*) FROM sn_delivery_batch WHERE result = 'UNCONFIRMED' AND source = 'PULL' AND created_at < %s"
        + fsql,
        cutoff,
        *fargs,
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
        "SELECT confirm_method, SUM(qty) q FROM sn_delivery_batch WHERE batch_type <> 'TRANSFER_OUT' "
        "AND confirmed_at >= %s" + fsql + " GROUP BY confirm_method",
        start,
        *fargs,
    ):
        today[f"CONFIRM_{r['confirm_method']}"] = int(r["q"] or 0)
    out = {
        "status_counts": counts,
        "suspended": suspended,
        "push_interrupted": interrupted,
        "pull_unconfirmed": stale_pull,
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
    elif cu.is_operator:
        # 只看含授权组织物料行的生成任务
        def mine(j: dict) -> bool:
            return any(generate.in_scope(facs, x) for x in json.loads(j["plan_json"])["items"])

        out["running_jobs"] = sum(
            1 for j in db.all("SELECT plan_json FROM sn_gen_job WHERE status = 'RUNNING'") if mine(j)
        )
        out["recent_jobs"] = [
            {k: util.jv(v) for k, v in r.items() if k != "plan_json"}
            for r in [
                j
                for j in db.all(
                    "SELECT gen_batch, pi_no, qty, done_qty, status, error_msg, created_by, created_at, plan_json "
                    "FROM sn_gen_job ORDER BY id DESC LIMIT 200"
                )
                if mine(j)
            ][:8]
        ]
    if not cu.is_auditor:
        # 最近失败的批次、未处理的交付告警（普通操作员只看授权组织的）
        # 推送中断与拉取后超时未回执用一条查询：MES 拉取建立、续推又重试用尽的批次两者都符合，只列一行
        out["recent_failures"] = [
            delivery.batch_json(b)
            for b in db.all(
                "SELECT * FROM sn_delivery_batch WHERE (result = 'FAILED' OR (result = 'UNCONFIRMED' "
                "AND (push_state = 'EXHAUSTED' OR (source = 'PULL' AND created_at < %s))))"
                + fsql
                + " ORDER BY updated_at DESC, id DESC LIMIT 20",
                cutoff,
                *fargs,
            )
        ]
        out["open_delivery_alerts"] = [
            {k: util.jv(v) for k, v in r.items()}
            for r in db.all(
                "SELECT * FROM sn_delivery_alert WHERE status = 'OPEN'" + fsql + " ORDER BY id DESC LIMIT 20", *fargs
            )
        ]
    return out
