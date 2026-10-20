"""规则库与规则选取（F-08）、SN 生成（F-09）、生成与分配页（F-10）。"""

from __future__ import annotations

from conftest import m, ok, uid


def mk(w, *lines, pi=None, org=None, bill=None, customer="C1"):
    org = org or w.org(uid("O")[:10])
    pi = pi or uid("PI")
    bill = bill or uid("MO")
    w.order(bill, org, customer, pi, *lines)
    return org, pi, bill


def P() -> str:
    return "P" + uid("").replace("-", "")


# ---------------------------------------------------------------- 规则库


def test_prefix_conflicts_in_both_directions(w, api):
    w.rule("AX")
    for p in ("AXB", "A", "AX"):
        r = api.post("/api/rules", m(rule_code=uid("R"), rule_name="x", prefix=p, base=10, seq_len=5), w.admin)
        assert r.code == "RULE_PREFIX_CONFLICT", p
    assert (
        api.post("/api/rules", m(rule_code=uid("R"), rule_name="x", prefix="ax", base=10, seq_len=5), w.admin).status
        == 200
    )


def test_used_version_needs_new_prefix_for_format_change(w, api, sql):
    org, pi, bill = mk(w, ("M1", 10))
    rid = w.rule(P(), seq_len=5)
    ok(w.bind_pi(pi, rid))
    w.generate(pi, 2)
    cur = sql.one("SELECT * FROM sn_rule_version WHERE rule_id = %s", rid)
    base = m(rule_name="x", prefix=cur["prefix"], base=16, seq_len=5)
    assert api.put(f"/api/rules/{rid}", base, w.admin).code == "RULE_USED_FORMAT"
    r = ok(api.put(f"/api/rules/{rid}", {**base, "prefix": P()}, w.admin))
    assert r["action"] == "NEW_VERSION" and r["rule"]["rule"]["current_version"] == 2
    assert (
        ok(api.put(f"/api/rules/{rid}", {**base, "prefix": r["rule"]["current"]["prefix"], "rule_name": "y"}, w.admin))[
            "action"
        ]
        == "RENAMED"
    )
    assert api.delete(f"/api/rules/{rid}", w.admin).code == "RULE_IN_USE"


def test_other_pi_must_change_prefix(w, sql):
    org, p1, b1 = mk(w, ("M1", 10))
    _, p2, b2 = mk(w, ("M1", 10), org=org)
    rid = w.rule("HX" + uid("").replace("-", "")[-4:], seq_len=6)
    ok(w.bind_pi(p1, rid))
    w.generate(p1, 1)
    r = w.bind_pi(p2, rid)
    assert r.code == "RULE_PREFIX_CHANGE_REQUIRED"
    newp = "HY" + uid("").replace("-", "")[-4:]
    ok(w.bind_pi(p2, rid, newp))
    w.generate(p2, 1)
    assert sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s", p2).startswith(newp)
    v = sql.one("SELECT * FROM sn_rule_version WHERE prefix = %s", newp)
    assert v["claimed_pi"] == p2 and v["derived_from"] is not None


def test_lines_with_different_rules_share_one_serial(w, sql):
    org, pi, bill = mk(w, ("M1", 100), ("M2", 50))
    ra, rb = w.rule(P()), w.rule(P())
    ok(w.select(pi, [(bill, 1)], rule_id=ra))
    ok(w.select(pi, [(bill, 2)], rule_id=rb))
    w.generate(pi, 150)
    rows = sql.all(
        "SELECT bill_seq, MIN(seq_dec) a, MAX(seq_dec) b, COUNT(DISTINCT rule_version_id) v FROM sn_item "
        "WHERE pi_no = %s GROUP BY bill_seq ORDER BY bill_seq",
        pi,
    )
    assert [(r["a"], r["b"]) for r in rows] == [(1, 100), (101, 150)]
    assert all(r["v"] == 1 for r in rows)


