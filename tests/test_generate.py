"""生成与分配。（对应 GenerateIT）"""

import time
import uuid

import pymysql
import pytest
from conftest import m, ok, q, raw_conn, uid

ORG = "生成测试厂"
FAC = "F-GEN"


@pytest.fixture(autouse=True)
def factories(w, once):
    if "gen" not in once:
        once["gen"] = True
        w.factory(FAC, ORG)
        w.factory("F-GEN2", "生成测试二厂")


def test_preview_writes_nothing_and_segments_follow_line_order(api, admin, w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "G", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 3), ("B", 2), ("", 1))
    p = ok(w.preview(bill, 6))
    assert p.text("start_sn") == "G00001"
    assert p.text("end_sn") == "G00006"
    assert p["base_last_seq"] == 0
    segs = p["segments"]
    assert len(segs) == 3
    assert segs[1]["material_code"] == "B"
    assert segs[1]["start_sn"] == "G00004"
    assert segs[2]["material_code"] == ""
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0, "preview must not write SNs"

    w.generate(bill, 6)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PENDING_ALLOC'", pi) == 6
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND material_code = 'A'", pi) == 3
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND material_code = ''", pi) == 1
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND bill_no = %s AND sn = 'G00006' "
            "AND seq_pi_no = %s AND seq_dec = 6 AND customer_code = 'C1' AND factory_code IS NULL",
            pi,
            bill,
            pi,
        )
        == 1
    )


def test_generate_requires_matching_unused_unexpired_preview(api, admin, w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "H", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 10))
    assert api.post("/api/generate", m(preview_token="nope", bill_no=bill, qty=2), admin).code == "GEN_PREVIEW_REQUIRED"
    token = ok(w.preview(bill, 2)).text("token")
    assert api.post("/api/generate", m(preview_token=token, bill_no=bill, qty=3), admin).code == "GEN_PREVIEW_CHANGED"
    ok(api.post("/api/generate", m(preview_token=token, bill_no=bill, qty=2), admin))
    assert api.post("/api/generate", m(preview_token=token, bill_no=bill, qty=2), admin).code == "GEN_PREVIEW_USED"
    sql.exec("UPDATE sn_gen_preview SET expires_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE bill_no = %s", bill)
    t2 = ok(w.preview(bill, 1)).text("token")
    sql.exec("UPDATE sn_gen_preview SET expires_at = '2000-01-01 00:00:00' WHERE token = %s", t2)
    assert api.post("/api/generate", m(preview_token=t2, bill_no=bill, qty=1), admin).code == "GEN_PREVIEW_EXPIRED"


def test_preview_goes_stale_when_pi_max_changes_via_another_order(api, admin, w):
    pi, b1, b2 = uid("PI"), uid("MO"), uid("MO")
    w.pi_rule(pi, "S", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 5))
    w.order(b2, ORG, "C1", pi, ("A", 5))
    t2 = ok(w.preview(b2, 2)).text("token")
    w.generate(b1, 3)
    stale = api.post("/api/generate", m(preview_token=t2, bill_no=b2, qty=2), admin)
    assert stale.code == "GEN_PREVIEW_STALE"
    assert stale["params"]["actual"] == 3
    # 重新预演后，两张订单在同一张 PI 的一套流水上接续
    j = w.generate(b2, 2)
    assert j["start_sn"] == "S00004"
    summary = ok(api.get("/api/generate/context" + q(bill_no=b2), admin))["pi_summary"]
    assert len(summary) == 2


def test_quota_is_order_qty_minus_generated(api, admin, w):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "Q", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 3), ("B", 2))
    assert w.preview(bill, 6).code == "GEN_QUOTA_EXCEEDED"
    assert w.preview(bill, 0).code == "GEN_QTY_INVALID"
    w.generate(bill, 5)
    assert w.preview(bill, 1).code == "GEN_QUOTA_EMPTY"
    ctx = ok(api.get("/api/generate/context" + q(bill_no=bill), admin)).body
    assert ctx["quota"] == 0
    assert "GEN_QUOTA_EMPTY" in ctx["issues"]


def test_busy_when_another_generation_runs_on_same_pi(api, admin, w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "B", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 5))
    token = ok(w.preview(bill, 2)).text("token")
    sql.exec(
        "INSERT INTO sn_gen_job(bill_no, pi_no, factory_code, rule_version_id, qty, start_seq_dec, end_seq_dec, "
        "start_sn, end_sn, status, running_pi, preview_token, segments_json, created_by, created_at) "
        "VALUES ('X', %s, 'F', 0, 1, 1, 1, 'a', 'a', 'RUNNING', %s, %s, '[]', 'x', NOW())",
        pi,
        pi,
        uid("tok"),
    )
    try:
        assert api.post("/api/generate", m(preview_token=token, bill_no=bill, qty=2), admin).code == "GEN_BUSY"
    finally:
        sql.exec("UPDATE sn_gen_job SET running_pi = NULL, status = 'FAILED' WHERE running_pi = %s", pi)


def test_rule_exhausted_stops_without_upper_limit_on_pi_total(api, admin, w):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "X", 10, 1)
    w.order(bill, ORG, "C1", pi, ("A", 20))
    w.generate(bill, 9)
    assert w.preview(bill, 1).code == "GEN_RULE_EXHAUSTED"


