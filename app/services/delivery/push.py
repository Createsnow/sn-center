"""推送任务（F-11 推送）。

同组织 + 同一 PI 一个任务（转出批次用 #XO）；批次顺序执行，一批成功再发下一批；
没有可用回执时用原批次号、原内容自动重试（默认 1、5、15 分钟），期间后续批次等待；重试用尽或被拒绝即停，
「继续推送」先重推未确认或失败的批次（含拉取建立的），再认领后续的号。同组织同时运行的任务数不超过推送并发上限，
同一 PI 同一时刻只运行一个任务；租约过期（实例宕机）的任务由其他实例接手。
"""

from __future__ import annotations

import math
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import structlog

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db
from app.services import audit, factories, mes_client
from app.services.delivery import receipts
from app.services.delivery.batches import (
    CONFIRMED,
    DELIVERY,
    FAILED,
    LEASE_SECONDS,
    M_PUSH,
    RUNNER,
    SRC_PULL,
    SRC_PUSH,
    TRANSFER_OUT,
    UNCONFIRMED,
    XO_KEY,
    build_delivery,
    must_batch,
)

log = structlog.get_logger()

_wake = threading.Event()
executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="sn-push")
_running_here: set[tuple[str, str]] = set()
_running_lock = threading.Lock()


def poke() -> None:
    _wake.set()


def start_task(cu: CurrentUser, org: str, key: str, resend: bool = False) -> None:
    """启动（或排队）推送任务：不存在 / 已结束 → 排队；运行中 → 结束后再排一次。"""
    now = util.now()
    with db.tx():
        t = db.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s FOR UPDATE", org, key)
        if t is None:
            db.exec(
                "INSERT INTO sn_push_task(factory_code, task_key, state, resend, started_by, started_at, updated_at) "
                "VALUES (%s,%s,'QUEUED',%s,%s,%s,%s)",
                org,
                key,
                1 if resend else 0,
                cu.emp_no,
                now,
                now,
            )
        elif t["state"] == "RUNNING":
            db.exec(
                "UPDATE sn_push_task SET requeue = 1, resend = resend OR %s, updated_at = %s WHERE factory_code = %s "
                "AND task_key = %s",
                1 if resend else 0,
                now,
                org,
                key,
            )
        elif t["state"] == "WAITING" and not resend:
            pass
        else:
            db.exec(
                "UPDATE sn_push_task SET state = 'QUEUED', run_after = NULL, resend = %s, requeue = 0, message = '', "
                "started_by = %s, started_at = %s, updated_at = %s WHERE factory_code = %s AND task_key = %s",
                1 if resend else 0,
                cu.emp_no,
                now,
                now,
                org,
                key,
            )
    poke()


def resume(cu: CurrentUser, org_in: str | None, key_in: str | None) -> dict:
    """继续推送：重置未确认、失败批次的自动重试，按原批次号、原内容先重推，再认领后续的号。"""
    org = factories.must_get(org_in)["factory_code"]
    key = util.trim(key_in)
    if not key:
        raise biz(ErrorCode.PI_REQUIRED)
    btype = TRANSFER_OUT if key == XO_KEY else DELIVERY
    with db.tx():
        cond = "batch_type = %s" + ("" if key == XO_KEY else " AND pi_no = %s")
        args = [btype] + ([] if key == XO_KEY else [key])
        n = db.exec(
            "UPDATE sn_delivery_batch SET retry_count = 0, push_state = 'IDLE', next_retry_at = NULL, "
            f"repush_count = repush_count + 1, updated_at = %s WHERE factory_code = %s AND {cond} "
            "AND result IN ('UNCONFIRMED','FAILED')",
            util.now(),
            org,
            *args,
        )
        remaining = remaining_pushes(org, key)
        if not n and not remaining["unbatched"]:
            raise biz(ErrorCode.PUSH_NOTHING, factory=org, pi=key)
        audit.record(
            cu,
            audit.entry(audit.SN_PUSH_RESUME)
            .factory(org)
            .pi(None if key == XO_KEY else key)
            .count(remaining["unbatched"])
            .info(f"key={key} batches={n} remaining={remaining['pushes']}"),
        )
    start_task(cu, org, key, resend=True)
    return {"factory_code": org, "key": key, **remaining}


