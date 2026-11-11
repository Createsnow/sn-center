"""推送任务（F-11 推送）。

同组织 + 同一 PI 一个任务（转出批次用 #XO）；批次串行执行，一批确认成功再发下一批：
- MES 应答「已收到」（code = OK）后批次进入等回执（WAIT_RECEIPT），任务等 MES 落库后调接口 ③ 回执：OK 确认成功，
  马上推下一批；NG 整批退回，停止并提示原因。
- 等回执超时（默认 1、5、10 分钟，SN_PUSH_RECEIPT_WAIT_MINUTES）按原批次号、原内容重推（MES 按批次号去重，
  重推时重新回执），个数即重推次数，最后一次重推后仍超时即停止（EXHAUSTED）。
- 没送达（超时、5xx、断连等）用原批次号、原内容自动重试（默认 1、5、15 分钟），重试用尽即停止。
- 等回执、等重试期间后续批次等待，这一批的号保持冻结；「继续推送」先重推未确认或失败的批次（含拉取建立的），
  再认领后续的号。同组织同时运行的任务数不超过推送并发上限，
同一 PI 同一时刻只运行一个任务；租约过期（实例宕机）的任务由其他实例接手。

一个实例同时执行的任务不超过推送线程数（PUSH_WORKERS）：调度只在有空闲线程时才认领，认领到的马上执行，
不会把一堆任务先标成运行中、再在线程池里排队（排队期间不续租，还占着组织的推送并发名额）。
执行中遇到数据库锁冲突（死锁、锁等待超时、连接池满）不算推送失败：任务转为等待，TRANSIENT_RETRY_SECONDS
秒后自动接着推（批次、号都在库里，接着做即可），连续 TRANSIENT_MAX 次仍冲突才停止。
"""

from __future__ import annotations

import math
import threading
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import timedelta

import structlog

from app.core import utils as util
from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, is_lock_conflict, retry_deadlock
from app.services import audit, factories, mes_client
from app.services.delivery import receipts
from app.services.delivery.batches import (
    FAILED,
    IS_DELIVERY,
    LEASE_SECONDS,
    RUNNER,
    SRC_PULL,
    SRC_PUSH,
    SUCCESS,
    TRANSFER_OUT,
    UNCONFIRMED,
    VOIDED,
    XO_KEY,
    build_delivery,
    must_batch,
    pool_groups,
)

log = structlog.get_logger()

_wake = threading.Event()
#: 本实例的推送线程数
PUSH_WORKERS = 8
executor = ThreadPoolExecutor(max_workers=PUSH_WORKERS, thread_name_prefix="sn-push")
_running_here: set[tuple[str, str]] = set()
_running_lock = threading.Lock()
#: 已认领、交给线程池还没执行完的任务数（不超过 PUSH_WORKERS）
_inflight = 0
#: 数据库锁冲突后多少秒自动接着推；连续冲突多少次后停止（各任务分别计，本实例内）
TRANSIENT_RETRY_SECONDS = 10
TRANSIENT_MAX = 5
_transient: dict[tuple[str, str], int] = {}
#: 运行中任务的续租间隔（远小于租约 LEASE_SECONDS）
HEARTBEAT_SECONDS = 60
#: 认领推送任务的跨实例互斥行（sn_seq 中的一行，只用来加行锁，不当序号用）
CLAIM_MUTEX = "#push_claim"


def poke() -> None:
    _wake.set()


def start_task(cu: CurrentUser, org: str, key: str, resend: bool = False) -> None:
    """启动（或排队）推送任务：不存在 / 已结束 → 排队；运行中 → 结束后再排一次。
    死锁时自动重做（不在外层事务里时）。"""
    retry_deadlock(lambda: _start_task(cu, org, key, resend))
    poke()


def _start_task(cu: CurrentUser, org: str, key: str, resend: bool) -> None:
    now = util.now()
    with db.tx():
        # 先不加锁地看任务行在不在，不在先插入，再按主键加锁：对不存在的行 FOR UPDATE 会锁住主键间隙，
        # 各组织同时分配、同时建第一个推送任务时互相卡住，成为死锁（MySQL 1213，分配接口报「稍后重试」）
        if not db.exists(
            "SELECT EXISTS(SELECT 1 FROM sn_push_task WHERE factory_code = %s AND task_key = %s)", org, key
        ) and db.exec(
            "INSERT IGNORE INTO sn_push_task(factory_code, task_key, state, resend, started_by, started_at, "
            "updated_at) VALUES (%s,%s,'QUEUED',%s,%s,%s,%s)",
            org,
            key,
            1 if resend else 0,
            cu.emp_no,
            now,
            now,
        ):
            return
        t = db.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s FOR UPDATE", org, key)
        if t is None:
            # 插入与加锁之间被删掉（组织关闭对接 MES 时清理已结束的任务）：重新排队
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