def _transfer(api, admin, pi_from, pi_to, **scope):
    body = m(factory_code=FAC, pi_no=pi_from, to_factory=FAC, to_pi=pi_to, target_source="MANUAL", reason="测试转入")
    body.update(scope)
    return api.post("/api/transfers/direct", body, admin)


def _two_pis(w, prefix_a: str, prefix_b: str, qty_a: int, own_b: int):
    """PI-A 生成 qty_a 枚并分配；PI-B 自己生成 own_b 枚。"""
    pi_a, pi_b, b_a, b_b = uid("PI"), uid("PI"), uid("MO"), uid("MO")
    w.pi_rule(pi_a, prefix_a, 10, 5)
    w.pi_rule(pi_b, prefix_b, 10, 5)
    w.order(b_a, ORG, "C1", pi_a, ("A", qty_a))
    w.order(b_b, ORG, "C1", pi_b, ("A", 50))
    w.generate(b_a, qty_a)
    w.allocate(b_a)
    if own_b:
        w.generate(b_b, own_b)
    return pi_a, pi_b, b_a, b_b


def _audit_detail(api, admin, transfer_no: str) -> str:
    rows = ok(api.get("/api/audits" + q(keyword=transfer_no, action="TRANSFER_DIRECT"), admin))["items"]
    return rows[0]["detail"]


def test_generation_continues_after_contiguous_transferred_sns(api, admin, w, sql):
    # PI-B 自己到 D00002；A 的 D00003..D00005 转入 → B 从 D00006 接着排，不重号、不断号
    pi_a, pi_b, _, b_b = _two_pis(w, "D", "D", 5, 2)
    for sn in ("D00003", "D00004", "D00005"):
        ok(_transfer(api, admin, pi_a, pi_b, scope="SINGLE", sn=sn))
    p = ok(w.preview(b_b, 3))
    assert p.text("start_sn") == "D00006"
    assert p["next"]["adjusted"] is True
    assert p["next"]["by_sn"] == "D00005"
    assert p["base_last_seq"] == 2, "own counter itself is not raised by the transfer"
    j = ok(api.post("/api/generate", m(preview_token=p.text("token"), bill_no=b_b, qty=3), admin)).body
    assert j["status"] == "SUCCESS"
    assert (j["start_sn"], j["end_sn"]) == ("D00006", "D00008")
    assert sql.count("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi_b) == 8
    assert sql.count("SELECT COUNT(DISTINCT sn) FROM sn_item WHERE pi_no = %s", pi_b) == 8
    detail = sql.one(
        "SELECT detail FROM sn_audit WHERE action = 'SN_GENERATE' AND pi_no = %s ORDER BY id DESC LIMIT 1", pi_b
    )["detail"]
    assert "start_after_transferred=D00005" in detail


def test_generation_jumps_past_non_contiguous_transferred_sns(api, admin, w):
    # PI-B 自己到 E00002；A 的 E00012 转入 → B 从 E00013 生成，E00003..E00011 不再由 B 生成
    pi_a, pi_b, _, b_b = _two_pis(w, "E", "E", 12, 2)
    c = ok(api.get("/api/transfers/candidates" + q(factory_code=FAC, pi=pi_a, scope="PI", to_pi=pi_b), admin))
    assert c["duplicates"] == {"count": 2, "samples": ["E00001", "E00002"]}
    d = _transfer(api, admin, pi_a, pi_b, scope="PI")
    assert d.code == "TR_DUPLICATE"
    assert d["params"]["count"] == 2
    assert d["params"]["sn"] == "E00001、E00002"
    single = ok(
        api.get(
            "/api/transfers/candidates" + q(factory_code=FAC, pi=pi_a, scope="SINGLE", sn="E00012", to_pi=pi_b),
            admin,
        )
    )
    assert single["duplicates"] is None
    assert single["target"]["before"]["start_sn"] == "E00003"
    assert single["target"]["after"]["start_sn"] == "E00013"
    assert single["target"]["unused"] == 9
    r = ok(_transfer(api, admin, pi_a, pi_b, scope="SINGLE", sn="E00012"))
    assert r["target_hint"]["after"]["start_sn"] == "E00013"
    assert "to_next=E00003->E00013 unused=9" in _audit_detail(api, admin, r["transfer_no"])
    r2 = ok(_transfer(api, admin, pi_a, pi_b, scope="SINGLE", sn="E00010"))
    assert r2["target_hint"] is None, "E00010 is below the new start: nothing moves"
    p = ok(w.preview(b_b, 2))
    assert (p.text("start_sn"), p.text("end_sn")) == ("E00013", "E00014")


def test_transferred_sns_of_other_format_do_not_move_start(api, admin, w):
    pi_a, pi_b, _, b_b = _two_pis(w, "FA", "FB", 3, 1)
    r = ok(_transfer(api, admin, pi_a, pi_b, scope="SINGLE", sn="FA00003"))
    assert r["target_hint"] is None
    p = ok(w.preview(b_b, 1))
    assert p.text("start_sn") == "FB00002"
    assert p["next"]["adjusted"] is False


def test_transfer_after_preview_makes_preview_stale(api, admin, w):
    pi_a, pi_b, _, b_b = _two_pis(w, "G", "G", 5, 1)
    p = ok(w.preview(b_b, 2))
    assert p.text("start_sn") == "G00002"
    ok(_transfer(api, admin, pi_a, pi_b, scope="SINGLE", sn="G00004"))
    g = api.post("/api/generate", m(preview_token=p.text("token"), bill_no=b_b, qty=2), admin)
    assert g.code == "GEN_PREVIEW_STALE", str(g)
    assert ok(w.preview(b_b, 2)).text("start_sn") == "G00005"


def test_pending_transfer_shows_target_hint_before_approval(api, admin, w, sql):
    pi_a, pi_b, _, _ = _two_pis(w, "J", "J", 8, 1)
    op = w.user(uid("opj"), "factory_operator", FAC)
    t = ok(
        api.post(
            "/api/transfers",
            m(pi_no=pi_a, scope="SINGLE", sn="J00008", to_factory=FAC, to_pi=pi_b, reason="申请转入"),
            op,
        )
    )
    assert t["target_hint"]["after"]["start_sn"] == "J00009"
    got = ok(api.get(f"/api/transfers/{t['id']}", admin))
    assert got["target_hint"]["before"]["start_sn"] == "J00002"
    assert got["target_hint"]["unused"] == 6
    a = ok(api.post(f"/api/transfers/{t['id']}/approve", m(note="ok"), admin))
    assert a["target_hint"]["after"]["start_sn"] == "J00009"
    assert ok(api.get(f"/api/transfers/{t['id']}", admin))["target_hint"] is None, "only pending ones carry a hint"


def test_duplicate_full_sn_still_stops_whole_batch_and_rolls_back(api, admin, w, sql):
    # 兜底：库里已有与本 PI 将生成的号相同的完整 SN（这里直接写护栏模拟）→ 停止并整批回滚
    pi_a, pi_b, b_a, b_b = _two_pis(w, "D", "D", 5, 0)
    sql.exec("INSERT INTO sn_key(pi_no, sn, seq_pi_no, seq_dec) VALUES (%s, 'D00003', %s, 999)", pi_b, pi_b)
    p = ok(w.preview(b_b, 5))
    assert p.text("start_sn") == "D00001"
    g = api.post("/api/generate", m(preview_token=p.text("token"), bill_no=b_b, qty=5), admin)
    assert g.code == "GEN_DUPLICATE_SN", str(g)
    assert g["params"]["sn"] == "D00003"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi_b) == 0
    assert sql.count("SELECT COALESCE(MAX(last_seq_dec), 0) FROM sn_pi_counter WHERE pi_no = %s", pi_b) == 0
    job = ok(api.get("/api/generate/jobs" + q(bill_no=b_b), admin)).body[0]
    assert job["status"] == "FAILED"
    # 原 PI 继续从自己的最大号 +1 排
    w.order(b_a, ORG, "C1", pi_a, ("A", 8))
    assert ok(w.preview(b_a, 1)).text("start_sn") == "D00006"