def remaining_pushes(org: str, key: str) -> dict:
    """「剩余 N 次」：未入批且未暂停的号按单批枚数折算的次数 + 未确认或失败的批次数。"""
    f = factories.get(org)
    size = max(1, int(f["batch_size"])) if f else 5000
    if key == XO_KEY:
        unbatched = 0
        batches = db.count(
            "SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND batch_type = %s "
            "AND result IN ('UNCONFIRMED','FAILED')",
            org,
            TRANSFER_OUT,
        )
    else:
        unbatched = db.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s AND status = 'TO_PUSH' "
            "AND suspend IS NULL AND delivery_batch_no IS NULL AND acquire_batch_no IS NULL",
            key,
            org,
        )
        batches = db.count(
            "SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND pi_no = %s AND batch_type = %s "
            "AND result IN ('UNCONFIRMED','FAILED')",
            org,
            key,
            DELIVERY,
        )
    return {"unbatched": unbatched, "batches": batches, "pushes": math.ceil(unbatched / size) + batches}


def task_state(org: str, key: str) -> dict | None:
    t = db.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, key)
    return None if t is None else {k: util.jv(v) for k, v in t.items()}


def kick_bill(bill_no: str) -> None:
    """单据解除暂停 / 号回到未入批后：涉及的（组织，PI）有可推送的号时启动推送。"""
    for r in db.all(
        "SELECT DISTINCT factory_code, pi_no FROM sn_item WHERE bill_no = %s AND status = 'TO_PUSH' "
        "AND delivery_batch_no IS NULL AND acquire_batch_no IS NULL AND suspend IS NULL",
        bill_no,
    ):
        if factories.is_mes(r["factory_code"]):
            start_task(CurrentUser.system(), r["factory_code"], r["pi_no"])


def _claim_task() -> dict | None:
    """挑一个可运行的任务并占用（多实例安全）。"""
    now = util.now()
    rows = db.all(
        "SELECT t.*, f.push_concurrency FROM sn_push_task t JOIN sn_factory f ON f.factory_code = t.factory_code "
        "WHERE t.state = 'QUEUED' OR (t.state = 'WAITING' AND t.run_after <= %s) "
        "OR (t.state = 'RUNNING' AND t.lease_until < %s) ORDER BY t.updated_at LIMIT 50",
        now,
        now,
    )
    for t in rows:
        busy_org = db.count(
            "SELECT COUNT(*) FROM sn_push_task WHERE factory_code = %s AND state = 'RUNNING' AND lease_until >= %s",
            t["factory_code"],
            now,
        )
        if busy_org >= int(t["push_concurrency"]):
            continue
        if t["task_key"] != XO_KEY and db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_push_task WHERE task_key = %s AND state = 'RUNNING' AND lease_until >= %s)",
            t["task_key"],
            now,
        ):
            continue
        n = db.exec(
            "UPDATE sn_push_task SET state = 'RUNNING', lease_until = %s, runner = %s, requeue = 0, updated_at = %s "
            "WHERE factory_code = %s AND task_key = %s AND state = %s AND updated_at = %s",
            now + timedelta(seconds=LEASE_SECONDS),
            RUNNER,
            now,
            t["factory_code"],
            t["task_key"],
            t["state"],
            t["updated_at"],
        )
        if n == 1:
            return t
    return None


def run_pending(wait: bool = True) -> int:
    """跑完当前所有可运行的任务（测试、调度线程调用）；返回执行的任务数。wait = False 时提交到线程池即返回。"""
    count = 0
    while True:
        t = _claim_task()
        if t is None:
            return count
        count += 1
        if wait:
            _run_task(t)
        else:
            executor.submit(_run_task, t)


def _renew(t: dict, batch_no: str | None) -> None:
    db.exec(
        "UPDATE sn_push_task SET lease_until = %s, current_batch = %s, updated_at = %s WHERE factory_code = %s "
        "AND task_key = %s AND state = 'RUNNING'",
        util.now() + timedelta(seconds=LEASE_SECONDS),
        batch_no,
        util.now(),
        t["factory_code"],
        t["task_key"],
    )


def _end_task(t: dict, state: str, message: str = "", run_after=None, batch_no: str | None = None) -> None:
    with db.tx():
        cur = db.one(
            "SELECT requeue FROM sn_push_task WHERE factory_code = %s AND task_key = %s FOR UPDATE",
            t["factory_code"],
            t["task_key"],
        )
        if cur is not None and cur["requeue"] and state == "DONE":
            state = "QUEUED"
        db.exec(
            "UPDATE sn_push_task SET state = %s, run_after = %s, lease_until = NULL, current_batch = %s, message = %s, "
            "requeue = 0, resend = IF(%s IN ('DONE','STOPPED'), 0, resend), updated_at = %s "
            "WHERE factory_code = %s AND task_key = %s",
            state,
            run_after,
            batch_no,
            util.cut(message, 500),
            state,
            util.now(),
            t["factory_code"],
            t["task_key"],
        )
    if state == "QUEUED":
        poke()


