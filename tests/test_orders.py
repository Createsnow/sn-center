"""订单同步（F-05）、订单页（F-07）、订单变更处理（F-06）与单据暂停交付（5.2.10）。"""

from __future__ import annotations

import conftest
from conftest import BILLS, K3_FAIL, MES, m, ok, uid


def P() -> str:
    return "P" + uid("").replace("-", "")


def issued(w, *lines, mes=False, gen=None, org=None, pi=None):
    """一张已出号单据：已生成（gen 给序号时只生成这些物料行）；返回 (org, pi, bill)。"""
    org = org or w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org, batch_size=100)
    pi = pi or uid("PI")
    bill = uid("MO")
    w.order(bill, org, "C1", pi, *(lines or (("M1", 10),)))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi, lines=[(bill, s) for s in gen] if gen else None)
    return org, pi, bill


def alerts(api, admin, bill):
    return ok(api.get(f"/api/orders/alerts?bill_no={bill}", admin))["items"]


# ---------------------------------------------------------------- 同步


def test_compare_sync_adds_replaces_removes(w, api, sql):
    org = w.org(uid("O")[:10])
    a, b, c = uid("MO"), uid("MO"), uid("MO")
    BILLS[a] = w.doc(a, org, "C1", "PI-A", ("M1", 5), ("M2", 6))
    BILLS[b] = w.doc(b, org, "C1", "PI-B", ("M1", 5), status="D")
    r = w.compare()
    assert r["replaced"] >= 1
    assert sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", a) == 2
    assert sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", b) == 0, "单据状态 D 不进快照"
    # 修改时间不同 → 整单替换，金蝶删掉的物料行随之消失
    BILLS[a]["TreeEntity"].pop()
    w.touch(a)
    w.compare()
    assert sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", a) == 1
    BILLS[c] = w.doc(c, org, "C1", "PI-C", ("M1", 5))
    w.compare()
    del BILLS[a]
    w.compare()
    assert sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", a) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", c) == 1


def test_compare_failure_keeps_snapshot(w, api, sql):
    org = w.org(uid("O")[:10])
    a = uid("MO")
    w.order(a, org, "C1", "PI-K", ("M1", 5))
    K3_FAIL["query"] = True
    del BILLS[a]
    r = api.post("/api/orders/compare", {}, w.admin)
    assert r.code == "K3_QUERY_FAILED"
    assert sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", a) == 1
    meta = ok(api.get("/api/orders/meta", w.admin))
    assert meta["compare"]["last_ok"] is False and meta["compare"]["last_message"]


def test_blank_pi_visible_but_not_in_generate_list(w, api):
    org = w.org(uid("O")[:10])
    a = uid("MO")
    w.order(a, org, "C1", " ", ("M1", 5))
    lst = ok(api.get(f"/api/orders?q={a}", w.admin))["items"]
    assert lst[0]["pi"] == ""
    assert ok(api.get(f"/api/generate/pis?q={a}", w.admin))["items"] == []


def test_empty_customer_flows_like_normal_bills(w, api, sql):
    org = w.org(uid("O")[:10])
    pi, a, tgt = uid("PI"), uid("MO"), uid("MO")
    w.order(a, org, "", pi, ("M1", 5))
    lst = ok(api.get(f"/api/orders?q={a}", w.admin))["items"]
    assert [x["bill_no"] for x in lst] == [a] and lst[0]["customer_code"] == ""
    assert ok(api.get("/api/orders/meta", w.admin))["empty_customer_bills"] >= 1
    assert [x["pi_no"] for x in ok(api.get(f"/api/generate/pis?q={pi}", w.admin))["items"]] == [pi]
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND customer_code = ''", a) == 5
    # 客户编码为空的单据也能作转单目标
    w.order(tgt, org, "", pi, ("M1", 5))
    body = m(
        pi_no=pi, bill_no=a, scope="BILL", to_pi=pi, to_bill=tgt, to_factory=org, to_material="M1", reason="整单作废"
    )
    ok(api.post("/api/transfers/bill", body, w.admin))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s", tgt) == 5


