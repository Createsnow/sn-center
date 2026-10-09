"""生成与分配。（对应 GenerateIT）"""

import time
import uuid

import pymysql
import pytest
from conftest import m, ok, q, uid

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


def test_start_number_only_once_before_first_generation(api, admin, w):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "T", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 10))
    j = w.generate(bill, 2, 500)
    assert j["start_sn"] == "T00500"
    assert w.preview(bill, 1, 900).code == "GEN_START_NOT_ALLOWED"
    assert ok(w.preview(bill, 1)).text("start_sn") == "T00502"


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


def test_start_number_must_be_above_transferred_sns(api, admin, w):
    # PI-B 还没生成过：可以指定起始号，但必须越过已转入的号
    pi_a, pi_b, _, b_b = _two_pis(w, "H", "H", 5, 0)
    ok(_transfer(api, admin, pi_a, pi_b, scope="SINGLE", sn="H00004"))
    bad = w.preview(b_b, 1, 3)
    assert bad.code == "GEN_START_TAKEN"
    assert bad["params"]["sn"] == "H00004"
    assert api.post("/api/pi-init", m(pi_no=pi_b, start_seq=4), admin).code == "GEN_START_TAKEN"
    assert ok(w.preview(b_b, 1, 10)).text("start_sn") == "H00010"
    assert ok(w.preview(b_b, 1)).text("start_sn") == "H00005"


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
    assert api.post("/api/generate/allocate", m(bill_no=bill, qty=7), admin).code == "ALLOC_QTY_EXCEEDED"
    a = ok(api.post("/api/generate/allocate", m(bill_no=bill, qty=4), admin)).body
    assert a["start_sn"] == "L00001"
    assert a["end_sn"] == "L00004"
    assert a["pending_left"] == 2
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'TO_ACQUIRE' AND factory_code = %s", pi, FAC
        )
        == 4
    )
    w.allocate(bill)
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


def _pi_generate(api, admin, p):
    """按 PI 预演的结果逐张凭令牌生成（与页面相同的顺序）。"""
    for it in p["items"]:
        j = ok(
            api.post(
                "/api/generate",
                m(preview_token=it["token"], bill_no=it["bill_no"], qty=it["qty"], start_seq=it["start_override"]),
                admin,
            )
        )
        assert j.text("status") == "SUCCESS", str(j)


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


def test_pi_preview_items_go_stale_out_of_order_and_start_override_only_on_first(api, admin, w):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "Q", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 2))
    w.order(b2, ORG, "C1", pi, ("A", 2))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=4, start_seq=100), admin)).body
    assert [it["start_override"] for it in p["items"]] == [100, None]
    assert p["items"][1]["start_sn"] == "Q00102"
    second = p["items"][1]
    stale = api.post("/api/generate", m(preview_token=second["token"], bill_no=b2, qty=2, start_seq=None), admin)
    assert stale.code == "GEN_PREVIEW_STALE", "第二张须在第一张之后生成"
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=4, start_seq=100), admin)).body
    _pi_generate(api, admin, p)
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert ctx["counter"]["last_seq_dec"] == 103 and ctx["counter"]["start_locked"]
    assert api.post("/api/generate/pi-preview", m(pi_no=pi, qty=1), admin).code == "GEN_PI_QUOTA_EMPTY"


def test_pi_allocate_sends_each_bill_to_its_own_factory_in_one_go(api, admin, w, sql):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "K", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 3))
    w.order(b2, "生成测试二厂", "C1", pi, ("A", 2))
    assert api.post("/api/generate/pi-allocate", m(pi_no=pi), admin).code == "ALLOC_PI_NOTHING"
    _pi_generate(api, admin, ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=5), admin)).body)
    ok(api.post("/api/generate/allocate", m(bill_no=b1, qty=1), admin))

    a = ok(api.post("/api/generate/pi-allocate", m(pi_no=pi), admin)).body
    assert a["qty"] == 4
    assert [(x["bill_no"], x["factory_code"], x["qty"]) for x in a["items"]] == [(b1, FAC, 2), (b2, "F-GEN2", 2)]
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND factory_code = 'F-GEN2' AND status = 'TO_ACQUIRE'", b2
        )
        == 2
    )
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert ctx["pending_alloc"] == 0 and ctx["allocated_qty"] == 5
    assert len(ctx["allocations"]) == 3


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


def test_pi_preview_skips_bills_whose_org_is_not_a_factory_yet(api, admin, w):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "U", 10, 5)
    w.order(b1, "未建档组织", "C1", pi, ("A", 3))
    w.order(b2, ORG, "C1", pi, ("A", 2))
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert "FACTORY_UNMAPPED" in ctx["issues"] and ctx["bills"][0]["factory_code"] is None
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=2), admin)).body
    assert [it["bill_no"] for it in p["items"]] == [b2]
    assert api.post("/api/generate/pi-preview", m(pi_no=pi, qty=3), admin).code == "GEN_QUOTA_EXCEEDED"