def resume(cu: CurrentUser, org_in: str | None, key_in: str | None) -> dict:
    """继续推送：重置未确认、失败批次的自动重试与等回执次数，按原批次号、原内容先重推，再认领后续的号。"""
    org = factories.must_get(org_in)["factory_code"]
    cu.check_write(org)
    key = util.trim(key_in)
    if not key:
        raise biz(ErrorCode.PI_REQUIRED)
    with db.tx():
        cond, args = _batch_cond(key)
        n = db.exec(
            "UPDATE sn_delivery_batch SET retry_count = 0, receipt_timeouts = 0, push_state = 'IDLE', "
            "next_retry_at = NULL, "
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


def _batch_cond(key: str) -> tuple[str, list]:
    """任务键对应的批次：#XO 为本组织的转出批次，否则为该 PI 的交付类批次。"""
    if key == XO_KEY:
        return "batch_type = %s", [TRANSFER_OUT]
    return f"{IS_DELIVERY} AND pi_no = %s", [key]


def remaining_pushes(org: str, key: str) -> dict:
    """「剩余 N 次」：未入批且未暂停的号按类型分批、按单批枚数折算的次数 + 未确认或失败的批次数。"""
    f = factories.get(org)
    size = max(1, int(f["batch_size"])) if f else 5000
    # 生成的号与 SN 导入的号分开成批，按（类型，是否导入）折算
    groups = [] if key == XO_KEY else pool_groups(org, key)
    cond, args = _batch_cond(key)
    batches = db.count(
        f"SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND {cond} "
        "AND result IN ('UNCONFIRMED','FAILED')",
        org,
        *args,
    )
    pushes = sum(math.ceil(n / size) for _t, _i, n in groups) + batches
    return {"unbatched": sum(n for _t, _i, n in groups), "batches": batches, "pushes": pushes}


def task_state(org: str, key: str) -> dict | None:
    t = db.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, key)
    return None if t is None else {k: util.jv(v) for k, v in t.items()}


