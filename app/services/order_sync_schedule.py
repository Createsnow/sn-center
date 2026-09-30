"""生产订单定时全量同步：总部在订单页开关、设间隔；后台线程每 30 秒看一次是否到期。

设置只有一行（sn_order_sync_schedule.id = 1）。到期时用条件更新「抢」这一次：
next_run_at 已到且仍开启的那一行被推到下一次，只有改到这一行的实例去跑，多实例部署也不会重复同步。
每次执行与 POST /orders/sync（全量）完全相同，操作人记为 system；成败都写回设置行并留痕。
"""

from __future__ import annotations

from datetime import timedelta

import structlog

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit, orders

log = structlog.get_logger()

#: 间隔上下限（分钟）：最短 10 分钟，避免频繁全量拉金蝶；最长 7 天
MIN_INTERVAL = 10
MAX_INTERVAL = 7 * 24 * 60
#: 后台线程检查是否到期的周期（秒）
POLL_SECONDS = 30

_ID = 1


def _row() -> dict:
    r = db.one("SELECT * FROM sn_order_sync_schedule WHERE id = %s", _ID)
    if r is None:  # 迁移已插入默认行；被误删时补回
        db.exec("INSERT IGNORE INTO sn_order_sync_schedule (id, enabled, interval_minutes) VALUES (%s, 0, 1440)", _ID)
        r = db.one("SELECT * FROM sn_order_sync_schedule WHERE id = %s", _ID)
    return r


def status() -> dict:
    r = _row()
    return {
        "enabled": bool(r["enabled"]),
        "interval_minutes": int(r["interval_minutes"]),
        "next_run_at": util.fmt(r["next_run_at"]) if r["enabled"] else None,
        "last_run_at": util.fmt(r["last_run_at"]),
        "last_ok": None if r["last_ok"] is None else bool(r["last_ok"]),
        "last_bills": r["last_bills"],
        "last_rows": r["last_rows"],
        "last_message": r["last_message"],
        "updated_by": r["updated_by"],
        "updated_at": util.fmt(r["updated_at"]),
        "min_interval": MIN_INTERVAL,
        "max_interval": MAX_INTERVAL,
    }


def save(cu: CurrentUser, enabled: bool, interval_minutes: int | None) -> dict:
    """开启 / 关闭、改间隔。开启或改了间隔时，下次执行 = 现在 + 间隔；只改其他不动下次时间。"""
    cur = _row()
    interval = int(cur["interval_minutes"]) if interval_minutes is None else interval_minutes
    if not MIN_INTERVAL <= interval <= MAX_INTERVAL:
        raise biz(ErrorCode.ORDER_SYNC_INTERVAL_INVALID, min=MIN_INTERVAL, max=MAX_INTERVAL)
    now = util.now()
    if not enabled:
        nxt = None
    elif not cur["enabled"] or interval != cur["interval_minutes"] or cur["next_run_at"] is None:
        nxt = now + timedelta(minutes=interval)
    else:
        nxt = cur["next_run_at"]
    with db.tx():
        db.exec(
            "UPDATE sn_order_sync_schedule SET enabled = %s, interval_minutes = %s, next_run_at = %s, "
            "updated_by = %s, updated_at = %s WHERE id = %s",
            1 if enabled else 0,
            interval,
            nxt,
            cu.emp_no,
            now,
            _ID,
        )
        audit.record(
            cu,
            audit.entry(audit.ORDER_SYNC_SCHEDULE).info(
                f"enabled={enabled} interval_minutes={interval} "
                f"before=enabled={bool(cur['enabled'])} interval_minutes={cur['interval_minutes']}"
            ),
        )
    return status()


def _claim() -> bool:
    """到期且开启时把下次时间推后一个间隔；改到这一行 = 本实例负责这一次。"""
    now = util.now()
    return (
        db.exec(
            "UPDATE sn_order_sync_schedule SET next_run_at = DATE_ADD(%s, INTERVAL interval_minutes MINUTE), "
            "last_run_at = %s WHERE id = %s AND enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= %s",
            now,
            now,
            _ID,
            now,
        )
        == 1
    )


def _finish(result: dict | None, error: str = "") -> None:
    """成功只更新同步量；失败保留上次成功的量，只记原因。"""
    if result is None:
        db.exec(
            "UPDATE sn_order_sync_schedule SET last_ok = 0, last_message = %s WHERE id = %s", util.cut(error, 500), _ID
        )
    else:
        db.exec(
            "UPDATE sn_order_sync_schedule SET last_ok = 1, last_bills = %s, last_rows = %s, last_message = '' "
            "WHERE id = %s",
            result["bills"],
            result["rows"],
            _ID,
        )


def run_due() -> bool:
    """后台线程调用：到期就全量同步一次。返回本次是否执行了同步。"""
    if not _claim():
        return False
    cu = CurrentUser.system()
    try:
        r = orders.sync_all(cu)
    except Exception as e:  # noqa: BLE001
        msg = getattr(e, "message", None) or str(e) or type(e).__name__
        log.warning("scheduled_order_sync_failed", err=msg)
        _finish(None, msg)
        audit.record_isolated(cu, audit.entry(audit.ORDER_SYNC).info("mode=scheduled").fail(msg))
        return True
    log.info("scheduled_order_sync_done", bills=r["bills"], rows=r["rows"])
    _finish(r)
    return True