def test_bill_sync_reports_status(w, api, sql):
    org = w.org(uid("O")[:10])
    a = uid("MO")
    w.order(a, org, "C1", "PI-S", ("M1", 5))
    w.touch(a, status="D")
    r = ok(api.post("/api/orders/sync", m(bill_no=a), w.admin))
    assert (r["outcome"], r["doc_status"]) == ("NOT_APPROVED", "D")
    assert sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", a) == 0
    del BILLS[a]
    assert ok(api.post("/api/orders/sync", m(bill_no=a), w.admin))["outcome"] == "NOT_FOUND"


def test_rebuild_failure_keeps_old_snapshot(w, api, sql):
    org = w.org(uid("O")[:10])
    a = uid("MO")
    w.order(a, org, "C1", "PI-R", ("M1", 5))
    K3_FAIL["query"] = True
    assert api.post("/api/orders/rebuild", {}, w.admin).code == "K3_QUERY_FAILED"
    assert sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", a) == 1
    K3_FAIL["query"] = False
    r = ok(api.post("/api/orders/rebuild", {}, w.admin))
    assert r["bills"] >= 1 and sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", a) == 1


def test_attach_fill_and_k3_value_later(w, api, sql):
    org = w.org(uid("O")[:10])
    a, pi = uid("MO"), uid("PI")
    w.order(a, org, "C1", pi, ("M1", 5, "100", " "))
    lines = ok(api.get(f"/api/orders/{a}/lines", w.admin))["lines"]
    assert lines[0]["attach_source"] == "EMPTY"
    assert api.put("/api/orders/attach", m(bill_no=a, seq=1, value=" "), w.admin).code == "ATTACH_INVALID"
    r = ok(api.put("/api/orders/attach", m(bill_no=a, seq=1, value="X"), w.admin))
    assert (r["attach_no"], r["attach_source"]) == ("X", "MANUAL")
    assert ok(api.get(f"/api/orders/{a}/lines", w.admin))["lines"][0]["attach_source"] == "MANUAL"
    BILLS[a]["TreeEntity"][0]["FTERM"] = "Y"
    w.touch(a)
    r = w.compare()
    assert r["attach_k3_filled"] == 1
    line = ok(api.get(f"/api/orders/{a}/lines", w.admin))["lines"][0]
    assert (line["attach_no"], line["attach_manual"], line["attach_k3_conflict"]) == ("Y", "X", True)
    al = [x for x in alerts(api, w.admin, a) if x["alert_type"] == "ATTACH"]
    assert al and al[0]["actions"] == ["ACK"]
    ok(api.post(f"/api/orders/alerts/{al[0]['id']}/handle", m(action="ACK"), w.admin))


# ---------------------------------------------------------------- 订单变更


def test_pi_change_freezes_until_confirmed(w, api, sql):
    org, p1, bill = issued(w, ("M1", 4), ("M1", 6), gen=[1])
    p2 = uid("PI")
    w.touch(bill, pi=p2)
    w.compare()
    al = [x for x in alerts(api, w.admin, bill) if x["alert_type"] == "PI"]
    assert al and (al[0]["before_value"], al[0]["after_value"]) == (p1, p2)
    assert sql.scalar("SELECT pi_frozen FROM sn_bill_ledger WHERE bill_no = %s", bill) == 1
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend = 'CHANGE'", bill) == 4
    assert "PI_FROZEN" in ok(w.preview(p2))["issues"][0]["issues"]
    assert w.api.post("/api/allocation/allocate", m(pi_no=p1), w.admin).code == "ALLOC_NOTHING"
    ok(api.post(f"/api/orders/alerts/{al[0]['id']}/handle", m(action="CONFIRM"), w.admin))
    assert sql.scalar("SELECT pi_frozen FROM sn_bill_ledger WHERE bill_no = %s", bill) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend IS NOT NULL", bill) == 0
    ok(w.bind_pi(p2, w.rule(P())))
    j = w.generate(p2)
    assert j["pi_no"] == p2 and j["start_seq_dec"] == 1, "之后该单生成的号进新 PI 的流水"


