"""SN 查询（F-18）、只读接口（F-19）、首页看板（F-20）、操作痕迹（F-21）、每日交付自检。"""

from __future__ import annotations

from conftest import MES, emp, m, ok, uid, xlsx_rows

from app.services import sn_sheet


def P() -> str:
    return "P" + uid("").replace("-", "")


def make(w, qty, mes=False, org=None):
    org = org or w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", qty))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    return org, pi, bill


def test_range_query_counts_missing_for_bound_operator(w, api):
    a, pi, bill = make(w, 10)
    w.allocate(pi)
    b = w.org(uid("O")[:10])
    sns = [x["sn"] for x in ok(api.get(f"/api/sn?pi={pi}&page_size=100", w.admin))["items"]]
    for sn in sns[:3]:
        ok(
            w.api.post(
                "/api/transfers/org/direct",
                m(
                    factory_code=a,
                    pi_no=pi,
                    bill_no=bill,
                    scope="RANGE",
                    material_code="M1",
                    start_sn=sn,
                    to_factory=b,
                    to_pi=pi,
                    to_bill=bill,
                    to_material="M1",
                    reason="x",
                ),
                w.admin,
            )
        )
    op = w.user(emp("F"), "factory_operator", a)
    r = ok(api.get(f"/api/sn?pi={pi}&seq_from=1&seq_to=10", op))
    assert r["range"] == {"found": 7, "expected": 10, "missing": 3, "by_status": {"ALLOCATED": 7}}
    full = ok(api.get(f"/api/sn?pi={pi}&seq_from={sns[0]}&seq_to=000010", w.admin))
    assert full["range"]["found"] == 10
    assert api.get("/api/sn?seq_from=1", w.admin).code == "QUERY_RANGE_PI_REQUIRED"


def test_export_truncates_with_notice(w, api, monkeypatch):
    org, pi, bill = make(w, 5)
    w.allocate(pi)
    monkeypatch.setenv("SN_EXPORT_LIMIT", "3")
    c = ok(api.get(f"/api/sn/export-check?pi={pi}", w.admin))
    assert c.body == {"limit": 3, "truncated": True, "rows": 3}
    r = api.client.get(f"/api/sn/export?pi={pi}", headers={"Authorization": f"Bearer {w.admin}"})
    rows = xlsx_rows(r.content)
    assert rows[0] == sn_sheet.HEADERS and len(rows) == 4, "列与 SN 导出、领取文件统一"
    got = dict(zip(rows[0], rows[1], strict=True))
    assert (got["组织编号"], got["生产组织名称"]) == (org, f"组织{org}")


def test_delivered_read_only_for_mes(w, api, sql):
    org, pi, bill = make(w, 3, mes=True)
    w.allocate(pi)
    token, _ = w.mes_account(org)
    assert ok(api.get(f"/api/mes/v1/sn/delivered?pi={pi}", token))["items"] == [], "未推送的不返回"
    w.push()
    r = ok(api.get(f"/api/mes/v1/sn/delivered?pi={pi}&pageSize=2", token))
    assert len(r["items"]) == 2 and r["nextCursor"]
    r2 = ok(api.get(f"/api/mes/v1/sn/delivered?pi={pi}&pageSize=2&cursor={r['nextCursor']}", token))
    assert len(r2["items"]) == 1 and r2["nextCursor"] is None
    assert {x["status"] for x in r["items"] + r2["items"]} == {"PUSHED"}
    q = w.user(emp("Q"), "query", None)
    assert len(ok(api.get(f"/api/mes/v1/sn/delivered?pi={pi}&orgCode={org}", q))["items"]) == 3
    assert api.post("/api/mes/v1/batches/next", m(), q).code == "FORBIDDEN"


def test_rate_limited_pull_leaves_failure_audit(w, api, sql, monkeypatch):
    org = w.org(uid("O")[:10])
    w.enable_mes(org)
    token, e = w.mes_account(org)
    monkeypatch.setenv("SN_MES_PULL_RATE_PER_MINUTE", "1")
    sql.exec("DELETE FROM sn_rate_counter")
    assert w.mes_next(token).status == 200
    r = w.mes_next(token)
    assert (r.status, r.code) == (429, "RATE_LIMITED")
    row = sql.one("SELECT * FROM sn_audit WHERE operator = %s AND result = 'FAIL' ORDER BY id DESC LIMIT 1", e)
    assert row["action"] == "SN_PULL" and "RATE_LIMITED" in row["error_msg"] and row["factory_code"] == org


def test_audit_scope_and_filters(w, api):
    a, pi, bill = make(w, 2)
    w.allocate(pi)
    other = w.org(uid("O")[:10])
    op_a = w.user(emp("F"), "factory_operator", a)
    op_b = w.user(emp("F"), "factory_operator", other)
    rows_a = ok(api.get(f"/api/audits?action=SN_ALLOCATE&pi={pi}", op_a))["items"]
    assert len(rows_a) == 1 and rows_a[0]["factory_code"] == a
    assert ok(api.get(f"/api/audits?action=SN_ALLOCATE&pi={pi}", op_b))["items"] == []
    gen = ok(api.get(f"/api/audits?action=SN_GENERATE&bill_no={bill}", w.admin))["items"]
    assert gen and gen[0]["bill_no"] == bill
    c = ok(api.get("/api/audits/export-check", w.admin))
    assert c["limit"] == 200_000


def test_dashboard_counts_and_today(w, api):
    a, pi, bill = make(w, 4, mes=True)
    w.allocate(pi)
    MES.behavior[a] = lambda b, h: (
        200,
        {
            "schemaVersion": "1.0",
            "batchNo": b["batchNo"],
            "outcome": "REJECTED",
            "rejectCode": "X",
            "rejectMessage": "x",
        },
    )
    w.push()
    d = ok(api.get("/api/dashboard", w.admin))
    assert d["push_interrupted"] >= 1 and d["status_counts"]["TO_PUSH"] >= 4
    assert any(x["factory_code"] == a for x in d["recent_failures"])
    assert d["today"]["SN_GENERATE"] >= 4 and d["today"]["SN_ALLOCATE"] >= 4
    op = w.user(emp("F"), "factory_operator", a)
    d2 = ok(api.get("/api/dashboard", op))
    assert "PENDING_ALLOC" not in d2["status_counts"], "待分配只给总部看"
    assert d2["tz_offset_min"] == 480


def test_self_check_flags_unconfirmed(w, api, sql, monkeypatch):
    from app.services import delivery

    a, pi, bill = make(w, 2, mes=True)
    w.allocate(pi)
    MES.behavior[a] = lambda b, h: (503, {"code": "X"})
    w.push()
    sql.exec("UPDATE sn_delivery_batch SET created_at = DATE_SUB(created_at, INTERVAL 1 DAY)")
    r = delivery.self_check()
    assert r["problems"] >= 1
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_alert WHERE alert_type = 'CHECK'") >= 1
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'SELF_CHECK'") >= 1


def test_times_are_utc_iso(w, api):
    a, pi, bill = make(w, 1)
    item = ok(api.get(f"/api/sn?pi={pi}", w.admin))["items"][0]
    assert item["created_at"].endswith("Z") and "T" in item["created_at"]
