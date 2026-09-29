"""生成与分配。（对应 GenerateIT）"""

import time
import uuid

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


def test_duplicate_full_sn_stops_whole_batch_and_rolls_back(api, admin, w, sql):
    # PI-A 生成 D00001..D00005；D00003 转入 PI-B；PI-B 用同一规则生成 5 枚时遇到 D00003 → 整批回滚
    pi_a, pi_b, b_a, b_b = uid("PI"), uid("PI"), uid("MO"), uid("MO")
    w.pi_rule(pi_a, "D", 10, 5)
    w.pi_rule(pi_b, "D", 10, 5)
    w.order(b_a, ORG, "C1", pi_a, ("A", 5))
    w.order(b_b, ORG, "C1", pi_b, ("A", 5))
    w.generate(b_a, 5)
    w.allocate(b_a)
    ok(
        api.post(
            "/api/transfers/direct",
            m(
                factory_code=FAC,
                pi_no=pi_a,
                scope="SINGLE",
                sn="D00003",
                to_factory=FAC,
                to_pi=pi_b,
                target_source="MANUAL",
                reason="测试转入",
            ),
            admin,
        )
    )
    p = ok(w.preview(b_b, 5))
    assert p.text("start_sn") == "D00001", "transfer-in does not raise PI-B counter"
    g = api.post("/api/generate", m(preview_token=p.text("token"), bill_no=b_b, qty=5), admin)
    assert g.code == "GEN_DUPLICATE_SN", str(g)
    assert g["params"]["sn"] == "D00003"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi_b) == 1, "only the transferred-in SN"
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
