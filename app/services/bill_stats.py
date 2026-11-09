"""单据汇总（运维手册 8.8）：每个（PI，单据，号上的生产组织）一行枚数，替代对整个号池的 GROUP BY。

为什么要：SN 分配页左侧单据列表（不带页签、「已分配」页签）与 SN 导出单据列表原来按（PI，单据）对整个号池汇总，
耗时随累计枚数线性增长（千万行约 3.7 秒，3 年 1.5 亿行时一次翻页要一分钟以上）。改查本表后耗时只与单据数有关。

口径与原汇总逐字段相同（同一个 SELECT 按单据重算，见 _STAT_SELECT），页面接口的请求、应答都不变：
- 分配页单据列表：本表按（PI，单据）再汇总授权范围内的组织行（qty / pending / to_push / suspended / handled_at）。
- SN 导出单据列表：本表 factory_code 非空、已分配枚数 > 0 的行（alloc_qty / alloc_suspended / alloc_customer）。

维护方式「标脏 + 重算」（不逐处加减，免得哪一处漏改）：
- 号的挂靠或状态一变（生成、导入、删号、分配、建批、回执、人工确认或作废、转移、暂停、对接开关等），
  改号的代码调 touch*，把所在（PI，单据）记进 sn_bill_stat_dirty（同一事务内合并，提交前按主键顺序一次写入，
  ver 加 1）。改号回滚时标脏一起回滚。
- refresh 按单据重算：读标记的 ver → 新开事务按单据重算并整行替换 → 只删 ver 未变的标记（重算期间又有改动的，
  标记留着下次再算）。重算全局串行（命名锁），不会有旧快照覆盖新结果。
- 列表查询前先 refresh（最多 REFRESH_BUDGET_SECONDS 秒），后台每 REFRESH_INTERVAL_SECONDS 秒也 refresh 一次，
  所以通常读到的就是最新值；积压很多时（如整个组织开关对接）页面最多晚几秒（已确认可接受）。
- 兜底：每日维护对最近改过的单据抽查对账，不一致就标脏重算并记日志；devtools.rebuild_bill_stat 可整表重建。

不变式：号池里每个（PI，单据）要么在本表有行，要么有未处理的标记。
"""

from __future__ import annotations

import contextvars
import threading
import time
from collections.abc import Iterable
from dataclasses import dataclass, field

import structlog

from app.core import utils as util
from app.db.session import db, marks
from app.services import locks

log = structlog.get_logger()

#: 列表查询前同步重算的时间上限（秒）：超出的留给后台
REFRESH_BUDGET_SECONDS = 3.0
#: 列表查询等重算锁的上限（秒）：另一个实例 / 后台正在重算时等它做完
REFRESH_WAIT_SECONDS = 3
#: 后台重算间隔（秒）
REFRESH_INTERVAL_SECONDS = 2
#: 每轮取多少个标记
_BATCH = 200
#: 写标记每条语句的行数
_FLUSH_CHUNK = 500

#: 按单据重算（与原来分配页、导出页的汇总同一口径）；org_key = 号上的生产组织，待分配（未分到组织）为空串
_STAT_SELECT = (
    "SELECT COALESCE(factory_code, '') org_key, MAX(factory_code) factory_code, COUNT(*) qty, "
    "SUM(status = 'PENDING_ALLOC') pending, SUM(status = 'TO_PUSH') to_push, SUM(suspend IS NOT NULL) suspended, "
    "MAX(updated_at) handled_at, SUM(status <> 'PENDING_ALLOC') alloc_qty, "
    "SUM(status <> 'PENDING_ALLOC' AND suspend IS NOT NULL) alloc_suspended, "
    "MAX(CASE WHEN status <> 'PENDING_ALLOC' THEN customer_code END) alloc_customer "
    "FROM sn_item"
)
_STAT_COLS = (
    "pi_no, bill_no, org_key, factory_code, qty, pending, to_push, suspended, handled_at, "
    "alloc_qty, alloc_suspended, alloc_customer, refreshed_at"
)


