"""SN 导出（F-14）：已分配的号按「生产组织 + PI + 单据」查看与下载；只读，不改状态。"""

from __future__ import annotations

from conftest import MES, emp, m, ok, uid


def P() -> str:
    return "P" + uid("").replace("-", "")


def ready(w, qty, mes=False, batch_size=5000, allocate=True):
    org = w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org, batch_size=batch_size)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", qty))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    if allocate:
        w.allocate(pi)
    return org, pi, bill


def get_file(api, token, **params):
    q = "&".join(f"{k}={v}" for k, v in params.items())
    return api.client.get(f"/api/export/file?{q}", headers={"Authorization": f"Bearer {token}"})


def test_export_is_read_only_and_repeatable(w, api, sql):
    org, pi, bill = ready(w, 5)
    op = w.user(emp("F"), "factory_operator", org)
    rows = ok(api.get(f"/api/export/bills?q={pi}", op))["items"]
    assert [(r["pi_no"], r["bill_no"], r["qty"], r["statuses"], r["mes_enabled"]) for r in rows] == [
        (pi, bill, 5, {"TO_ACQUIRE": 5}, False)
    ]
    for _ in range(2):
        r = get_file(api, op, pi=pi, bill_no=bill, factory_code=org, format="csv")
        lines = r.content.decode("utf-8-sig").splitlines()
        assert r.status_code == 200 and lines[0] == "PI,单据,物料编码,SN" and len(lines) == 6
        assert lines[1].startswith(f"{pi},{bill},M1,")
    sns = [x.split(",")[3] for x in lines[1:]]
    assert sns == [
        r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY bill_seq, seq_pi_no, seq_dec", pi)
    ]
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'TO_ACQUIRE'", pi) == 5, "不改状态"
    assert sql.count("SELECT COUNT(*) FROM sn_acquire_batch WHERE factory_code = %s", org) == 0, "不建领取批次"
    x = get_file(api, op, pi=pi, bill_no=bill, format="xlsx")
    assert x.status_code == 200 and x.content[:2] == b"PK"


def test_mes_org_lists_every_allocated_sn_and_push_unaffected(w, api, sql):
    org, pi, bill = ready(w, 13, mes=True, batch_size=5)
    row = ok(api.get(f"/api/export/bills?q={bill}", w.admin))["items"][0]
    assert (row["qty"], row["statuses"], row["mes_enabled"]) == (13, {"TO_PUSH": 13}, True)
    r = get_file(api, w.admin, pi=pi, bill_no=bill, format="csv")
    assert len(r.content.decode("utf-8-sig").splitlines()) == 14
    w.push()  # 导出后照常推送
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 13
    assert len(MES.store[org]) >= 13
    row = ok(api.get(f"/api/export/bills?q={bill}&mes=true", w.admin))["items"][0]
    assert row["statuses"] == {"PUSHED": 13}, "已推送的号照样展示"
    r = get_file(api, w.admin, pi=pi, bill_no=bill, format="csv")
    assert len(r.content.decode("utf-8-sig").splitlines()) == 14
    seg = ok(api.get(f"/api/export/segments?pi={pi}&bill_no={bill}&status=PUSHED", w.admin))
    assert seg["total"] == 1 and seg["items"][0]["qty"] == 13 and seg["items"][0]["start_seq"] == 1
    assert ok(api.get(f"/api/export/segments?pi={pi}&bill_no={bill}&status=TO_PUSH", w.admin))["items"] == []


def test_frozen_batch_sns_are_listed(w, api):
    org, pi, bill = ready(w, 13, mes=True, batch_size=5)
    MES.behavior[org] = lambda b, h: (503, {"code": "X"})
    w.push()  # 第 1 批 5 枚未确认，另 8 枚未入批
    r = get_file(api, w.admin, pi=pi, bill_no=bill, format="csv")
    assert len(r.content.decode("utf-8-sig").splitlines()) == 14, "未确认批次里的号也导出"