def test_custom_rule_saved_only_after_success(w, sql):
    org, pi, bill = mk(w, ("9148363", 3, "10.50"))
    p = P()
    ok(w.select(pi, [(bill, 1)], custom=m(prefix=p, base=10, seq_len=6, with_length=True)))
    assert sql.count("SELECT COUNT(*) FROM sn_rule") == 0, "生成前不留规则"
    w.generate(pi)
    r = sql.one("SELECT * FROM sn_rule")
    assert r["rule_name"] == f"{pi}_9148363" and r["rule_code"] == f"PI-{pi}-1"
    assert sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi) == f"{p}10.5000001"
    assert sql.scalar("SELECT rule_id FROM sn_line_rule WHERE bill_no = %s", bill) == r["id"]


def test_customer_template_requires_prefix(w, api):
    org, pi, bill = mk(w, ("M1", 5), customer="C900")
    rid = w.rule(P())
    ok(api.post("/api/rules/customer-binding", m(customer_code="C900", rule_id=rid), w.admin))
    r = api.post("/api/rules/customer-binding", m(customer_code="C900", rule_id=w.rule(P())), w.admin)
    assert r.code == "RULE_CUSTOMER_BOUND"
    p = ok(w.preview(pi))
    assert p["generatable"] is False and p["issues"][0]["issues"] == ["RULE_PREFIX"]
    ok(api.post("/api/rules/customer-template", m(pi_no=pi, new_prefix=P()), w.admin))
    w.generate(pi)


# ---------------------------------------------------------------- 生成


def test_start_after_own_max_ignoring_transferred_in(w, sql):
    org, pi, bill = mk(w, ("M1", 1300))
    rid = w.rule(P())
    ok(w.bind_pi(pi, rid))
    w.generate(pi, 1200)
    j = w.generate(pi, 100)
    assert (j["start_seq_dec"], j["end_seq_dec"]) == (1201, 1300)


def test_preview_lists_reasons(w, api):
    org, pi, bill = mk(w, ("M1", 5, " "), ("M2", 5, "x1"), ("M3", 5, "1", ""))
    rid = w.rule(P())
    ok(w.bind_pi(pi, rid))
    p = ok(w.preview(pi))
    assert p["generatable"] is False
    got = {x["seq"]: x["issues"] for x in p["issues"]}
    assert got == {2: ["LENGTH_INVALID"], 3: ["ATTACH_EMPTY"]}, "长度为空取默认 000，不挡生成"
    ok(api.put("/api/orders/attach", m(bill_no=bill, seq=3, value="A9"), w.admin))
    assert ok(w.preview(pi, lines=[(bill, 3)]))["generatable"] is True, "补填附件项次后可以生成；可缩小范围"
    assert api.put("/api/orders/length", m(bill_no=bill, seq=2, value="x"), w.admin).code == "LENGTH_FILL_INVALID"
    assert api.put("/api/orders/length", m(bill_no=bill, seq=3, value="2"), w.admin).code == "LENGTH_FILL_K3"
    r = ok(api.put("/api/orders/length", m(bill_no=bill, seq=2, value="020.50"), w.admin))
    assert (r["length"], r["length_source"]) == ("20.5", "MANUAL")
    assert ok(w.preview(pi))["generatable"] is True, "补填长度后可以生成"


def test_batch_length_fills_pending_lines_only(w, api, sql):
    org, pi, bill = mk(w, ("M1", 1, " "), ("M2", 1, "x1"), ("M3", 1, "100"), ("M4", 1, ""))
    ok(api.put("/api/orders/length", m(bill_no=bill, seq=4, value="7"), w.admin))
    assert api.put("/api/orders/length/batch", m(pi_no=pi, value="x"), w.admin).code == "LENGTH_FILL_INVALID"
    r = ok(api.put("/api/orders/length/batch", m(pi_no=pi, value="000"), w.admin))
    assert r["filled"] == 2, "只补填待填写的（为空、不是数字），金蝶已有和已补填的不改"
    got = {
        x["seq"]: (x["length"], x["length_source"]) for x in ok(api.get(f"/api/orders/{bill}/lines", w.admin))["lines"]
    }
    assert got == {1: ("000", "MANUAL"), 2: ("000", "MANUAL"), 3: ("100", "K3"), 4: ("7", "MANUAL")}
    r = ok(api.put("/api/orders/length/batch", m(pi_no=pi, lines=[m(bill_no=bill, seq=1)], value="5"), w.admin))
    assert r["filled"] == 0, "按物料行范围；已补填的不改"