def test_reversal_suspends_undelivered_and_resumes(w, api, sql):
    org, pi, bill = issued(w, ("M1", 6), mes=True)
    w.allocate(pi)
    MES.behavior[org] = lambda b, h: (503, {"code": "X"})
    from app.services import delivery

    # 先建一批（未确认），其余未入批
    ok(api.put(f"/api/factories/{org}/mes-config", m(batch_size=4), w.admin))
    w.push()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND delivery_batch_no IS NOT NULL", bill) == 4
    w.touch(bill, status="D")
    w.compare()
    al = [x for x in alerts(api, w.admin, bill) if x["alert_type"] == "REVERSAL"]
    assert al and al[0]["suspended_qty"] == 2
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend = 'REVERSAL'", bill) == 2
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND delivery_batch_no IS NOT NULL AND suspend IS NULL",
            bill,
        )
        == 4
    )
    # 拉取：已入批的未确认批次照常可取；暂停的号不建新批
    token, _ = w.mes_account(org)
    r = ok(w.mes_next(token))
    assert len(r["batch"]["items"]) == 4 and r["paused"] == 2
    ok(w.mes_receipt(token, r["batch"]["batchNo"], MES.process(org, r["batch"])))
    r = ok(w.mes_next(token, billNo=bill))
    assert r["batch"] is None and r["paused"] == 2
    # 重新审核、没有变化 → 自动解除，并自动推送
    MES.behavior.pop(org)
    w.touch(bill, status="C")
    w.compare()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend IS NOT NULL", bill) == 0
    assert all(x["status"] == "CLOSED" for x in alerts(api, w.admin, bill) if x["alert_type"] == "REVERSAL") or not [
        x for x in alerts(api, w.admin, bill) if x["alert_type"] == "REVERSAL"
    ]
    assert delivery.task_state(org, pi)["state"] == "QUEUED"
    w.push()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'PUSHED'", bill) == 6


def test_reapproved_with_customer_change_stays_suspended(w, api, sql):
    org, pi, bill = issued(w, ("M1", 3))
    w.touch(bill, status="D")
    w.compare()
    w.touch(bill, status="C", customer="C2")
    w.compare()
    led = sql.one("SELECT * FROM sn_bill_ledger WHERE bill_no = %s", bill)
    assert (led["suspend"], led["leave_state"]) == ("CHANGE", None)
    types = {x["alert_type"] for x in alerts(api, w.admin, bill)}
    assert "CUSTOMER" in types


def test_deleted_bill_lifts_suspension(w, api, sql):
    org, pi, bill = issued(w, ("M1", 3))
    w.touch(bill, status="D")
    w.compare()
    assert sql.scalar("SELECT suspend FROM sn_bill_ledger WHERE bill_no = %s", bill) == "REVERSAL"
    del BILLS[bill]
    w.compare()
    led = sql.one("SELECT * FROM sn_bill_ledger WHERE bill_no = %s", bill)
    assert (led["suspend"], led["leave_state"]) == (None, "DELETED")
    r = w.allocate(pi)
    assert r["qty"] == 3 and r["orgs"] == [org], "已不在快照：按生成时订单生产组织分配"


def test_confirm_void_lifts(w, api, sql):
    org, pi, bill = issued(w, ("M1", 3))
    w.touch(bill, status="D")
    w.compare()
    al = [x for x in alerts(api, w.admin, bill) if x["alert_type"] == "REVERSAL"][0]
    assert al["actions"] == ["VOID"]
    assert api.post(f"/api/orders/alerts/{al['id']}/handle", m(action="VOID"), w.admin).code == "REASON_REQUIRED"
    ok(api.post(f"/api/orders/alerts/{al['id']}/handle", m(action="VOID", note="金蝶已作废"), w.admin))
    led = sql.one("SELECT * FROM sn_bill_ledger WHERE bill_no = %s", bill)
    assert (led["suspend"], led["leave_state"]) == (None, "VOID_CONFIRMED")
    assert w.allocate(pi)["qty"] == 3


