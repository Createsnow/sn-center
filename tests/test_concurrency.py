"""并发：多张 PI 同时生成、分配、推送时的死锁与连接占用（docs/测试记录/ 下《多账户并发生成推送测试报告》《数据库存储压测报告》）。"""

from __future__ import annotations

import threading
import time
from datetime import timedelta

import pymysql
import pytest
from conftest import m, ok, uid

from app.core import utils as util


def _deadlock() -> pymysql.err.OperationalError:
    return pymysql.err.OperationalError(1213, "Deadlock found when trying to get lock; try restarting transaction")


def _mk(w, qty: int, mes: bool = True, batch_size: int = 5000):
    """一个组织、一张 PI、一张单据、一个物料行，选自定义规则（首次生成时新建规则版本、占用新前缀）。"""
    org = w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org, batch_size=batch_size)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", qty, "0100"))
    prefix = "Q" + uid("").replace("-", "")[-8:] + "X"
    ok(w.select(pi, [(bill, 1)], custom=m(prefix=prefix, base=10, seq_len=6, with_length=False)))
    return org, pi, bill


# ---------------------------------------------------------------------- 连接池


def test_nested_new_tx_does_not_wait_for_pool(app_client):
    """事务里另开独立事务（失败痕迹、生成进度、取消检查）用备用名额：主名额全被占着时也不用等满 30 秒取连接超时。"""
    from app.db.mysql import Db
    from app.db.session import db as app_db

    d = Db()
    d.configure(app_db.target, 1)
    try:
        t0 = time.monotonic()
        with d.tx():
            with d.new_tx():
                assert d.scalar("SELECT 1") == 1
            with d.connection() as c, c.cursor() as cur:
                cur.execute("SELECT 1")
        assert time.monotonic() - t0 < 5
        # 名额都已归还：最外层再取照常拿得到
        with d.tx():
            assert d.scalar("SELECT 2") == 2
    finally:
        d.close()


def test_retry_deadlock_redoes_whole_transaction_outside_tx(app_client):
    from app.db.session import db, retry_deadlock

    calls = []

    def fn() -> str:
        calls.append(1)
        if len(calls) == 1:
            raise _deadlock()
        return "ok"

    assert retry_deadlock(fn) == "ok" and len(calls) == 2

    # 外层事务里：死锁已回滚整笔外层事务，不在里面重做，原样抛出
    calls.clear()
    with pytest.raises(pymysql.err.OperationalError), db.tx():
        retry_deadlock(fn)
    assert len(calls) == 1

    # 独立事务（new_tx）只回滚它自己：外层事务里也可重做
    calls.clear()
    with db.tx():
        assert retry_deadlock(fn, independent=True) == "ok"
    assert len(calls) == 2

    # 别的错误不重做；重做次数用尽照样抛
    with pytest.raises(pymysql.err.OperationalError):
        retry_deadlock(lambda: (_ for _ in ()).throw(_deadlock()))


# ---------------------------------------------------------------------- 生成


def test_generation_redone_after_deadlock(w, sql, monkeypatch):
    """生成事务撞上死锁（整笔已回滚）：PI 操作锁还在手里，按原计划整笔重做，任务成功，号、计数、规则都只有一份。"""
    from app.services import generate

    org, pi, bill = _mk(w, 50, mes=False)
    real = generate._generate_in_tx
    calls = []

    def flaky(cu, j, plan):
        calls.append(1)
        real(cu, j, plan)
        if len(calls) == 1:
            # 号都写进去之后才死锁：重做时须从头再来，不能留下第一次的号
            raise _deadlock()

    monkeypatch.setattr(generate, "_generate_in_tx", flaky)
    j = w.generate(pi)
    assert j["status"] == "SUCCESS" and len(calls) == 2
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 50
    assert sql.scalar("SELECT own_generated FROM sn_line_count WHERE bill_no = %s AND seq = 1", bill) == 50
    assert sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) == 50
    assert sql.count("SELECT COUNT(*) FROM sn_rule_version WHERE claimed_pi = %s", pi) == 1
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_GENERATE' AND pi_no = %s", pi) == 1


def test_generation_fails_after_repeated_deadlocks(w, sql, monkeypatch):
    from app.db import mysql
    from app.services import generate

    org, pi, bill = _mk(w, 5, mes=False)
    calls = []

    def always(cu, j, plan):
        calls.append(1)
        raise _deadlock()

    monkeypatch.setattr(generate, "_generate_in_tx", always)
    p = ok(w.preview(pi))
    r = w.api.post("/api/generate/run", m(token=p["token"]), w.admin)
    assert r.status == 409 and r.code == "CONFLICT_RETRY"
    assert len(calls) == mysql.DEADLOCK_ATTEMPTS
    assert sql.scalar("SELECT status FROM sn_gen_job WHERE pi_no = %s", pi) == "FAILED"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0


def test_concurrent_generation_on_many_pis(w, sql):
    """八张 PI 同时首次生成（各自新建自定义规则、占用新前缀）再同时分配：全部成功，不因死锁失败
    （报告第一轮 8 户同时生成 6 户死锁失败）。"""
    n, qty = 8, 2000
    made = [_mk(w, qty) for _ in range(n)]
    tokens = [ok(w.preview(pi))["token"] for _, pi, _ in made]
    start = threading.Barrier(n)
    out: list = [None] * n

    def run(i: int) -> None:
        start.wait()
        out[i] = w.api.post("/api/generate/run", m(token=tokens[i]), w.admin)

    ths = [threading.Thread(target=run, args=(i,)) for i in range(n)]
    for t in ths:
        t.start()
    for t in ths:
        t.join(120)
    assert [(r.status, r["status"]) for r in out] == [(200, "SUCCESS")] * n, [str(r) for r in out]

    start = threading.Barrier(n)

    def alloc(i: int) -> None:
        start.wait()
        out[i] = w.api.post("/api/allocation/allocate", m(pi_no=made[i][1]), w.admin)

    ths = [threading.Thread(target=alloc, args=(i,)) for i in range(n)]
    for t in ths:
        t.start()
    for t in ths:
        t.join(120)
    assert [r.status for r in out] == [200] * n, [str(r) for r in out]
    for org, pi, _ in made:
        assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'TO_PUSH'", pi) == qty
        assert sql.scalar("SELECT MAX(seq_dec) FROM sn_item WHERE seq_pi_no = %s", pi) == qty
        assert sql.scalar("SELECT state FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, pi) == (
            "QUEUED"
        )
    w.push()
    for _, pi, _ in made:
        assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == qty