def test_allocate_whole_order_to_its_factory_only(api, admin, w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "L", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 3), ("B", 3))
    w.generate(bill, 6)
    assert (
        api.post("/api/generate/allocate", m(bill_no=bill, factory_code="F-GEN2"), admin).code
        == "ALLOC_FACTORY_MISMATCH"
    )
    # 不能只分一部分：旧调用方传的数量被忽略，整单分完
    a = ok(api.post("/api/generate/allocate", m(bill_no=bill, qty=4), admin)).body
    assert (a["start_sn"], a["end_sn"], a["qty"], a["pending_left"]) == ("L00001", "L00006", 6, 0)
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'TO_ACQUIRE' AND factory_code = %s", pi, FAC
        )
        == 6
    )
    assert api.post("/api/generate/allocate", m(bill_no=bill), admin).code == "ALLOC_NOTHING"


def test_cannot_acquire_before_allocation(api, admin, w):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "N", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 2))
    w.generate(bill, 2)
    assert w.acquire(admin, FAC, pi, str(uuid.uuid4())).code == "ACQ_NOTHING"


def test_large_quantity_runs_as_background_job_with_progress(api, admin, w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "BIG", 32, 6)
    w.order(bill, ORG, "C1", pi, ("A", 8000))
    p = ok(w.preview(bill, 8000))
    j = ok(api.post("/api/generate", m(preview_token=p.text("token"), bill_no=bill, qty=8000), admin))
    job_id = j["id"]
    status = j.text("status")
    for _ in range(240):
        if status != "RUNNING":
            break
        time.sleep(0.25)
        status = ok(api.get(f"/api/generate/jobs/{job_id}", admin)).text("status")
    assert status == "SUCCESS"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 8000
    assert sql.count("SELECT COUNT(*) FROM sn_key WHERE pi_no = %s", pi) == 8000


def test_only_admin_generates(api, admin, w):
    op = w.user(uid("op"), "factory_operator", FAC)
    assert api.post("/api/generate/preview", m(bill_no="x", qty=1), op).code == "FORBIDDEN"
    assert api.get("/api/rules", op).status != 200


def _stuck_job(sql, bill: str, pi: str, token: str) -> int:
    """模拟进程在后台生成途中退出：任务行停在 RUNNING，running_pi 仍占着该 PI。"""
    sql.exec(
        "INSERT INTO sn_gen_job(bill_no, pi_no, factory_code, rule_version_id, qty, start_seq_dec, end_seq_dec, "
        "start_sn, end_sn, status, running_pi, preview_token, segments_json, created_by, created_at) "
        "VALUES (%s, %s, %s, 0, 1, 1, 1, 'a', 'a', 'RUNNING', %s, %s, '[]', 'x', NOW())",
        bill,
        pi,
        FAC,
        pi,
        token,
    )
    return sql.one("SELECT id FROM sn_gen_job WHERE preview_token = %s", token)["id"]


