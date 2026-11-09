"""按月分区维护：

- sn_audit（RANGE COLUMNS created_at）提前建好未来 N 个月分区，兜底分区 pmax 始终为空，拆分几乎零成本
  （sn_item 不分区：号不删不归档，查询也不按月份）；
- 痕迹保存期（默认 3 年，0 = 永久）之外的整月分区先整体拷入 sn_audit_archive，再 DROP PARTITION；
- 同步生产组织、每日交付自检；
- 归档表设总保存上限（SN_AUDIT_ARCHIVE_RETENTION_DAYS，默认 5 年，0 = 永久，不短于在线保存期）：
  发生时间超过上限的归档痕迹分批删除，删除本身留痕。

多实例部署时用 MySQL 命名锁 sn_partition 保证同一时刻只有一个实例做 DDL；
命名锁只防同时执行，防不了先后重复执行，所以每日维护另按 sn_seq 里记的上次完成时间去重（DAILY_MIN_GAP）。
"""

from __future__ import annotations

import time
from datetime import date, datetime, timedelta

import structlog

from app.core import utils as util
from app.core.config import settings
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit

log = structlog.get_logger()

#: 两次每日维护（归档、清理、生产组织同步、交付自检）的最短间隔：多实例各自按 cron 触发、相继启动时，
#: 这段时间内已有实例做完的直接跳过
DAILY_MIN_GAP = timedelta(hours=1)
#: sn_seq 里记最近一次每日维护完成时间（Unix 秒）的行；# 开头不会与单号序列重名
DAILY_MARK = "#daily_maintain"


def _partitions(c, table: str) -> list[tuple[str, str | None]]:
    """分区名与上界（pmax 的上界为 None）。"""
    c.execute(
        "SELECT PARTITION_NAME AS n, PARTITION_DESCRIPTION AS d FROM information_schema.PARTITIONS "
        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = %s AND PARTITION_NAME IS NOT NULL "
        "ORDER BY PARTITION_ORDINAL_POSITION",
        (table,),
    )
    return [(r["n"], None if r["d"] == "MAXVALUE" else r["d"].replace("'", "")) for r in c.fetchall()]


def _add_months(y: int, m: int, k: int) -> tuple[int, int]:
    total = y * 12 + (m - 1) + k
    return total // 12, total % 12 + 1


def _wanted() -> list[tuple[int, int]]:
    n = util.now()
    return [_add_months(n.year, n.month, i) for i in range(settings.partition_ahead_months + 1)]


def _ym(y: int, m: int) -> int:
    return y * 100 + m


def maintain(daily: bool = True) -> None:
    """建月分区；daily = True 时再做每日维护（DAILY_MIN_GAP 内已有实例做完则跳过）。

    启动时与每天 SN_PARTITION_CRON 各调用一次；SN_MAINTAIN_ENABLED=false 的实例只在启动时建分区。
    """
    with db.connection() as conn, conn.cursor() as c:
        c.execute("SELECT GET_LOCK('sn_partition', 0) AS ok")
        if c.fetchone()["ok"] != 1:
            log.info("partition_maintain_skipped", reason="other instance holds lock")
            return
        try:
            _ensure_audit(c)
            if not daily:
                return
            last = _last_daily(c)
            if last is not None and time.time() - last < DAILY_MIN_GAP.total_seconds():
                log.info("daily_maintain_skipped", reason="done recently", last_done_ago_s=int(time.time() - last))
                return
            _archive(c, settings.audit_retention_days)
            purge_archive(settings.audit_archive_retention_days)
            c.execute("DELETE FROM sn_gen_preview WHERE created_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 7 DAY)")
            c.execute("DELETE FROM sn_rate_counter WHERE win < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY)")
            _daily_jobs()
            # 做完才记：中途失败（含实例被杀）时下一个实例照常补做
            done = int(time.time())
            c.execute(
                "INSERT INTO sn_seq(name, val) VALUES (%s, %s) ON DUPLICATE KEY UPDATE val = %s",
                (DAILY_MARK, done, done),
            )
        finally:
            c.execute("SELECT RELEASE_LOCK('sn_partition')")