def test_material_lost_map_moves_counts_and_items(w, api, sql):
    org, pi, bill = issued(w, ("M1", 5), ("M2", 5))
    BILLS[bill]["TreeEntity"][0]["MaterialId"] = {"Number": "M9", "Name": "x"}
    w.touch(bill)
    w.compare()
    al = [x for x in alerts(api, w.admin, bill) if x["alert_type"] == "MATERIAL"][0]
    assert (al["seq"], al["before_value"], al["after_value"]) == (1, "M1", "M9")
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend = 'CHANGE'", bill) == 10
    # 「确认对应」到同单另一行：失去对应行的计数并入，号改挂（不是转单）
    BILLS[bill]["TreeEntity"].append(
        {"Seq": 3, "MaterialId": {"Number": "M9", "Name": "x"}, "Qty": 5.0, "Flength": "100", "FTERM": "1"}
    )
    w.touch(bill)
    w.compare()
    assert ok(w.preview(pi))["issues"][0]["issues"] == ["MATERIAL_LOST"], "新出现的物料行暂不能生成"
    ok(api.post(f"/api/orders/alerts/{al['id']}/handle", m(action="MAP", target_seq=3), w.admin))
    c3 = sql.one("SELECT * FROM sn_line_count WHERE bill_no = %s AND seq = 3", bill)
    assert (c3["own_generated"], c3["gen_count"]) == (5, 5)
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND bill_seq = 3 AND material_code = 'M9'", bill)
        == 5
    )
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend IS NOT NULL", bill) == 0


def test_material_map_reassigns_delivered_items(w, api, sql):
    """已交付给本组织 MES 的号：确认对应后 rev + 1、改为待推送改挂并自动重推；未交付的只改挂。"""
    org, pi, bill = issued(w, ("M1", 4), ("M2", 3), mes=True)
    w.allocate(pi, lines=[(bill, 1)])
    w.push()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'PUSHED' AND rev = 1", bill) == 4
    BILLS[bill]["TreeEntity"][0]["MaterialId"] = {"Number": "M9", "Name": "x"}
    BILLS[bill]["TreeEntity"].append(
        {"Seq": 3, "MaterialId": {"Number": "M9", "Name": "x"}, "Qty": 4.0, "Flength": "100", "FTERM": "1"}
    )
    w.touch(bill)
    w.compare()
    al = [x for x in alerts(api, w.admin, bill) if x["alert_type"] == "MATERIAL"][0]
    ok(api.post(f"/api/orders/alerts/{al['id']}/handle", m(action="MAP", target_seq=3), w.admin))
    rows = sql.all(
        "SELECT status, push_type, rev, delivery_batch_no FROM sn_item WHERE bill_no = %s AND bill_seq = 3", bill
    )
    assert len(rows) == 4
    assert {(r["status"], r["push_type"], r["rev"]) for r in rows} == {("TO_PUSH", "REASSIGN", 2)}
    # 确认对应时当场建改挂（REMAP）批次，不回号池
    nos = {r["delivery_batch_no"] for r in rows}
    assert len(nos) == 1 and None not in nos
    assert sql.scalar("SELECT batch_type FROM sn_delivery_batch WHERE batch_no = %s", nos.pop()) == "REMAP"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND bill_seq = 2 AND rev = 1", bill) == 3
    w.push()
    got = MES.received[-1]["batch"]
    item = got["items"][0]
    assert (got["batchType"], item["rev"], item["billSeq"], item["materialCode"]) == ("REMAP", 2, 3, "M9")
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'PUSHED' AND rev = 2", bill) == 4


def test_qty_below_generated_only_reminds(w, api, sql):
    org, pi, bill = issued(w, ("M1", 8))
    BILLS[bill]["TreeEntity"][0]["Qty"] = 5.0
    w.touch(bill)
    w.compare()
    al = [x for x in alerts(api, w.admin, bill) if x["alert_type"] == "QTY"]
    assert al and "多出 3" in al[0]["after_value"]
    assert sql.scalar("SELECT suspend FROM sn_bill_ledger WHERE bill_no = %s", bill) is None
    lines = ok(api.get(f"/api/orders/{bill}/lines", w.admin))["lines"]
    assert lines[0]["quota"] == 0


def test_query_user_sees_only_bound_org(w, api):
    org_a, org_b = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    a, b = uid("MO"), uid("MO")
    w.order(a, org_a, "C1", "PI-QA", ("M1", 1))
    w.order(b, org_b, "C1", "PI-QB", ("M1", 1))
    tok = w.user(conftest.emp("Q"), "query", None, [org_a])
    got = {x["bill_no"] for x in ok(api.get("/api/orders?page_size=500", tok))["items"]}
    assert a in got and b not in got
    assert api.get(f"/api/orders/{b}/lines", tok).code == "FACTORY_FORBIDDEN"