def test_interrupted_job_is_recovered_on_startup_and_pi_can_generate_again(api, admin, w, sql):
    from app.services import generate

    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "RC", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 5))
    job_id = _stuck_job(sql, bill, pi, uuid.uuid4().hex)
    token = ok(w.preview(bill, 2)).text("token")
    assert api.post("/api/generate", m(preview_token=token, bill_no=bill, qty=2), admin).code == "GEN_BUSY"
    assert generate.recover_interrupted() >= 1
    j = sql.one("SELECT status, error_code, running_pi FROM sn_gen_job WHERE id = %s", job_id)
    assert (j["status"], j["error_code"], j["running_pi"]) == ("FAILED", "INTERRUPTED", None)
    assert w.generate(bill, 2)["start_sn"] == "RC00001"


def test_recovery_skips_job_whose_generation_still_holds_the_pi_counter(w, sql):
    from conftest import raw_conn

    from app.services import generate

    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "RL", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 5))
    w.generate(bill, 1)
    job_id = _stuck_job(sql, bill, pi, uuid.uuid4().hex)
    holder = raw_conn()
    try:
        holder.begin()
        with holder.cursor() as c:
            # 正在进行的生成事务全程持有计数器行锁
            c.execute("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", (pi,))
        generate.recover_interrupted()
        assert sql.one("SELECT status FROM sn_gen_job WHERE id = %s", job_id)["status"] == "RUNNING"
    finally:
        holder.rollback()
        holder.close()
    generate.recover_interrupted()
    assert sql.one("SELECT status FROM sn_gen_job WHERE id = %s", job_id)["status"] == "FAILED"


def test_recovered_job_never_writes_numbers_afterwards(w, sql):
    from app.core.exceptions import BizError
    from app.core.security import CurrentUser
    from app.services import generate

    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "RN", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 5))
    token = ok(w.preview(bill, 3)).text("token")
    p = sql.one("SELECT * FROM sn_gen_preview WHERE token = %s", token)
    job_id = _stuck_job(sql, bill, pi, token)
    generate.recover_interrupted()
    # 例如另一实例排队中的任务在回收后才轮到执行：必须放弃，不能再写号
    with pytest.raises(BizError):
        generate._run_job(CurrentUser.system(), job_id, p, True)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0
    assert sql.one("SELECT error_code FROM sn_gen_job WHERE id = %s", job_id)["error_code"] == "INTERRUPTED"


def test_successful_job_status_commits_with_the_numbers(w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "RS", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 5))
    j = w.generate(bill, 4)
    row = sql.one("SELECT status, done_qty, running_pi FROM sn_gen_job WHERE id = %s", j["id"])
    assert (row["status"], row["done_qty"], row["running_pi"]) == ("SUCCESS", 4, None)


def test_lock_conflict_keeps_pooled_connection_but_lost_one_is_dropped(w, sql):
    from conftest import raw_conn

    from app.db.session import db, error_no

    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "RP", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 5))
    w.generate(bill, 1)
    holder = raw_conn()
    try:
        holder.begin()
        with holder.cursor() as c:
            c.execute("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", (pi,))
        # NOWAIT 冲突是服务端错误：回滚后连接仍健康，应回池复用，而不是重连
        with pytest.raises(pymysql.err.OperationalError) as e, db.tx() as conn:
            db.exec("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE NOWAIT", pi)
        assert error_no(e.value) == 3572
        assert conn.open and conn in db._pool.queue
    finally:
        holder.rollback()
        holder.close()

    # 断线（这里由服务端 KILL）才丢弃
    killer = raw_conn()
    try:
        with pytest.raises(pymysql.err.OperationalError), db.tx() as conn:
            with killer.cursor() as c:
                c.execute("KILL %s", (conn.thread_id(),))
            db.scalar("SELECT 1")
        assert conn not in db._pool.queue
    finally:
        killer.close()
    assert db.scalar("SELECT 1") == 1


# ---------------------------------------------------------------- 按 PI


def _pi_items(p, **override):
    return [m(preview_token=it["token"], bill_no=it["bill_no"], qty=it["qty"], **override) for it in p["items"]]


def _pi_generate(api, admin, p):
    """凭按 PI 预演的结果一次生成整张 PI（同步完成）。"""
    r = ok(api.post("/api/generate/pi", m(pi_no=p["pi_no"], items=_pi_items(p)), admin)).body
    assert [j["status"] for j in r["jobs"]] == ["SUCCESS"] * len(p["items"]), r
    return r