def test_generation_slots_queue_jobs(w, monkeypatch):
    """同时写号池的生成数有上限：名额占满时当场生成排队，等不到报稍后重试，任务记为失败（不留号）。"""
    from app.services import generate

    monkeypatch.setenv("SN_GEN_CONCURRENCY", "1")
    monkeypatch.setattr(generate, "SLOT_WAIT_SECONDS", 0.2)
    slots = generate._gen_slots()
    org, pi, bill = _mk(w, 3, mes=False)
    p = ok(w.preview(pi))
    assert slots.acquire(timeout=1)
    try:
        r = w.api.post("/api/generate/run", m(token=p["token"]), w.admin)
    finally:
        slots.release()
    assert r.status == 409 and r.code == "CONFLICT_RETRY"
    assert w.generate(pi)["status"] == "SUCCESS"


# ---------------------------------------------------------------------- 推送


def _task(sql, org, key):
    return sql.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, key)


def test_push_task_waits_after_lock_conflict_then_resumes(w, sql, monkeypatch):
    """推送任务认领下一批时撞上死锁：不停止，转为等待、稍后自动接着推（报告里停在 STOPPED，要人工继续推送）。"""
    from app.services.delivery import push

    org, pi, bill = _mk(w, 12, batch_size=5)
    w.generate(pi)
    w.allocate(pi)
    real = push.build_delivery
    calls = []

    def flaky(*a, **kw):
        calls.append(1)
        if len(calls) == 2:
            raise _deadlock()
        return real(*a, **kw)

    monkeypatch.setattr(push, "build_delivery", flaky)
    w.push()
    t = _task(sql, org, pi)
    assert t["state"] == "WAITING" and "锁冲突" in t["message"]
    assert t["run_after"] > util.now() + timedelta(seconds=push.TRANSIENT_RETRY_SECONDS - 5)
    sql.exec("UPDATE sn_push_task SET run_after = %s WHERE factory_code = %s", util.now(), org)
    w.push()
    assert _task(sql, org, pi)["state"] == "DONE"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 12


def test_push_task_stops_after_repeated_lock_conflicts(w, sql, monkeypatch):
    from app.services.delivery import push

    org, pi, bill = _mk(w, 3)
    w.generate(pi)
    w.allocate(pi)
    monkeypatch.setattr(push, "TRANSIENT_MAX", 1)
    monkeypatch.setattr(push, "build_delivery", lambda *a, **kw: (_ for _ in ()).throw(_deadlock()))
    w.push()
    assert _task(sql, org, pi)["state"] == "WAITING"
    sql.exec("UPDATE sn_push_task SET run_after = %s WHERE factory_code = %s", util.now(), org)
    w.push()
    t = _task(sql, org, pi)
    assert t["state"] == "STOPPED" and "OperationalError" in t["message"]


def test_scheduler_claims_only_free_push_workers(w, sql, monkeypatch):
    """调度只认领空闲推送线程数那么多个任务：其余留在排队，不先标成运行中（报告里推送线程 8 个、运行中峰值 72）。"""
    from app.services.delivery import push

    made = []
    for _ in range(3):
        org, pi, bill = _mk(w, 3)
        w.generate(pi)
        w.allocate(pi)
        made.append((org, pi))
    submitted = []
    monkeypatch.setattr(push, "PUSH_WORKERS", 2)
    monkeypatch.setattr(push.executor, "submit", lambda fn, t: submitted.append(t))
    try:
        assert push.run_pending(wait=False) == 2
        assert push.run_pending(wait=False) == 0, "没有空闲线程：不再认领"
        states = sorted(_task(sql, org, pi)["state"] for org, pi in made)
        assert states == ["QUEUED", "RUNNING", "RUNNING"]
        push._done_inflight(False)
        assert push.run_pending(wait=False) == 1
    finally:
        with push._running_lock:
            push._inflight = 0
    for t in submitted:
        push._run_task(t)
    w.push()
    assert all(_task(sql, org, pi)["state"] == "DONE" for org, pi in made)


def test_start_task_for_many_new_orgs_at_once(app_client, sql):
    """各组织同时建第一个推送任务（分配后启动推送）：互不卡住，不因死锁报错。"""
    from app.core.security import CurrentUser
    from app.services import delivery

    orgs = [uid("S")[:12] for _ in range(12)]
    start = threading.Barrier(len(orgs))
    errors: list = []

    def run(org: str) -> None:
        start.wait()
        try:
            delivery.start_task(CurrentUser.system(), org, "PI-START")
        except Exception as e:  # noqa: BLE001
            errors.append(e)

    ths = [threading.Thread(target=run, args=(o,)) for o in orgs]
    for t in ths:
        t.start()
    for t in ths:
        t.join(60)
    try:
        assert errors == []
        assert all(_task(sql, o, "PI-START")["state"] == "QUEUED" for o in orgs)
    finally:
        sql.exec("DELETE FROM sn_push_task WHERE task_key = 'PI-START'")