def _last_daily(c) -> int | None:
    """最近一次每日维护完成时间（Unix 秒）；从未做过为 None。"""
    c.execute("SELECT val FROM sn_seq WHERE name = %s", (DAILY_MARK,))
    row = c.fetchone()
    return None if row is None else int(row["val"])


def _daily_jobs() -> None:
    """每天 2:10 维护任务的其余部分：同步生产组织（F-03）、每日交付自检（5.2.5 第 5 条）、单据汇总对账。
    各自失败互不影响。"""
    from app.services import bill_stats, delivery, factories

    try:
        factories.sync_from_k3(CurrentUser.system())
    except Exception as e:  # noqa: BLE001
        log.warning("daily_factory_sync_failed", err=str(e))
    try:
        delivery.self_check()
    except Exception:  # noqa: BLE001
        log.exception("daily_self_check_failed")
    try:
        bad = bill_stats.reconcile()
        log.info("bill_stat_reconciled", mismatched=bad)
    except Exception:  # noqa: BLE001
        log.exception("bill_stat_reconcile_failed")


def _ensure_audit(c) -> None:
    highest = max((b for _, b in _partitions(c, "sn_audit") if b is not None), default="")
    defs = []
    for y, m in _wanted():
        ny, nm = _add_months(y, m, 1)
        bound = f"{ny:04d}-{nm:02d}-01 00:00:00"
        if bound > highest:
            defs.append(f"PARTITION p{_ym(y, m)} VALUES LESS THAN ('{bound}')")
    _reorganize(c, "sn_audit", defs, "PARTITION pmax VALUES LESS THAN (MAXVALUE)")


def _reorganize(c, table: str, defs: list[str], max_def: str) -> None:
    if not defs:
        return
    c.execute(f"ALTER TABLE {table} REORGANIZE PARTITION pmax INTO ({', '.join(defs)}, {max_def})")
    log.info("partition_added", table=table, count=len(defs))


def _archive(c, days: int) -> int:
    """上界不晚于（今天 − 保存天数）的整月分区归档后删除。"""
    if days <= 0:
        return 0
    cutoff = (date(*util.now().timetuple()[:3]) - timedelta(days=days)).strftime("%Y-%m-%d") + " 00:00:00"
    parts = _partitions(c, "sn_audit")
    # 至少留一个有上界的分区，避免 pmax 以外的分区被删空
    bounded = sum(1 for _, b in parts if b is not None)
    archived = 0
    for name, bound in parts:
        if bound is None or bound > cutoff or bounded <= 1:
            continue
        moved = c.execute(
            f"INSERT IGNORE INTO sn_audit_archive SELECT a.*, UTC_TIMESTAMP() FROM sn_audit PARTITION ({name}) a"
        )
        c.execute(f"ALTER TABLE sn_audit DROP PARTITION {name}")
        bounded -= 1
        archived += 1
        log.info("audit_archived", partition=name, rows=moved)
        audit.record_isolated(
            CurrentUser.system(),
            audit.entry(audit.AUDIT_ARCHIVE)
            .count(moved)
            .info(f"partition={name} before={bound} at={datetime.now().isoformat()}"),
        )
    return archived


PURGE_BATCH = 10_000


def purge_archive(days: int) -> int:
    """删除发生时间早于（今天 − 保存天数）的归档痕迹；返回删除的条数。

    days = 0 或在线痕迹永久保存时不删；days 短于在线保存天数时按在线保存天数算（总保存期不会短于在线期）。
    """
    online = settings.audit_retention_days
    if days <= 0 or online <= 0:
        return 0
    keep = max(days, online)
    cutoff = datetime.combine(date(*util.now().timetuple()[:3]) - timedelta(days=keep), datetime.min.time())
    total = 0
    while True:
        with db.new_tx():
            n = db.exec("DELETE FROM sn_audit_archive WHERE created_at < %s LIMIT %s", cutoff, PURGE_BATCH)
        total += n
        if n < PURGE_BATCH:
            break
    if total:
        log.info("audit_archive_purged", rows=total, before=str(cutoff))
        audit.record_isolated(
            CurrentUser.system(),
            audit.entry(audit.AUDIT_ARCHIVE_PURGE).count(total).info(f"before={cutoff} keep_days={keep}"),
        )
    return total