def _next_batch(t: dict, resend: bool) -> dict | str | None:
    """下一个要发的批次：先未确认 / 失败的（续推时含拉取建立的、失败的），再认领新号建批。
    返回批次，或 'WAIT:<批次号>'（等重试）/ 'STOP:<原因>'。"""
    org, key = t["factory_code"], t["task_key"]
    btype = TRANSFER_OUT if key == XO_KEY else DELIVERY
    cond = "factory_code = %s AND batch_type = %s" + ("" if key == XO_KEY else " AND pi_no = %s")
    args = [org, btype] + ([] if key == XO_KEY else [key])
    pending = db.all(
        f"SELECT * FROM sn_delivery_batch WHERE {cond} AND result IN ('UNCONFIRMED','FAILED') ORDER BY created_at, id",
        *args,
    )
    for b in pending:
        if b["result"] == FAILED:
            if resend:
                return b
            return f"STOP:批次 {b['batch_no']} 失败：{b['fail_reason'] or b['fail_code']}"
        if b["push_state"] == "WAIT_RETRY":
            if b["next_retry_at"] is not None and b["next_retry_at"] > util.now():
                return f"WAIT:{b['batch_no']}"
            return b
        if b["push_state"] == "EXHAUSTED":
            if resend:
                return b
            return f"STOP:批次 {b['batch_no']} 自动重试用尽仍未确认"
        if b["source"] == SRC_PULL and not resend:
            # MES 取走、尚未回执的批次：自动推送不抢着发，续推时才重推
            continue
        return b
    if key == XO_KEY or not factories.is_mes(org):
        return None
    return build_delivery(CurrentUser.system(), org, key, SRC_PUSH)


def _run_task(t: dict) -> None:
    key = (t["factory_code"], t["task_key"])
    with _running_lock:
        if key in _running_here:
            return
        _running_here.add(key)
    try:
        _drive(t)
    except Exception as e:  # noqa: BLE001
        log.exception("push_task_failed", org=t["factory_code"], key=t["task_key"])
        _end_task(t, "STOPPED", f"推送任务异常：{type(e).__name__}")
    finally:
        with _running_lock:
            _running_here.discard(key)


def _drive(t: dict) -> None:
    sent = 0
    resend = bool(t["resend"])
    seen_resend: set[int] = set()
    while True:
        nxt = _next_batch(t, resend)
        if nxt is None:
            other = (
                db.count(
                    "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s AND status IN "
                    "('TO_PUSH','PUSHED','ACQUIRED') "
                    "AND delivery_mode IN ('PULL','PAGE')",
                    t["task_key"],
                    t["factory_code"],
                )
                if t["task_key"] != XO_KEY
                else 0
            )
            _end_task(
                t, "DONE", f"已推送 {sent} 批" + (f"；其余 {other} 枚已由 MES 拉取 / 在领取页领取" if other else "")
            )
            return
        if isinstance(nxt, str):
            kind, _, info = nxt.partition(":")
            if kind == "WAIT":
                b = must_batch(info)
                _end_task(t, "WAITING", f"批次 {info} 等待自动重试", b["next_retry_at"], info)
            else:
                _end_task(t, "STOPPED", info)
            return
        if resend and nxt["id"] in seen_resend:
            _end_task(t, "STOPPED", f"批次 {nxt['batch_no']} 续推仍未确认")
            return
        if resend and nxt["result"] == FAILED:
            seen_resend.add(nxt["id"])
        _renew(t, nxt["batch_no"])
        ok = push_once(nxt)
        if ok == "CONFIRMED":
            sent += 1
            continue
        b = must_batch(nxt["batch_no"])
        if b["result"] in CONFIRMED:
            continue
        if b["push_state"] == "WAIT_RETRY":
            _end_task(t, "WAITING", f"批次 {b['batch_no']} 等待自动重试", b["next_retry_at"], b["batch_no"])
            return
        _end_task(
            t, "STOPPED", f"批次 {b['batch_no']}：{b['fail_reason'] or '自动重试用尽仍未确认'}", None, b["batch_no"]
        )
        return