def test_empty_length_defaults_to_000(w, sql):
    org, pi, bill = mk(w, ("M1", 1, " "))
    p = P()
    ok(w.bind_pi(pi, w.rule(p)))
    w.generate(pi)
    assert sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s", pi) == f"{p}000000001"


def test_length_normalized_in_sn(w, sql):
    org, pi, bill = mk(w, ("M1", 1, "10.50"))
    p = P()
    ok(w.bind_pi(pi, w.rule(p)))
    w.generate(pi)
    assert sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s", pi) == f"{p}10.5000001"


def test_click_order_and_repeated_material_lines(w, sql):
    org, pi, bill = mk(w, ("9148363", 100), ("9148363", 50), ("9150001", 30))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi, 130, lines=[(bill, 3), (bill, 1)])
    rows = {
        r["bill_seq"]: (r["a"], r["b"])
        for r in sql.all(
            "SELECT bill_seq, MIN(seq_dec) a, MAX(seq_dec) b FROM sn_item WHERE pi_no = %s GROUP BY bill_seq", pi
        )
    }
    assert rows == {3: (1, 30), 1: (31, 130)}, "先点序号 3 再点序号 1"
    c = {r["seq"]: r for r in sql.all("SELECT * FROM sn_line_count WHERE bill_no = %s", bill)}
    assert c[1]["own_generated"] == 100 and 2 not in c


def test_whole_bill_order(w, sql):
    org, pi, bill = mk(w, ("9148363", 100), ("9148363", 50), ("9150001", 30))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi, 180)
    rows = {
        r["bill_seq"]: (r["a"], r["b"])
        for r in sql.all(
            "SELECT bill_seq, MIN(seq_dec) a, MAX(seq_dec) b FROM sn_item WHERE pi_no = %s GROUP BY bill_seq", pi
        )
    }
    assert rows == {1: (1, 100), 2: (101, 150), 3: (151, 180)}


def test_preview_invalidated_by_other_generation(w, api):
    org, pi, bill = mk(w, ("M1", 10))
    ok(w.bind_pi(pi, w.rule(P())))
    p = ok(w.preview(pi, 3))
    w.generate(pi, 2)
    r = api.post("/api/generate/run", m(token=p["token"]), w.admin)
    assert r.code == "GEN_PREVIEW_STALE"


def test_preview_single_use_and_quota(w, api):
    org, pi, bill = mk(w, ("M1", 10))
    ok(w.bind_pi(pi, w.rule(P())))
    assert w.preview(pi, 11).code == "GEN_QUOTA_EXCEEDED"
    p = ok(w.preview(pi, 3))
    ok(api.post("/api/generate/run", m(token=p["token"]), w.admin))
    assert api.post("/api/generate/run", m(token=p["token"]), w.admin).code == "GEN_PREVIEW_USED"


def test_revoke_last_only_and_not_allocated(w, api, sql):
    org, pi, bill = mk(w, ("M1", 10))
    ok(w.bind_pi(pi, w.rule(P())))
    j1 = w.generate(pi, 3)
    j2 = w.generate(pi, 4)
    assert (
        api.post(f"/api/generate/batches/{j1['gen_batch']}/revoke", m(reason="x"), w.admin).code
        == "GEN_REVOKE_NOT_LAST"
    )
    assert api.post(f"/api/generate/batches/{j2['gen_batch']}/revoke", m(), w.admin).code == "REASON_REQUIRED"
    ok(api.post(f"/api/generate/batches/{j2['gen_batch']}/revoke", m(reason="点错了"), w.admin))
    assert sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) == 3
    assert sql.scalar("SELECT own_generated FROM sn_line_count WHERE bill_no = %s", bill) == 3
    j3 = w.generate(pi, 1)
    assert j3["start_seq_dec"] == 4, "撤销后流水退回，号与撤销前相同"
    w.allocate(pi)
    assert (
        api.post(f"/api/generate/batches/{j3['gen_batch']}/revoke", m(reason="x"), w.admin).code
        == "GEN_REVOKE_ALLOCATED"
    )