def test_pi_preview_spreads_qty_over_bills_in_bill_order_on_one_serial(api, admin, w, sql):
    pi = uid("PI")
    b1, b2, b3 = pi + "-MO1", pi + "-MO2", pi + "-MO3"
    w.pi_rule(pi, "P", 10, 5)
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 4))
    w.order(b1, ORG, "C1", pi, ("A", 3), ("B", 2))
    w.order(b3, ORG, "C1", pi, ("A", 10))
    w.generate(b1, 2)

    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=9), admin)).body
    assert [(it["bill_no"], it["qty"]) for it in p["items"]] == [(b1, 3), (b2, 4), (b3, 2)]
    assert p["start_sn"] == "P00003" and p["end_sn"] == "P00011"
    assert [it["base_last_seq"] for it in p["items"]] == [2, 5, 9]
    assert p["items"][0]["segments"][0]["material_code"] == "A", "第一张订单接着已生成的 2 枚切段"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 2, "预演不落号"

    _pi_generate(api, admin, p)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND sn = 'P00009'", b2) == 1
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s", b3) == 2

    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert [b["bill_no"] for b in ctx["bills"]] == [b1, b2, b3]
    assert ctx["generated_qty"] == 11 and ctx["pending_alloc"] == 11 and ctx["quota"] == 8
    assert ctx["bills"][1]["factory_code"] == "F-GEN2"
    assert ctx["next"]["start_sn"] == "P00012"
    assert ok(api.get("/api/generate/pi-context" + q(bill_no=b2), admin))["pi_no"] == pi

    assert api.post("/api/generate/pi-preview", m(pi_no=pi, qty=9), admin).code == "GEN_QUOTA_EXCEEDED"


def test_pi_generate_requires_the_whole_preview_in_order(api, admin, w, sql):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "Q", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 2))
    w.order(b2, ORG, "C1", pi, ("A", 2))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=4), admin)).body
    assert p["items"][1]["start_sn"] == "Q00003"
    items = _pi_items(p)
    gen = lambda its: api.post("/api/generate/pi", m(pi_no=pi, items=its), admin)  # noqa: E731
    assert gen(items[::-1]).code == "GEN_PREVIEW_CHANGED", "顺序打乱"
    assert gen(items[1:]).code == "GEN_PREVIEW_STALE", "缺第一张"
    assert gen([items[0], items[0]]).code == "GEN_PREVIEW_CHANGED", "同一张重复"
    assert api.post("/api/generate/pi", m(pi_no=uid("PI"), items=items), admin).code == "GEN_PREVIEW_CHANGED"
    assert sql.count("SELECT COUNT(*) FROM sn_gen_job WHERE pi_no = %s", pi) == 0, "校验不过不建任务"
    r = _pi_generate(api, admin, p)
    assert [j["bill_no"] for j in r["jobs"]] == [b1, b2] and r["qty"] == 4
    assert gen(items).code == "GEN_PREVIEW_USED"
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert ctx["counter"]["last_seq_dec"] == 4 and ctx["counter"]["start_locked"]
    assert ctx["running_job"] is None, "整张 PI 结束后释放 running_pi"
    assert api.post("/api/generate/pi-preview", m(pi_no=pi, qty=1), admin).code == "GEN_PI_QUOTA_EMPTY"


def test_pi_generate_rolls_back_every_bill_when_a_later_bill_fails(api, admin, w, sql):
    pi = uid("PI")
    b1, b2, b3 = pi + "-MO1", pi + "-MO2", pi + "-MO3"
    w.pi_rule(pi, "R", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 3))
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 3))
    w.order(b3, ORG, "C1", pi, ("A", 3))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=9), admin)).body
    # 第三张号段里的 R00008 已被本 PI 占用（流水归属本 PI，不影响起点），生成到第三张时查重失败
    sql.exec("INSERT INTO sn_key(pi_no, sn, seq_pi_no, seq_dec) VALUES (%s, 'R00008', %s, 8)", pi, pi)
    r = api.post("/api/generate/pi", m(pi_no=pi, items=_pi_items(p)), admin)
    assert r.code == "GEN_DUPLICATE_SN"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0, "前两张也回滚"
    assert sql.count("SELECT COUNT(*) FROM sn_key WHERE pi_no = %s", pi) == 1
    assert sql.count("SELECT COUNT(*) FROM sn_bill_gen WHERE pi_no = %s AND generated_qty > 0", pi) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_pi_counter WHERE pi_no = %s AND last_seq_dec > 0", pi) == 0
    rows = sql.all("SELECT status, error_code, error_msg, running_pi FROM sn_gen_job WHERE pi_no = %s", pi)
    assert [x["status"] for x in rows] == ["FAILED"] * 3
    assert all(x["error_code"] == "GEN_DUPLICATE_SN" and x["running_pi"] is None for x in rows)
    assert rows[0]["error_msg"].startswith(b3 + ":"), "失败原因写明是哪张订单"


def test_pi_generate_holds_the_pi_until_every_bill_is_done(api, admin, w, sql):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "B", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 2))
    w.order(b2, ORG, "C1", pi, ("A", 2))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=4), admin)).body
    # 模拟别的生成正占着这张 PI
    sql.exec(
        "INSERT INTO sn_gen_job(bill_no, pi_no, factory_code, customer_code, rule_version_id, qty, start_seq_dec, "
        "end_seq_dec, start_sn, end_sn, status, done_qty, running_pi, preview_token, segments_json, created_by, "
        "created_at) VALUES (%s,%s,%s,'C1',1,1,1,1,'x','x','RUNNING',0,%s,%s,'[]','t',NOW())",
        b1,
        pi,
        FAC,
        pi,
        uuid.uuid4().hex,
    )
    assert api.post("/api/generate/pi", m(pi_no=pi, items=_pi_items(p)), admin).code == "GEN_BUSY"
    sql.exec("DELETE FROM sn_gen_job WHERE pi_no = %s", pi)
    assert sql.count("SELECT COUNT(*) FROM sn_gen_preview WHERE pi_no = %s AND used_at IS NOT NULL", pi) == 0
    _pi_generate(api, admin, p)