# ================================================================== 标脏


@dataclass
class _Pending:
    conn_id: int
    keys: set[tuple[str, str]] = field(default_factory=set)
    open: bool = True


#: 当前事务待写的标记（提交前一次写入）
_pending: contextvars.ContextVar[_Pending | None] = contextvars.ContextVar("sn_bill_stat_pending", default=None)


def touch(pi: str | None, bill: str | None) -> None:
    touch_many([(pi, bill)])


def touch_many(pairs: Iterable[tuple[str | None, str | None]]) -> None:
    """把这些（PI，单据）标为待重算。在事务里时合并到提交前一次写入（随事务回滚），否则马上写。"""
    keys = {(p, b) for p, b in pairs if p is not None and b is not None}
    if not keys:
        return
    if not db.in_tx():
        _flush(keys)
        return
    with db.tx() as conn:
        cur = _pending.get()
        if cur is None or not cur.open or cur.conn_id != id(conn):
            cur = _Pending(id(conn))
            _pending.set(cur)
            p = cur

            def flush() -> None:
                p.open = False
                _flush(p.keys)

            db.before_commit(flush)
            # 事务结束（含回滚、死锁后整笔重做）就作废：同一条池连接上的下一个事务另起一份，否则标记写不进去
            db.after_tx(lambda: setattr(p, "open", False))
        cur.keys |= keys


def touch_ids(ids: Iterable[int]) -> None:
    """按号池 id 标脏（号还在号池里时调用：删号前、改挂靠前后各一次）。"""
    ids = list(ids)
    for i in range(0, len(ids), 1000):
        part = ids[i : i + 1000]
        touch_many(
            (r["pi_no"], r["bill_no"])
            for r in db.all(f"SELECT DISTINCT pi_no, bill_no FROM sn_item WHERE id IN ({marks(len(part))})", *part)
        )


def touch_batch(batch_no: str | None) -> None:
    """交付批次里的号（号上记着这个批次号时）。"""
    if batch_no:
        touch_many(
            (r["pi_no"], r["bill_no"])
            for r in db.all("SELECT DISTINCT pi_no, bill_no FROM sn_item WHERE delivery_batch_no = %s", batch_no)
        )


def touch_batch_items(batch_id: int) -> None:
    """交付批次明细里的号现在挂的（PI，单据）（号上已不记批次号时，如回执清 MES 持有）。"""
    touch_many(
        (r["pi_no"], r["bill_no"])
        for r in db.all(
            "SELECT DISTINCT i.pi_no, i.bill_no FROM sn_delivery_item d JOIN sn_item i ON i.id = d.item_id "
            "WHERE d.batch_id = %s",
            batch_id,
        )
    )


def touch_bill(bill_no: str | None) -> None:
    """单据下全部的号（按单据改号之后调用：本事务改过的号在一致性读里可见）。"""
    if bill_no:
        touch_many((p, bill_no) for p in db.column("SELECT DISTINCT pi_no FROM sn_item WHERE bill_no = %s", bill_no))


def touch_factory(factory_code: str, status: str) -> None:
    """某组织某状态的号（整个组织开关对接之后调用）。"""
    touch_many(
        (r["pi_no"], r["bill_no"])
        for r in db.all(
            "SELECT DISTINCT pi_no, bill_no FROM sn_item WHERE status = %s AND factory_code = %s", status, factory_code
        )
    )


def _flush(keys: set[tuple[str, str]]) -> None:
    rows = sorted(keys)
    now = util.now()
    for i in range(0, len(rows), _FLUSH_CHUNK):
        part = rows[i : i + _FLUSH_CHUNK]
        db.exec(
            "INSERT INTO sn_bill_stat_dirty(pi_no, bill_no, ver, marked_at) VALUES "
            + ",".join(["(%s,%s,1,%s)"] * len(part))
            + " ON DUPLICATE KEY UPDATE ver = ver + 1, marked_at = VALUES(marked_at)",
            *[x for p, b in part for x in (p, b, now)],
        )