def test_unallocated_not_listed_and_scope(w, api):
    org, pi, bill = ready(w, 2, allocate=False)
    assert ok(api.get(f"/api/export/bills?q={pi}", w.admin))["items"] == [], "待分配的号不展示"
    assert get_file(api, w.admin, pi=pi, bill_no=bill).json()["code"] == "EXPORT_NOTHING"
    w.allocate(pi)
    other = w.org(uid("O")[:10])
    op = w.user(emp("F"), "factory_operator", other)
    assert ok(api.get(f"/api/export/bills?q={pi}", op))["items"] == [], "操作员只看本组织"
    assert get_file(api, op, pi=pi, bill_no=bill, factory_code=org).json()["code"] == "FACTORY_FORBIDDEN"
    assert get_file(api, op, pi=pi, bill_no=bill).json()["code"] == "EXPORT_NOTHING"
    q = w.user(emp("Q"), "query", None)
    assert ok(api.get(f"/api/export/bills?q={pi}", q))["items"][0]["qty"] == 2, "查询员可查看、下载"
    assert get_file(api, q, pi=pi, bill_no=bill).status_code == 200
    assert ok(api.get(f"/api/export/bills?q={pi}&mes=true", w.admin))["items"] == []


def test_csv_neutralizes_formula_bill_numbers(w, api):
    org = w.org(uid("O")[:10])
    pi, bill = uid("PI"), "=" + uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 1))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    r = get_file(api, w.admin, pi=pi, bill_no=bill.replace("=", "%3D"), format="csv")
    assert ",'=" in r.content.decode("utf-8-sig")


def test_legacy_batch_still_downloadable_and_incomplete_released(w, api, sql):
    from app.services import acquire

    org, pi, bill = ready(w, 4)
    no = w.legacy_acquire(org, pi)
    r = api.client.get(f"/api/acquire/batches/{no}/file?format=csv", headers={"Authorization": f"Bearer {w.admin}"})
    assert r.status_code == 200 and len(r.content.decode("utf-8-sig").splitlines()) == 5
    assert ok(api.get(f"/api/export/bills?q={pi}", w.admin))["items"][0]["statuses"] == {"ACQUIRED": 4}

    org2, pi2, _ = ready(w, 3)
    sql.exec(
        "INSERT INTO sn_acquire_batch(batch_no, factory_code, pi_no, request_no, mes_org, status, qty, created_by, "
        "created_at) VALUES ('AQ-INC', %s, %s, 'R-INC', 0, 'INCOMPLETE', 0, 'x', NOW())",
        org2,
        pi2,
    )
    sql.exec("UPDATE sn_item SET acquire_batch_no = 'AQ-INC' WHERE pi_no = %s", pi2)
    assert acquire.release_incomplete() == 3
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND acquire_batch_no IS NULL", pi2) == 3
    assert sql.count("SELECT COUNT(*) FROM sn_acquire_batch WHERE batch_no = 'AQ-INC'") == 0


def test_lines_summarize_each_bill_line(w, api):
    org, other = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 4, "120"), ("M2", 3, "080"))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    segs = ok(api.get(f"/api/export/segments?pi={pi}&bill_no={bill}&factory_code={org}", w.admin))["items"]
    moved = segs[0]["start_sn"]
    ok(
        w.api.post(
            "/api/transfers/org/direct",
            m(
                factory_code=org,
                pi_no=pi,
                bill_no=bill,
                scope="RANGE",
                material_code="M1",
                start_sn=moved,
                to_factory=other,
                to_pi=pi,
                to_bill=bill,
                to_material="M1",
                reason="调拨",
            ),
            w.admin,
        )
    )
    got = ok(api.get(f"/api/export/lines?pi={pi}&bill_no={bill}&factory_code={org}", w.admin)).body
    assert [(x["bill_seq"], x["material_code"], x["length"], x["qty"], x["statuses"]) for x in got] == [
        (1, "M1", "120", 3, {"TO_ACQUIRE": 3}),
        (2, "M2", "080", 3, {"TO_ACQUIRE": 3}),
    ]
    q = w.user(emp("Q"), "query", None)
    assert len(ok(api.get(f"/api/export/lines?pi={pi}&bill_no={bill}", q)).body) == 2
    op = w.user(emp("F"), "factory_operator", other)
    assert api.get(f"/api/export/lines?pi={pi}&bill_no={bill}&factory_code={org}", op).code == "FACTORY_FORBIDDEN"