def test_pi_allocate_sends_each_bill_to_its_own_factory_in_one_go(api, admin, w, sql):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "K", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 3))
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 2))
    assert api.post("/api/generate/pi-allocate", m(pi_no=pi), admin).code == "ALLOC_PI_NOTHING"
    _pi_generate(api, admin, ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=5), admin)).body)

    a = ok(api.post("/api/generate/pi-allocate", m(pi_no=pi), admin)).body
    assert a["qty"] == 5
    assert [(x["bill_no"], x["factory_code"], x["qty"]) for x in a["items"]] == [(b1, FAC, 3), (b2, "F-GEN2", 2)]
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND factory_code = 'F-GEN2' AND status = 'TO_ACQUIRE'", b2
        )
        == 2
    )
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert ctx["pending_alloc"] == 0 and ctx["allocated_qty"] == 5
    assert len(ctx["allocations"]) == 2


def test_pi_list_shows_orgs_and_matches_by_bill(api, admin, w):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "L", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 3))
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 2))
    rows = ok(api.get("/api/orders/pis" + q(q=b2, pending=1), admin)).body
    assert len(rows) == 1
    r = rows[0]
    assert r["pi"] == pi and r["bills"] == 2 and r["quota"] == 5, "按单据命中也汇总整张 PI"
    assert sorted(o["factory_code"] for o in r["orgs"]) == [FAC, "F-GEN2"]
    assert r["unmapped"] is False and r["start_locked"] is False


def test_pi_cannot_generate_while_any_bill_org_is_not_a_factory_yet(api, admin, w):
    """有订单的生产组织未建档：整张 PI 都不能生成（不先生成其余订单），建档后再一起生成。"""
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "U", 10, 5)
    w.order(b1, "未建档组织", "C1", pi, ("A", 3))
    w.order(b2, ORG, "C1", pi, ("A", 2))
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert "FACTORY_UNMAPPED" in ctx["issues"] and ctx["bills"][0]["factory_code"] is None
    r = api.post("/api/generate/pi-preview", m(pi_no=pi, qty=2), admin)
    assert r.code == "FACTORY_UNMAPPED" and r["params"]["org"] == "未建档组织"
    row = ok(api.get("/api/orders/pis" + q(q=pi), admin)).body[0]
    assert row["unmapped"] is True and row["quota"] == 5


def test_pi_generate_runs_in_background_when_the_pi_total_is_large(api, admin, w, sql):
    """各张都不超过同步阈值（测试里 3000），但整张 PI 合计超过：整体转后台，仍是一个事务。"""
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "BG", 32, 6)
    w.order(b1, ORG, "C1", pi, ("A", 2000))
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 2000))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=4000), admin)).body
    r = ok(api.post("/api/generate/pi", m(pi_no=pi, items=_pi_items(p)), admin)).body
    ids = [j["id"] for j in r["jobs"]]
    statuses = [j["status"] for j in r["jobs"]]
    for _ in range(240):
        if "RUNNING" not in statuses:
            break
        time.sleep(0.25)
        statuses = [ok(api.get(f"/api/generate/jobs/{i}", admin)).text("status") for i in ids]
    assert statuses == ["SUCCESS", "SUCCESS"]
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND bill_no = %s", pi, b2) == 2000
    assert ok(api.get("/api/generate/pi-context" + q(pi=pi), admin))["pending_alloc"] == 4000


# ---------------------------------------------------------------- 撤销生成


def _revoke(api, admin, job_id, reason="点错数量"):
    return api.post(f"/api/generate/jobs/{job_id}/revoke", m(reason=reason), admin)


def test_revoke_last_generation_rolls_back_and_regenerates_the_same_sns(api, admin, w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "RV", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 10))
    j1 = w.generate(bill, 3)
    j2 = w.generate(bill, 4)
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert {j["id"]: j["revocable"] for j in ctx["jobs"]} == {j1["id"]: False, j2["id"]: True}

    assert _revoke(api, admin, j1["id"]).code == "GEN_REVOKE_NOT_LAST"
    assert _revoke(api, admin, j2["id"], " ").code == "GEN_REVOKE_REASON_REQUIRED"
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_REVOKE' AND result = 'FAIL' AND pi_no IS NULL "
            "AND detail LIKE %s",
            f"%/jobs/{j2['id']}/revoke",
        )
        == 1
    ), "被拒绝的撤销按 SN_REVOKE 记失败痕迹"
    assert api.post("/api/generate/jobs/999999999/revoke", m(reason="x"), admin).code == "GEN_JOB_NOT_FOUND"

    r = ok(_revoke(api, admin, j2["id"])).body
    assert (r["qty"], r["start_sn"], r["end_sn"], r["last_seq_dec"]) == (4, "RV00004", "RV00007", 3)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 3
    assert sql.count("SELECT COUNT(*) FROM sn_key WHERE pi_no = %s", pi) == 3
    assert ok(api.get(f"/api/generate/jobs/{j2['id']}", admin)).text("status") == "REVOKED"
    assert _revoke(api, admin, j2["id"]).code == "GEN_REVOKE_NOT_SUCCESS"
    a = sql.one("SELECT * FROM sn_audit WHERE action = 'SN_REVOKE' AND result = 'OK' AND pi_no = %s", pi)
    assert (a["qty"], a["start_sn"], a["end_sn"], a["reason"], a["before_status"]) == (
        4,
        "RV00004",
        "RV00007",
        "点错数量",
        "PENDING_ALLOC",
    )

    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert (ctx["generated_qty"], ctx["pending_alloc"], ctx["quota"]) == (3, 3, 7)
    assert ctx["counter"]["last_seq_dec"] == 3 and ctx["next"]["start_sn"] == "RV00004"
    assert {j["id"]: j["revocable"] for j in ctx["jobs"]} == {j1["id"]: True, j2["id"]: False}

    # 重新生成：号与撤销前相同，号段不留空洞
    j3 = w.generate(bill, 2)
    assert (j3["start_sn"], j3["end_sn"]) == ("RV00004", "RV00005")