def push_once(b: dict) -> str:
    """发送一次。返回 CONFIRMED / FAILED / RETRY / EXHAUSTED / SKIPPED。"""
    b = must_batch(b["batch_no"])
    if b["result"] not in (UNCONFIRMED, FAILED):
        return "CONFIRMED" if b["result"] in CONFIRMED else "SKIPPED"
    f = factories.must_get(b["factory_code"])
    target = mes_client.Target.of(f)
    attempt = b["push_count"] + 1
    out = mes_client.send(target, b["batch_no"], b["body_gz"], attempt)
    now = util.now()
    sys = CurrentUser.system()
    db.exec(
        "UPDATE sn_delivery_batch SET push_count = push_count + 1, last_push_at = %s, updated_at = %s WHERE id = %s",
        now,
        now,
        b["id"],
    )
    if out.kind == mes_client.RECEIPT:
        applied = receipts.apply_receipt(sys, b["batch_no"], out.receipt or {}, M_PUSH)
        audit.record_isolated(
            sys,
            audit.entry(audit.SN_PUSH)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .info(f"attempt={attempt} verdict={applied.verdict}")
            if applied.verdict in ("CONFIRMED", "ARCHIVED")
            else audit.entry(audit.SN_PUSH)
            .fail(f"MES 应答：{applied.verdict}")
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .info(f"attempt={attempt}"),
        )
        if f["cfg_status"] != "OK":
            db.exec(
                "UPDATE sn_factory SET cfg_status = 'OK', cfg_message = '' WHERE factory_code = %s", f["factory_code"]
            )
        return "CONFIRMED" if applied.verdict in ("CONFIRMED", "ARCHIVED") else "FAILED"
    if out.kind == mes_client.RETRY:
        intervals = settings.push_retry_intervals
        with db.tx():
            cur = must_batch(b["batch_no"], lock=True)
            if cur["result"] in CONFIRMED:
                return "CONFIRMED"
            if cur["retry_count"] < len(intervals):
                wait = intervals[cur["retry_count"]] * 60
                if out.retry_after is not None:
                    wait = max(wait, out.retry_after)
                db.exec(
                    "UPDATE sn_delivery_batch SET retry_count = retry_count + 1, push_state = 'WAIT_RETRY', "
                    "next_retry_at = %s, fail_code = %s, fail_reason = %s, updated_at = %s WHERE id = %s",
                    now + timedelta(seconds=wait),
                    out.code,
                    util.cut(out.message, 1000),
                    now,
                    b["id"],
                )
                state = "RETRY"
            else:
                db.exec(
                    "UPDATE sn_delivery_batch SET push_state = 'EXHAUSTED', next_retry_at = NULL, fail_code = %s, "
                    "fail_reason = %s, updated_at = %s WHERE id = %s",
                    out.code,
                    util.cut(out.message, 1000),
                    now,
                    b["id"],
                )
                state = "EXHAUSTED"
            audit.record(
                sys,
                audit.entry(audit.SN_PUSH)
                .fail(out.message)
                .factory(b["factory_code"])
                .pi(b["pi_no"])
                .count(b["qty"])
                .batch(b["batch_no"])
                .info(f"attempt={attempt} code={out.code} next={state}"),
            )
        return state
    with db.tx():
        cur = must_batch(b["batch_no"], lock=True)
        if cur["result"] in CONFIRMED:
            return "CONFIRMED"
        receipts.mark_failed(cur, out.code, out.message, out.voidable, now)
        if out.config_problem:
            db.exec(
                "UPDATE sn_factory SET cfg_status = 'FAILED', cfg_message = %s WHERE factory_code = %s",
                util.cut(out.message, 500),
                f["factory_code"],
            )
        audit.record(
            sys,
            audit.entry(audit.SN_PUSH)
            .fail(out.message)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .info(f"attempt={attempt} code={out.code} http={out.http_status}"),
        )
    return "FAILED"


class _Scheduler:
    def __init__(self) -> None:
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, name="sn-push-scheduler", daemon=True)

    def start(self) -> _Scheduler:
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        _wake.set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            _wake.wait(settings.push_poll_seconds)
            _wake.clear()
            if self._stop.is_set():
                return
            try:
                run_pending(wait=False)
            except Exception:  # noqa: BLE001
                log.exception("push_scheduler_failed")


def start_scheduler() -> _Scheduler | None:
    return _Scheduler().start() if settings.push_enabled else None