# ================================================================== 重算


def _recompute(pi: str, bill: str, ver: int | None) -> None:
    """新开事务：按单据重算、整行替换；ver 非空时只删 ver 未变的标记。第一条语句就是一致性读，
    快照晚于读到 ver 的时刻，ver 之前提交的改动都在快照里。"""
    with db.new_tx():
        rows = db.all(_STAT_SELECT + " WHERE bill_no = %s AND pi_no = %s GROUP BY org_key", bill, pi)
        db.exec("DELETE FROM sn_bill_stat WHERE pi_no = %s AND bill_no = %s", pi, bill)
        if rows:
            now = util.now()
            db.exec_many(
                f"INSERT INTO sn_bill_stat({_STAT_COLS}) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                [(pi, bill, *_values(r), now) for r in rows],
            )
        if ver is not None:
            db.exec("DELETE FROM sn_bill_stat_dirty WHERE pi_no = %s AND bill_no = %s AND ver = %s", pi, bill, ver)


def refresh(budget_seconds: float | None = REFRESH_BUDGET_SECONDS, wait: int = REFRESH_WAIT_SECONDS) -> int:
    """处理标记，返回重算的单据数。重算全局串行：等不到锁（别处正在重算）返回 0，读到的最多晚几秒。
    不能在事务里调用（重算要看到别的事务已提交的改动）。"""
    if db.in_tx():
        return 0
    if not db.exists("SELECT EXISTS(SELECT 1 FROM sn_bill_stat_dirty)"):
        return 0
    held = locks.try_acquire("stat", ["bill"], wait)
    if held is None:
        return 0
    done = 0
    deadline = None if budget_seconds is None else time.monotonic() + budget_seconds
    try:
        while True:
            rows = db.all("SELECT pi_no, bill_no, ver FROM sn_bill_stat_dirty ORDER BY pi_no, bill_no LIMIT %s", _BATCH)
            if not rows:
                break
            for r in rows:
                _recompute(r["pi_no"], r["bill_no"], int(r["ver"]))
                done += 1
                if deadline is not None and time.monotonic() > deadline:
                    return done
            if len(rows) < _BATCH:
                # 这一批处理期间新标的留给下一轮（避免同一单据被持续改动时一直占着）
                break
    finally:
        held.release()
    if done:
        log.debug("bill_stat_refreshed", bills=done)
    return done


def refresh_quietly() -> None:
    """后台定时：出错只记日志。"""
    try:
        refresh(budget_seconds=None, wait=0)
    except Exception:  # noqa: BLE001
        log.exception("bill_stat_refresh_failed")


# ================================================================== 建立、对账


def rebuild_all() -> int:
    """整表重建（建表后首次回填、devtools.rebuild_bill_stat）：按号池全表汇总一次，返回行数。
    汇总是一致性读（不锁号池，改号照常进行）；期间提交的改动都带标记，重建后按标记再重算，不会丢。"""
    held = locks.try_acquire("stat", ["bill"], 600)
    if held is None:
        raise RuntimeError("bill stat refresh lock busy")
    try:
        with db.new_tx():
            whole = _STAT_SELECT.replace("SELECT ", "SELECT pi_no, bill_no, ", 1)
            rows = db.all(whole + " GROUP BY pi_no, bill_no, org_key")
            db.exec("DELETE FROM sn_bill_stat")
            now = util.now()
            for i in range(0, len(rows), _FLUSH_CHUNK):
                db.exec_many(
                    f"INSERT INTO sn_bill_stat({_STAT_COLS}) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    [(r["pi_no"], r["bill_no"], *_values(r), now) for r in rows[i : i + _FLUSH_CHUNK]],
                )
    finally:
        held.release()
    log.warning("bill_stat_rebuilt", rows=len(rows))
    return len(rows)