def test_revoke_refused_once_any_sn_of_the_generation_is_allocated(api, admin, w, sql):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "RA", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 2))
    w.order(b2, ORG, "C1", pi, ("A", 3))
    r = _pi_generate(api, admin, ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=5), admin)).body)
    w.allocate(b1)
    r = api.post(f"/api/generate/batches/{r['batch']}/revoke", m(reason="点错"), admin)
    assert r.code == "GEN_REVOKE_ALLOCATED" and r["params"]["count"] == 2
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 5
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert ctx["revocable_batch"] is None and ctx["generated_qty"] == 5
    assert not any(j["revocable"] for j in ctx["jobs"])


def test_revoke_whole_pi_generation_from_last_order_back_to_never_generated(api, admin, w, sql):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "RP", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 3))
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 4))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=6), admin)).body
    g1 = _pi_generate(api, admin, p)
    # 第二次生成（剩下的 1 枚）在后：要先撤销它
    g2 = _pi_generate(api, admin, ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=1), admin)).body)
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert ctx["revocable_batch"]["batch"] == g2["batch"] and ctx["revocable_batch"]["qty"] == 1
    assert api.post(f"/api/generate/batches/{g1['batch']}/revoke", m(reason="x"), admin).code == "GEN_REVOKE_NOT_LAST"
    ok(api.post(f"/api/generate/batches/{g2['batch']}/revoke", m(reason="x"), admin))
    # 整次撤销：两张订单的号一次退回（按任务号调用也撤销它所属的整次）
    r = ok(_revoke(api, admin, g1["jobs"][1]["id"])).body
    assert (r["batch"], r["qty"], r["bills"], r["start_sn"], r["end_sn"]) == (
        g1["batch"],
        6,
        [b1, b2],
        "RP00001",
        "RP00006",
    )
    assert (
        sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_REVOKE' AND result = 'OK' AND pi_no = %s", pi) == 3
    )

    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_key WHERE pi_no = %s", pi) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_bill_gen WHERE pi_no = %s", pi) == 0
    c = sql.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s", pi)
    assert (c["last_seq_dec"], c["generated_qty"], c["start_locked"], c["locked_by"]) == (0, 0, 0, None)
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert [(b["bill_no"], b["generated_qty"], b["quota"]) for b in ctx["bills"]] == [(b1, 0, 3), (b2, 0, 4)]
    assert ctx["next"]["start_sn"] == "RP00001"

    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=7), admin)).body
    assert (p["start_sn"], p["end_sn"]) == ("RP00001", "RP00007")


# ---------------------------------------------------------------- 数量上限 / 进度 / 取消


def test_generation_limits_are_exposed_and_hard_max_is_enforced(api, admin, w):
    """弹窗默认单次上限（可解除）与单次硬上限（测试里 20000）。"""
    pi = uid("PI")
    bill = pi + "-MO1"
    w.pi_rule(pi, "LM", 32, 6)
    w.order(bill, ORG, "C1", pi, ("A", 25000))
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert ctx["limits"] == {"default": 10000, "max": 20000} and ctx["quota"] == 25000
    r = api.post("/api/generate/pi-preview", m(pi_no=pi, qty=20001), admin)
    assert r.code == "GEN_QTY_OVER_MAX" and r["params"] == {"qty": 20001, "max": 20000}
    assert w.preview(bill, 20001).code == "GEN_QTY_OVER_MAX"
    assert ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=20000), admin))["qty"] == 20000


def test_pi_list_shows_rule_fixed_and_running_progress(api, admin, w, sql):
    pi = uid("PI")
    bill = pi + "-MO1"
    w.order(bill, ORG, "C1", pi, ("A", 4))
    row = ok(api.get("/api/orders/pis" + q(q=pi), admin)).body[0]
    assert row["rule_fixed"] is False and row["running"] is None, "没有绑定、也没指定过：要先选规则"
    w.pi_rule(pi, "RF", 10, 5)
    row = ok(api.get("/api/orders/pis" + q(q=pi), admin)).body[0]
    assert row["rule_fixed"] is True
    sql.exec(
        "INSERT INTO sn_gen_job(bill_no, pi_no, gen_batch, factory_code, customer_code, rule_version_id, qty, "
        "start_seq_dec, end_seq_dec, start_sn, end_sn, status, done_qty, running_pi, preview_token, segments_json, "
        "created_by, created_at) VALUES (%s,%s,'GTEST1',%s,'C1',1,4,1,4,'x','y','RUNNING',3,%s,%s,'[]','t',NOW())",
        bill,
        pi,
        FAC,
        pi,
        uuid.uuid4().hex,
    )
    try:
        row = ok(api.get("/api/orders/pis" + q(q=pi), admin)).body[0]
        assert row["running"] == {"batch": "GTEST1", "qty": 4, "done_qty": 3}
    finally:
        sql.exec("DELETE FROM sn_gen_job WHERE pi_no = %s", pi)