def test_background_generation_and_cancel(w, api, sql, monkeypatch):
    monkeypatch.setenv("SN_GEN_SYNC_THRESHOLD", "10")
    org, pi, bill = mk(w, ("M1", 5000))
    ok(w.bind_pi(pi, w.rule(P())))
    p = ok(w.preview(pi, 5000))
    j = ok(api.post("/api/generate/run", m(token=p["token"]), w.admin))
    batch = j["gen_batch"]
    import time

    for _ in range(100):
        prog = ok(api.get(f"/api/generate/batches/{batch}", w.admin))
        if prog["status"] != "RUNNING":
            break
        time.sleep(0.1)
    assert prog["status"] == "SUCCESS" and prog["percent"] == 100
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 5000
    assert api.post(f"/api/generate/batches/{batch}/cancel", {}, w.admin).code == "GEN_CANCEL_NOT_RUNNING"


def test_workbench_lists_status_and_buttons(w, api):
    org, pi, bill = mk(w, ("M1", 10))
    r = ok(api.get(f"/api/generate/pis?q={pi}", w.admin))
    assert r["items"][0]["status"] == "NEED_RULE"
    ok(w.bind_pi(pi, w.rule(P())))
    assert ok(api.get(f"/api/generate/pis?q={pi}", w.admin))["items"][0]["status"] == "TO_GENERATE"

    def tab(t: str) -> list[str]:
        return [x["pi_no"] for x in ok(api.get(f"/api/generate/pis?q={pi}&tab={t}", w.admin))["items"]]

    assert (tab("TO_GENERATE"), tab("TO_ALLOCATE"), tab("DONE")) == ([pi], [], [])
    w.generate(pi, 10)
    x = ok(api.get(f"/api/generate/pis?q={pi}", w.admin))["items"][0]
    assert (x["status"], x["pending_alloc"], x["generated_qty"]) == ("TO_ALLOCATE", 10, 10)
    ctx = ok(api.get(f"/api/generate/context?bill_no={bill}", w.admin))
    assert ctx["pi_no"] == pi and ctx["allocation"]["qty"] == 10
    assert ctx["bills"][0]["lines"][0]["rule"]["format"].startswith("P")
    assert (tab("TO_GENERATE"), tab("TO_ALLOCATE"), tab("DONE")) == ([], [pi], [])
    w.allocate(pi)
    assert ok(api.get(f"/api/generate/pis?q={pi}", w.admin))["items"][0]["status"] == "DONE"
    assert (tab("TO_GENERATE"), tab("TO_ALLOCATE"), tab("DONE")) == ([], [], [pi])


def test_generating_pi_listed_first_under_to_generate(w, api, sql):
    _, running_pi, _ = mk(w, ("M1", 10))
    ok(w.bind_pi(running_pi, w.rule(P())))
    w.generate(running_pi, 10)
    _, other, _ = mk(w, ("M1", 10))
    ok(w.bind_pi(other, w.rule(P())))
    # 模拟生成中：额度已用完，仍归「待生成」页签并排在最前
    sql.exec("UPDATE sn_gen_job SET running_pi = pi_no WHERE pi_no = %s", running_pi)
    try:
        items = ok(api.get("/api/generate/pis?tab=TO_GENERATE", w.admin))["items"]
        names = [x["pi_no"] for x in items]
        assert items[0]["status"] == "GENERATING" and running_pi in names and other in names
        assert names.index(running_pi) < names.index(other)
    finally:
        sql.exec("UPDATE sn_gen_job SET running_pi = NULL WHERE pi_no = %s", running_pi)


def test_allocation_skips_reversed_bill(w, api, sql):
    org, pi, b1 = mk(w, ("M1", 2))
    _, _, b2 = mk(w, ("M1", 2), pi=pi, org=org)
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.touch(b2, status="D")
    w.compare()
    r = w.allocate(pi)
    assert r["qty"] == 2 and r["skipped"][0]["bill_no"] == b2 and r["skipped"][0]["reason"] == "REVERSAL"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'TO_ACQUIRE'", b1) == 2