def ensure_filled() -> bool:
    """启动时：号池有号、汇总表却是空的（如从备份单独恢复了号池，或整表回填没做完）→ 整表回填。返回是否回填了。"""
    if db.exists("SELECT EXISTS(SELECT 1 FROM sn_bill_stat)") or not db.exists("SELECT EXISTS(SELECT 1 FROM sn_item)"):
        return False
    rebuild_all()
    return True


def _values(r: dict) -> tuple:
    """_STAT_SELECT 一行 → sn_bill_stat 的 org_key … alloc_customer。"""
    return (
        r["org_key"],
        r["factory_code"],
        int(r["qty"]),
        int(r["pending"] or 0),
        int(r["to_push"] or 0),
        int(r["suspended"] or 0),
        r["handled_at"],
        int(r["alloc_qty"] or 0),
        int(r["alloc_suspended"] or 0),
        r["alloc_customer"],
    )


#: 对账比较的列（与 _values 同序）
_CMP_COLS = (
    "org_key, factory_code, qty, pending, to_push, suspended, handled_at, alloc_qty, alloc_suspended, alloc_customer"
)


def reconcile(limit: int = 2000, missing: bool = False) -> int:
    """对账（每日维护）：最近重算过的 limit 张单据各按号池重算一遍，与表里的值比较；不一致的记日志
    （bill_stat_mismatch）并重算。返回不一致的单据数。只抽查最近的，不扫整个号池。
    每张单据在一个事务（同一快照）里比：有未处理标记的跳过（本来就要重算）。"""
    held = locks.try_acquire("stat", ["bill"], 60)
    if held is None:
        return 0
    bad: list[tuple[str, str]] = []
    try:
        pairs = db.all(
            "SELECT pi_no, bill_no FROM sn_bill_stat GROUP BY pi_no, bill_no ORDER BY MAX(refreshed_at) DESC LIMIT %s",
            limit,
        )
        if missing:
            # 号池里有、汇总里没有、也没有标记的（PI，单据）：要扫整个号池，只在手动对账时做
            have_pairs = {
                (r["pi_no"], r["bill_no"]) for r in db.all("SELECT DISTINCT pi_no, bill_no FROM sn_bill_stat")
            }
            dirty = {(r["pi_no"], r["bill_no"]) for r in db.all("SELECT pi_no, bill_no FROM sn_bill_stat_dirty")}
            for r in db.all("SELECT DISTINCT pi_no, bill_no FROM sn_item"):
                key = (r["pi_no"], r["bill_no"])
                if key not in have_pairs and key not in dirty:
                    bad.append(key)
                    log.warning("bill_stat_mismatch", pi=key[0], bill=key[1], missing=True)
        for p in pairs:
            pi, bill = p["pi_no"], p["bill_no"]
            with db.new_tx():
                fresh = sorted(
                    _values(r)
                    for r in db.all(_STAT_SELECT + " WHERE bill_no = %s AND pi_no = %s GROUP BY org_key", bill, pi)
                )
                if db.exists(
                    "SELECT EXISTS(SELECT 1 FROM sn_bill_stat_dirty WHERE pi_no = %s AND bill_no = %s)", pi, bill
                ):
                    continue
                have = sorted(
                    tuple(r.values())
                    for r in db.all(f"SELECT {_CMP_COLS} FROM sn_bill_stat WHERE pi_no = %s AND bill_no = %s", pi, bill)
                )
            if have != fresh:
                bad.append((pi, bill))
                log.warning("bill_stat_mismatch", pi=pi, bill=bill)
        for pi, bill in bad:
            _recompute(pi, bill, None)
    finally:
        held.release()
    return len(bad)


# ================================================================== 后台


class _Loop:
    def __init__(self) -> None:
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="sn-bill-stat", daemon=True)

    def start(self) -> _Loop:
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        while not self._stop.wait(REFRESH_INTERVAL_SECONDS):
            refresh_quietly()


def start_scheduler() -> _Loop:
    return _Loop().start()