def kick_bill(bill_no: str) -> None:
    """单据解除暂停 / 号回到未入批后：涉及的（组织，PI）有可推送的号时启动推送。"""
    for r in db.all(
        "SELECT DISTINCT factory_code, pi_no FROM sn_item WHERE bill_no = %s AND status = 'TO_PUSH' "
        "AND delivery_batch_no IS NULL AND suspend IS NULL",
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
    if rows and not db.exists("SELECT EXISTS(SELECT 1 FROM sn_seq WHERE name = %s)", CLAIM_MUTEX):
        # 互斥行只建一次，在事务外建：事务里先读会提前定下一致性快照，下面数运行中的任务就看不到
        # 别的实例刚提交的认领；也免得 ON DUPLICATE KEY UPDATE 锁住它前面的间隙
        db.exec("INSERT IGNORE INTO sn_seq(name, val) VALUES (%s, 0)", CLAIM_MUTEX)
    for t in rows:
        with db.tx():
            # 各实例的「数运行中的任务 → 认领」串行：否则两个实例可能同时数到未满，各认领一个，超出推送并发上限。
            # 加锁读放在事务第一句，之后的普通读（运行中的任务数）快照晚于别的实例的提交
            db.one("SELECT val FROM sn_seq WHERE name = %s FOR UPDATE", CLAIM_MUTEX)
            busy_org = db.count(
                "SELECT COUNT(*) FROM sn_push_task WHERE factory_code = %s AND state = 'RUNNING' AND lease_until >= %s",
                t["factory_code"],
                now,
            )
            if busy_org >= int(t["push_concurrency"]):
                continue
            if t["task_key"] != XO_KEY and db.exists(
                "SELECT EXISTS(SELECT 1 FROM sn_push_task WHERE task_key = %s AND state = 'RUNNING' "
                "AND lease_until >= %s)",
                t["task_key"],
                now,
            ):
                continue
            n = db.exec(
                "UPDATE sn_push_task SET state = 'RUNNING', lease_until = %s, runner = %s, requeue = 0, "
                "updated_at = %s WHERE factory_code = %s AND task_key = %s AND state = %s AND updated_at = %s",
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
    """跑完当前所有可运行的任务（测试、调度线程调用）；返回执行的任务数。
    wait = False（调度线程）：只认领空闲推送线程数那么多个，提交到线程池即返回；有线程空出来时再唤醒调度。"""
    global _inflight
    count = 0
    while True:
        if not wait:
            with _running_lock:
                if _inflight >= PUSH_WORKERS:
                    return count
                _inflight += 1
        try:
            t = _claim_task()
        except BaseException:
            if not wait:
                _done_inflight(False)
            raise
        if t is None:
            if not wait:
                _done_inflight(False)
            return count
        count += 1
        if wait:
            _run_task(t)
        else:
            executor.submit(_run_inflight, t)


def _run_inflight(t: dict) -> None:
    try:
        _run_task(t)
    finally:
        _done_inflight(True)


def _done_inflight(wake: bool) -> None:
    global _inflight
    with _running_lock:
        _inflight -= 1
    if wake:
        # 空出一个推送线程：排着队的任务不必等到下一次轮询
        poke()


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


def _extend_lease(t: dict) -> None:
    """只延长租约（不动当前批次）；任务已不在运行（已结束、等待）时不改。"""
    db.exec(
        "UPDATE sn_push_task SET lease_until = %s WHERE factory_code = %s AND task_key = %s AND state = 'RUNNING' "
        "AND runner = %s",
        util.now() + timedelta(seconds=LEASE_SECONDS),
        t["factory_code"],
        t["task_key"],
        RUNNER,
    )


@contextmanager
def _lease_heartbeat(t: dict) -> Iterator[None]:
    """任务运行期间每 HEARTBEAT_SECONDS 续一次租：单次推送再久（超时最长 600 秒，且按连接、写、读分段计时），
    租约也不会过期，不会被当成执行实例已退出而被别处接手、同一批同时推两次；
    实例真退出时租约照常在 LEASE_SECONDS 内过期。"""
    stop = threading.Event()

    def beat() -> None:
        while not stop.wait(HEARTBEAT_SECONDS):
            try:
                _extend_lease(t)
            except Exception:  # noqa: BLE001
                log.warning("push_lease_renew_failed", org=t["factory_code"], key=t["task_key"], exc_info=True)

    th = threading.Thread(target=beat, name="sn-push-lease", daemon=True)
    th.start()
    try:
        yield
    finally:
        stop.set()


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
    返回批次，或 'WAIT:<批次号>'（等重试、等回执）/ 'STOP:<原因>'。等回执超时的按原批次重推（返回该批次）。"""
    org, key = t["factory_code"], t["task_key"]
    cond, args = _batch_cond(key)
    pending = db.all(
        f"SELECT * FROM sn_delivery_batch WHERE factory_code = %s AND {cond} AND result IN ('UNCONFIRMED','FAILED') "
        "ORDER BY created_at, id",
        org,
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
        if b["push_state"] == "WAIT_RECEIPT":
            if b["next_retry_at"] is not None and b["next_retry_at"] > util.now():
                return f"WAIT:{b['batch_no']}"
            why = _receipt_timeout(b)
            return f"STOP:{why}" if why else b
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


def _receipt_timeout(b: dict) -> str | None:
    """等 ③ 回执超时：重推次数未用尽的改为待重推（返回 None，按原批次号、原内容重推）；用尽的记为重试用尽，返回原因。
    回执恰在此时到达（批次已不在等回执）的不处理，返回 None。"""
    waits = settings.push_receipt_waits
    sys = CurrentUser.system()
    with db.tx():
        cur = must_batch(b["batch_no"], lock=True)
        if cur["result"] != UNCONFIRMED or cur["push_state"] != "WAIT_RECEIPT":
            return None
        n = int(cur["receipt_timeouts"])
        wait = waits[min(n, len(waits) - 1)]
        if n < len(waits):
            reason = f"等待 MES 回执超时（{wait} 分钟），第 {n + 1} 次按原批次重推"
            db.exec(
                "UPDATE sn_delivery_batch SET receipt_timeouts = receipt_timeouts + 1, push_state = 'IDLE', "
                "next_retry_at = NULL, fail_code = 'RECEIPT_TIMEOUT', fail_reason = %s, updated_at = %s WHERE id = %s",
                reason,
                util.now(),
                cur["id"],
            )
            out = None
        else:
            reason = f"已重推 {n} 次，等待 MES 回执超时（{wait} 分钟）仍未收到"
            db.exec(
                "UPDATE sn_delivery_batch SET push_state = 'EXHAUSTED', next_retry_at = NULL, "
                "fail_code = 'RECEIPT_TIMEOUT', fail_reason = %s, updated_at = %s WHERE id = %s",
                reason,
                util.now(),
                cur["id"],
            )
            out = f"批次 {cur['batch_no']}：{reason}"
        audit.record(
            sys,
            audit.entry(audit.SN_PUSH)
            .fail(reason)
            .factory(cur["factory_code"])
            .pi(cur["pi_no"])
            .count(cur["qty"])
            .batch(cur["batch_no"])
            .info(f"code=RECEIPT_TIMEOUT timeouts={n} next={'RESEND' if out is None else 'EXHAUSTED'}"),
        )
    return out


def _wait_message(b: dict) -> str:
    if b["push_state"] == "WAIT_RECEIPT":
        return f"批次 {b['batch_no']} 已送达，等待 MES 回执"
    return f"批次 {b['batch_no']} 等待自动重试"


def _run_task(t: dict) -> None:
    key = (t["factory_code"], t["task_key"])
    with _running_lock:
        if key in _running_here:
            return
        _running_here.add(key)
    try:
        with _lease_heartbeat(t):
            _drive(t)
        _transient.pop(key, None)
    except Exception as e:  # noqa: BLE001
        _task_error(t, key, e)
    finally:
        with _running_lock:
            _running_here.discard(key)


def _task_error(t: dict, key: tuple[str, str], e: Exception) -> None:
    """任务执行中出错：数据库锁冲突（死锁、锁等待超时、连接池满）转为等待、稍后自动接着推，
    连续 TRANSIENT_MAX 次仍冲突、或别的异常才停止（由「继续推送」处理）。"""
    n = _transient.get(key, 0) + 1 if is_lock_conflict(e) else 0
    try:
        if 0 < n <= TRANSIENT_MAX:
            _transient[key] = n
            log.warning("push_task_conflict", org=t["factory_code"], key=t["task_key"], attempt=n, err=str(e))
            retry_deadlock(
                lambda: _end_task(
                    t,
                    "WAITING",
                    f"数据库繁忙（锁冲突），{TRANSIENT_RETRY_SECONDS} 秒后自动继续推送",
                    util.now() + timedelta(seconds=TRANSIENT_RETRY_SECONDS),
                )
            )
            return
        _transient.pop(key, None)
        log.error("push_task_failed", org=t["factory_code"], key=t["task_key"], exc_info=e)
        retry_deadlock(lambda: _end_task(t, "STOPPED", f"推送任务异常：{type(e).__name__}"))
    except Exception:  # noqa: BLE001
        # 连结束状态都写不进去：任务留在运行中，租约过期后由调度重新认领
        log.exception("push_task_end_failed", org=t["factory_code"], key=t["task_key"])


def _drive(t: dict) -> None:
    resend = bool(t["resend"])
    seen_resend: set[int] = set()
    while True:
        nxt = _next_batch(t, resend)
        if nxt is None:
            other = (
                db.count(
                    "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s AND status IN "
                    "('TO_PUSH','PUSHED') AND delivery_mode IN ('PULL','PAGE')",
                    t["task_key"],
                    t["factory_code"],
                )
                if t["task_key"] != XO_KEY
                else 0
            )
            cond, args = _batch_cond(t["task_key"])
            sent = db.count(
                f"SELECT COUNT(*) FROM sn_delivery_batch WHERE factory_code = %s AND {cond} AND result = 'SUCCESS' "
                "AND source = 'PUSH'",
                t["factory_code"],
                *args,
            )
            _end_task(t, "DONE", f"已推送 {sent} 批" + (f"；其余 {other} 枚已由 MES 拉取" if other else ""))
            return
        if isinstance(nxt, str):
            kind, _, info = nxt.partition(":")
            if kind == "WAIT":
                _wait(t, must_batch(info))
            else:
                _end_task(t, "STOPPED", info)
            return
        if resend and nxt["id"] in seen_resend:
            _end_task(t, "STOPPED", f"批次 {nxt['batch_no']} 续推仍未确认")
            return
        if resend and nxt["result"] == FAILED:
            seen_resend.add(nxt["id"])
        _renew(t, nxt["batch_no"])
        if push_once(nxt) == "CONFIRMED":
            continue
        b = must_batch(nxt["batch_no"])
        if b["result"] in (SUCCESS, VOIDED):
            # 已确认，或发送期间被作废（号已回号池或已重建）：接着推后续批次
            continue
        if b["push_state"] in ("WAIT_RETRY", "WAIT_RECEIPT"):
            _wait(t, b)
            return
        _end_task(
            t, "STOPPED", f"批次 {b['batch_no']}：{b['fail_reason'] or '自动重试用尽仍未确认'}", None, b["batch_no"]
        )
        return


def _wait(t: dict, b: dict) -> None:
    """任务等这一批（等回执或等重试）到截止时间；③ 回执在设为等待之前已到的，马上再跑。"""
    _end_task(t, "WAITING", _wait_message(b), b["next_retry_at"], b["batch_no"])
    cur = must_batch(b["batch_no"])
    if (cur["result"], cur["push_state"]) != (b["result"], b["push_state"]):
        receipts.wake_task(cur)


def push_once(b: dict) -> str:
    """发送一次。返回 CONFIRMED / WAIT_RECEIPT / FAILED / RETRY / EXHAUSTED / SKIPPED。"""
    b = must_batch(b["batch_no"])
    if b["result"] not in (UNCONFIRMED, FAILED):
        return "CONFIRMED" if b["result"] == SUCCESS else "SKIPPED"
    f = factories.must_get(b["factory_code"])
    target = mes_client.Target.of(f)
    attempt = b["push_count"] + 1
    # 发送前最后一份回执：发送期间 ③ 先到的回执（如重推失败批次时又整批退回）不被随后的应答覆盖
    seen = db.scalar("SELECT COALESCE(MAX(id), 0) FROM sn_delivery_receipt WHERE batch_id = %s", b["id"])
    out = mes_client.send(target, b["batch_no"], b["body_gz"], attempt)
    now = util.now()
    sys = CurrentUser.system()
    db.exec(
        "UPDATE sn_delivery_batch SET push_count = push_count + 1, last_push_at = %s, updated_at = %s WHERE id = %s",
        now,
        now,
        b["id"],
    )
    if out.kind == mes_client.RECEIVED:
        # MES 已收到：等 MES 落库后调 ③ 回执，回执到了才确认、推下一批
        waits = settings.push_receipt_waits
        with db.tx():
            cur = must_batch(b["batch_no"], lock=True)
            if cur["result"] == SUCCESS:
                state = "CONFIRMED"
            elif cur["result"] not in (UNCONFIRMED, FAILED) or (
                cur["result"] == FAILED
                and (
                    b["result"] == UNCONFIRMED
                    or db.exists(
                        "SELECT EXISTS(SELECT 1 FROM sn_delivery_receipt WHERE batch_id = %s AND id > %s)",
                        b["id"],
                        seen,
                    )
                )
            ):
                # 发送期间 ③ 已整批退回（首次推送，或重推失败批次时又退回），或批次已作废
                state = "FAILED"
            else:
                # 续推失败的批次时，MES 收下即回到未确认，等这一次的回执
                wait = waits[min(int(cur["receipt_timeouts"]), len(waits) - 1)]
                db.exec(
                    "UPDATE sn_delivery_batch SET result = %s, voidable = 0, push_state = 'WAIT_RECEIPT', "
                    "next_retry_at = %s, received_at = %s, updated_at = %s WHERE id = %s",
                    UNCONFIRMED,
                    now + timedelta(minutes=wait),
                    now,
                    now,
                    b["id"],
                )
                state = "WAIT_RECEIPT"
        audit.record_isolated(
            sys,
            audit.entry(audit.SN_PUSH)
            .factory(b["factory_code"])
            .pi(b["pi_no"])
            .count(b["qty"])
            .batch(b["batch_no"])
            .info(f"attempt={attempt} received next={state}"),
        )
        if f["cfg_status"] != "OK":
            db.exec(
                "UPDATE sn_factory SET cfg_status = 'OK', cfg_message = '' WHERE factory_code = %s", f["factory_code"]
            )
        return state
    if out.kind == mes_client.RETRY:
        intervals = settings.push_retry_intervals
        with db.tx():
            cur = must_batch(b["batch_no"], lock=True)
            if cur["result"] == SUCCESS:
                return "CONFIRMED"
            if cur["result"] not in (UNCONFIRMED, FAILED):
                # 发送期间已作废（号已回号池或已重建）：不再改这一批
                return "SKIPPED"
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
        if cur["result"] == SUCCESS:
            return "CONFIRMED"
        if cur["result"] not in (UNCONFIRMED, FAILED):
            # 发送期间已作废：保持作废，不改回失败（否则又挡住该 PI 的推送、拉取，续推时按旧内容重发）
            return "SKIPPED"
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