def _wait_batch(api, admin, batch):
    b = ok(api.get(f"/api/generate/batches/{batch}", admin)).body
    for _ in range(240):
        if b["status"] != "RUNNING":
            break
        time.sleep(0.25)
        b = ok(api.get(f"/api/generate/batches/{batch}", admin)).body
    return b


def test_batch_progress_covers_the_whole_pi_generation(api, admin, w):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "PG", 32, 6)
    w.order(b1, ORG, "C1", pi, ("A", 2000))
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 2000))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=4000), admin)).body
    r = ok(api.post("/api/generate/pi", m(pi_no=pi, items=_pi_items(p)), admin)).body
    assert {j["gen_batch"] for j in r["jobs"]} == {r["batch"]}
    b = _wait_batch(api, admin, r["batch"])
    assert (b["status"], b["qty"], b["done_qty"], b["bills"]) == ("SUCCESS", 4000, 4000, 2)
    assert (b["start_sn"], b["end_sn"], b["current_sn"]) == (p["start_sn"], p["end_sn"], p["end_sn"])
    assert api.get("/api/generate/batches/NOPE", admin).code == "GEN_BATCH_NOT_FOUND"
    assert api.post(f"/api/generate/batches/{r['batch']}/cancel", m(), admin).code == "GEN_CANCEL_NOT_RUNNING"


def test_cancel_running_generation_rolls_back_everything_but_keeps_audit(api, admin, w, sql):
    """生成中取消：已写入的号、查重护栏、计数器、订单已生成数、规则使用标记都不留；任务记为已取消；痕迹保留。"""
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "CX", 32, 6)
    w.order(b1, ORG, "C1", pi, ("A", 2000))
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 2000))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=4000), admin)).body
    version = sql.one("SELECT rule_version_id v FROM sn_gen_preview WHERE token = %s", p["items"][0]["token"])["v"]
    # 先占住这张 PI 的计数器，让后台生成停在开头，保证取消发生在生成过程中
    holder = raw_conn()
    holder.autocommit(False)
    with holder.cursor() as c:
        c.execute(
            "INSERT INTO sn_pi_counter(pi_no, last_seq_dec, generated_qty, imported_qty, start_locked, updated_at) "
            "VALUES (%s,0,0,0,0,NOW()) ON DUPLICATE KEY UPDATE pi_no = pi_no",
            (pi,),
        )
        holder.commit()
        c.execute("SELECT * FROM sn_pi_counter WHERE pi_no = %s FOR UPDATE", (pi,))
    try:
        r = ok(api.post("/api/generate/pi", m(pi_no=pi, items=_pi_items(p)), admin)).body
        c = ok(api.post(f"/api/generate/batches/{r['batch']}/cancel", m(), admin)).body
        assert c["status"] == "RUNNING" and c["cancel_by"] == "admin"
        # 重复取消不报错，也不重复留痕
        ok(api.post(f"/api/generate/batches/{r['batch']}/cancel", m(), admin))
    finally:
        holder.rollback()
        holder.close()
    b = _wait_batch(api, admin, r["batch"])
    assert (b["status"], b["done_qty"], b["error_code"]) == ("CANCELED", 0, "GEN_CANCELED")
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_key WHERE pi_no = %s", pi) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_bill_gen WHERE pi_no = %s", pi) == 0
    c = sql.one("SELECT * FROM sn_pi_counter WHERE pi_no = %s", pi)
    assert (c["last_seq_dec"], c["generated_qty"], c["start_locked"]) == (0, 0, 0)
    assert sql.one("SELECT used FROM sn_rule_version WHERE id = %s", version)["used"] == 0
    assert (
        sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_GEN_CANCEL' AND result = 'OK' AND pi_no = %s", pi)
        == 1
    )
    assert (
        sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_GENERATE' AND result = 'FAIL' AND pi_no = %s", pi)
        == 2
    )
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert ctx["running_batch"] is None and ctx["quota"] == 4000
    assert {j["status"] for j in ctx["jobs"]} == {"CANCELED"}
    # 取消后可重新预演、生成，号从头开始
    p2 = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=10), admin)).body
    assert p2["start_sn"] == p["start_sn"]


def test_cancel_after_some_chunks_were_written_deletes_them(api, admin, w, sql, monkeypatch):
    """取消请求在写了几块号之后才到：整批回滚，已写入的号全部删除。"""
    from app.services import generate as gen

    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "CY", 32, 6)
    w.order(bill, ORG, "C1", pi, ("A", 2500))
    p = ok(w.preview(bill, 2500))
    original = gen._progress

    def progress(job_id, done):
        if done >= 2000:  # 已写入两块（2000 枚）后有人点了取消
            sql.exec("UPDATE sn_gen_job SET cancel_at = NOW(), cancel_by = 'admin' WHERE id = %s", job_id)
        return original(job_id, done)

    monkeypatch.setattr(gen, "_progress", progress)
    r = api.post("/api/generate", m(preview_token=p.text("token"), bill_no=bill, qty=2500), admin)
    assert r.code == "GEN_CANCELED"
    j = sql.one("SELECT * FROM sn_gen_job WHERE bill_no = %s", bill)
    assert (j["status"], j["done_qty"], j["running_pi"]) == ("CANCELED", 0, None)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_key WHERE pi_no = %s", pi) == 0
