"""转单（F-15）、转组织（F-16）、记录页（F-17）与转移后的 MES 处理（5.2.8）。"""

from __future__ import annotations

from conftest import BILLS, MES, emp, m, ok, uid


def P() -> str:
    return "P" + uid("").replace("-", "")


def setup(w, qty=10, mes=True, org=None, length="100"):
    org = org or w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org, batch_size=1000)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", qty, length))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    return org, pi, bill


def org_direct(w, factory, pi, to_factory, to_pi=None, scope="PI", **kw):
    return w.api.post(
        "/api/transfers/org/direct",
        m(factory_code=factory, pi_no=pi, scope=scope, to_factory=to_factory, to_pi=to_pi or pi, reason="调拨", **kw),
        w.admin,
    )


# ---------------------------------------------------------------- 转组织


def test_org_transfer_pushes_reassign_to_target_and_transfer_out_to_source(w, sql):
    a, pi, bill = setup(w, qty=5)
    b = w.org(uid("O")[:10])
    w.enable_mes(b)
    w.allocate(pi)
    w.push()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND mes_holder = %s", pi, a) == 5
    t = ok(org_direct(w, a, pi, b))
    assert len(t["out_batches"]) == 1
    items = sql.all("SELECT * FROM sn_item WHERE pi_no = %s", pi)
    assert {(x["factory_code"], x["status"], x["push_type"], x["rev"]) for x in items} == {
        (b, "TO_PUSH", "REASSIGN", 2)
    }
    w.push()
    # MES-B 收下改挂（没有该 SN 时按新增入库）；MES-A 收到转出，置「已转出」
    sn = items[0]["sn"]
    assert MES.store[b][sn]["rev"] == 2 and not MES.store[b][sn]["transferredOut"]
    assert MES.store[a][sn]["transferredOut"] and MES.store[a][sn]["toOrgCode"] == b
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND mes_holder = %s AND status = 'PUSHED'", pi, b) == 5
    )
    # 转出后来源 MES 按批次号、按 PI 只读查询都查不到
    ta, _ = w.mes_account(a)
    first = sql.scalar("SELECT from_delivery_batch FROM sn_transfer_item WHERE kind = 'ORG' LIMIT 1")
    assert ok(w.api.get(f"/api/mes/v1/batches/{first}/items", ta))["items"] == []
    assert ok(w.api.get(f"/api/mes/v1/sn/delivered?pi={pi}", ta))["items"] == []
    xo = t["out_batches"][0]["batch_no"]
    assert ok(w.api.get(f"/api/mes/v1/batches/{xo}/items", ta))["items"] == []
    # 转回 A：MES-A 收到更大的 rev，清除「已转出」
    ok(org_direct(w, b, pi, a))
    w.push()
    assert MES.store[a][sn]["rev"] == 3 and not MES.store[a][sn]["transferredOut"]
    assert MES.store[b][sn]["transferredOut"]


def test_transfer_out_pending_blocks_retransfer(w, sql):
    a, pi, bill = setup(w, qty=3)
    b = w.org(uid("O")[:10])
    w.enable_mes(b)
    w.allocate(pi)
    w.push()
    MES.behavior[a] = lambda batch, h: (503, {"code": "X"})
    ok(org_direct(w, a, pi, b))
    w.push()
    xo = sql.one("SELECT * FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT'")
    assert (xo["result"], xo["push_state"]) == ("UNCONFIRMED", "EXHAUSTED")
    r = org_direct(w, b, pi, a)
    assert r.code == "TR_FROZEN" and "转出" in r["message"]
    # 来源 MES 按转出前的 PI 取批能取到转出批次（号已全部转走也不拒绝）
    ta, _ = w.mes_account(a)
    got = ok(w.mes_next(ta, pi=pi))
    assert got["batch"]["batchNo"] == xo["batch_no"] and got["batch"]["batchType"] == "TRANSFER_OUT"
    ok(w.mes_receipt(ta, xo["batch_no"], MES.process(a, got["batch"])))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND mes_holder = %s", pi, a) == 0
    ok(org_direct(w, b, pi, a))


def test_holder_correct_when_target_confirms_first(w, sql):
    a, pi, bill = setup(w, qty=2)
    b, c = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    w.enable_mes(b)
    w.enable_mes(c)
    w.allocate(pi)
    w.push()
    MES.behavior[a] = lambda batch, h: (503, {"code": "X"})
    ok(org_direct(w, a, pi, b))
    w.push()  # B 的交付批次先确认，A 的转出未确认
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND mes_holder = %s", pi, b) == 2
    MES.behavior.pop(a)
    xo = sql.scalar("SELECT batch_no FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT'")
    ok(w.api.post("/api/delivery/resume", m(factory_code=a, key="#XO"), w.admin))
    w.push()
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE batch_no = %s", xo) == "SUCCESS"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND mes_holder = %s", pi, b) == 2, (
        "A 的转出后确认不清 B"
    )
    ok(org_direct(w, b, pi, c))
    xo2 = sql.one("SELECT * FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT' AND factory_code = %s", b)
    assert xo2 is not None and xo2["qty"] == 2, "再从 B 转到 C，转出推给 B"


def test_acquired_same_org_change_pi_stays_acquired(w, sql):
    a, pi, bill = setup(w, qty=4)
    w.allocate(pi)
    w.legacy_acquire(a, pi)
    to_pi = uid("PI")
    ok(org_direct(w, a, pi, a, to_pi=to_pi))
    rows = sql.all("SELECT status, pi_no, seq_pi_no FROM sn_item WHERE seq_pi_no = %s", pi)
    assert {(r["status"], r["pi_no"]) for r in rows} == {("ACQUIRED", to_pi)}
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_batch") == 0, "页面领取的号不在 MES 中，不推送"
    q = ok(w.api.get(f"/api/sn?pi={to_pi}", w.admin))["items"]
    assert all(x["pi_changed"] and x["transferred_in"] for x in q)


def test_same_org_reassign_then_other_org_still_sends_transfer_out(w, sql):
    a, pi, bill = setup(w, qty=2)
    b = w.org(uid("O")[:10])
    w.allocate(pi)
    w.push()
    ok(org_direct(w, a, pi, a, to_pi=uid("PI")))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE status = 'TO_PUSH' AND push_type = 'REASSIGN'") == 2
    pi2 = sql.scalar("SELECT pi_no FROM sn_item LIMIT 1")
    ok(org_direct(w, a, pi2, b))
    assert (
        sql.count("SELECT COUNT(*) FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT' AND factory_code = %s", a)
        == 1
    )
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE status = 'TO_ACQUIRE' AND factory_code = %s", b) == 2


def test_transfer_out_after_source_disabled_mes(w, sql):
    a, pi, bill = setup(w, qty=2)
    b = w.org(uid("O")[:10])
    w.allocate(pi)
    w.push()
    ok(w.api.post(f"/api/factories/{a}/mes-toggle", m(enabled=False), w.admin))
    ok(org_direct(w, a, pi, b))
    w.push()
    xo = sql.one("SELECT * FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT'")
    assert xo["result"] == "SUCCESS", "关闭对接后转出照常推送到原接口地址"


def test_apply_withdraw_reject_approve(w, api, sql):
    a, pi, bill = setup(w, qty=3, mes=False)
    b = w.org(uid("O")[:10])
    w.allocate(pi)
    op = w.user(emp("F"), "factory_operator", a)
    body = m(pi_no=pi, scope="PI", to_factory=b, to_pi=pi, reason="调拨")
    t = ok(api.post("/api/transfers/org", body, op))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'APPLYING'", pi) == 3
    ok(api.post(f"/api/transfers/org/{t['id']}/withdraw", {}, op))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'TO_ACQUIRE'", pi) == 3
    t2 = ok(api.post("/api/transfers/org", body, op))
    ok(api.post(f"/api/transfers/org/{t2['id']}/reject", m(note="不同意"), w.admin))
    t3 = ok(api.post("/api/transfers/org", body, op))
    lst = ok(api.get("/api/transfers/org?status=PENDING", op))
    assert lst["pending"] == 1 and lst["items"][0]["direction"] == "OUT"
    ok(api.post(f"/api/transfers/org/{t3['id']}/approve", m(), w.admin))
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s AND status = 'TO_ACQUIRE'", pi, b
        )
        == 3
    )
    assert sql.count("SELECT COUNT(*) FROM sn_line_count WHERE bill_no = %s AND gen_count = 3", bill) == 1, (
        "转组织计数不变"
    )
    items = ok(api.get(f"/api/transfers/org/{t3['id']}/items", op))["items"]
    assert {x["current_status"] for x in items} == {"TRANSFERRED_OUT"}
    r = w.api.client.get(
        f"/api/transfers/org/{t3['id']}/out-list?format=csv", headers={"Authorization": f"Bearer {op}"}
    )
    assert r.status_code == 200 and r.content.decode("utf-8-sig").count("\n") == 4


def test_org_transfer_rejects_disabled_target_and_same_target(w, api):
    a, pi, bill = setup(w, qty=1, mes=False)
    w.allocate(pi)
    d = w.org(uid("O")[:10])
    w.org(d, enabled=False)
    assert org_direct(w, a, pi, d).code == "FACTORY_DISABLED"
    assert org_direct(w, a, pi, a).code == "TR_SAME_TARGET"


def test_org_transfer_material_length_check(w, api):
    a, pi, bill = setup(w, qty=1, mes=False)
    w.allocate(pi)
    other = uid("MO")
    w.order(other, a, "C1", pi, ("M2", 1, "200"))
    assert org_direct(w, a, pi, a, to_material="M2").code == "TR_LENGTH_MISMATCH"
    ok(org_direct(w, a, pi, a, to_material="MX", manual_fields=["material"]))


# ---------------------------------------------------------------- 转单


def test_split_moves_counts_not_quota(w, api, sql):
    org = w.org(uid("O")[:10])
    pi, src = uid("PI"), uid("MO")
    w.order(src, org, "C1", pi, ("M1", 100))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    a, b = uid("MO"), uid("MO")
    w.order(a, org, "C1", pi, ("M1", 60))
    w.order(b, org, "C1", pi, ("M1", 40))
    first = sql.all("SELECT sn FROM sn_item WHERE bill_no = %s ORDER BY seq_dec", src)
    pre = ok(
        api.post(
            "/api/transfers/bill/preview",
            m(from_bill=src, to_bill=a, mode="SPLIT", from_seq=1, start_sn=first[0]["sn"], end_sn=first[59]["sn"]),
            w.admin,
        )
    )
    assert pre["transferable"] == 60
    ok(
        api.post(
            "/api/transfers/bill",
            m(
                from_bill=src,
                to_bill=a,
                mode="SPLIT",
                from_seq=1,
                start_sn=first[0]["sn"],
                end_sn=first[59]["sn"],
                reason="拆单",
            ),
            w.admin,
        )
    )
    ok(api.post("/api/transfers/bill", m(from_bill=src, to_bill=b, mode="SPLIT", from_seq=1, reason="拆单"), w.admin))
    gc = {r["bill_no"]: r for r in sql.all("SELECT * FROM sn_line_count")}
    assert (gc[src]["gen_count"], gc[a]["gen_count"], gc[b]["gen_count"]) == (0, 60, 40)
    assert gc[src]["own_generated"] == 100 and gc[a]["own_generated"] == 0
    lines = {x["bill_no"]: x for x in ok(api.get("/api/orders?page_size=100", w.admin))["items"]}
    assert lines[a]["quota"] == 60, "未生成额度不变（新单仍可按订单数量生成）"
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'PENDING_ALLOC' AND rev = 2", a) == 60
    )


def test_same_org_bill_transfer_reassigns_pushed(w, api, sql):
    org, pi, src = setup(w, qty=3)
    w.allocate(pi)
    w.push()
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 3))
    ok(api.post("/api/transfers/bill", m(from_bill=src, to_bill=tgt, mode="VOID", reason="整单作废"), w.admin))
    rows = sql.all("SELECT status, push_type, rev, bill_no FROM sn_item WHERE pi_no = %s", pi)
    assert {(r["status"], r["push_type"], r["rev"], r["bill_no"]) for r in rows} == {("TO_PUSH", "REASSIGN", 2, tgt)}
    w.push()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 3
    sn = rows and sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s LIMIT 1", pi)
    assert MES.store[org][sn]["billNo"] == tgt and MES.store[org][sn]["rev"] == 2


def test_bill_transfer_length_mismatch_and_pending_alloc_to_other_org(w, api, sql):
    org, pi, src = setup(w, qty=2, mes=False)
    other = w.org(uid("O")[:10])
    t1, t2 = uid("MO"), uid("MO")
    w.order(t1, org, "C1", pi, ("M1", 2, "200"))
    assert api.post("/api/transfers/bill", m(from_bill=src, to_bill=t1, mode="VOID", reason="x"), w.admin).code == (
        "TR_LENGTH_MISMATCH"
    )
    w.order(t2, other, "C1", pi, ("M1", 2, "100"))
    ok(api.post("/api/transfers/bill", m(from_bill=src, to_bill=t2, mode="VOID", reason="x"), w.admin))
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'PENDING_ALLOC' AND factory_code IS NULL", t2
        )
        == 2
    )
    r = w.allocate(pi)
    assert r["orgs"] == [other], "分配时分到目标单据的生产组织"


def test_bill_transfer_from_reversed_bill_releases(w, api, sql):
    org, pi, src = setup(w, qty=2, mes=False)
    w.allocate(pi)
    w.touch(src, status="D")
    w.compare()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend = 'REVERSAL'", src) == 2
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 2))
    ok(api.post("/api/transfers/bill", m(from_bill=src, to_bill=tgt, mode="VOID", reason="作废转单"), w.admin))
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend IS NULL AND status = 'TO_ACQUIRE'", tgt)
        == 2
    )
    op = w.user(emp("F"), "factory_operator", org)
    rows = ok(api.get(f"/api/export/bills?q={tgt}", op))["items"]
    assert [(r["bill_no"], r["qty"], r["suspended"]) for r in rows] == [(tgt, 2, 0)]


def test_bill_list_and_items(w, api):
    org, pi, src = setup(w, qty=1, mes=False)
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 1))
    t = ok(api.post("/api/transfers/bill", m(from_bill=src, to_bill=tgt, mode="VOID", reason="x"), w.admin))
    lst = ok(api.get(f"/api/transfers/bill?bill_no={src}", w.admin))["items"]
    assert lst[0]["transfer_no"] == t["transfer_no"]
    items = ok(api.get(f"/api/transfers/bill/{t['id']}/items", w.admin))["items"]
    assert items[0]["from_bill"] == src and items[0]["to_bill"] == tgt and items[0]["to_rev"] == 2
    assert BILLS[tgt]["BillNo"] == tgt


def test_void_transfer_out_rebuilds_and_delivers(w, sql):
    """9.2-15：转出批次作废后按原明细重建新的转出批次并送达；作废的转出批次不能再取回。"""
    a, pi, bill = setup(w, qty=2)
    b = w.org(uid("O")[:10])
    w.enable_mes(b)
    w.allocate(pi)
    w.push()
    MES.behavior[a] = lambda batch, h: (503, {"code": "X"})
    ok(org_direct(w, a, pi, b))
    w.push()
    xo = sql.one("SELECT * FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT'")
    assert xo["result"] == "UNCONFIRMED"
    ok(
        w.api.post(
            f"/api/delivery/batches/{xo['batch_no']}/void", m(reason="MES 停机", basis="书面确认未处理"), w.admin
        )
    )
    MES.behavior.pop(a)
    w.push()
    rows = sql.all("SELECT * FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT' ORDER BY id")
    assert [(r["result"], r["qty"]) for r in rows] == [("VOIDED", 2), ("SUCCESS", 2)]
    assert rows[1]["rebuilt_from"] == xo["batch_no"]
    sns = [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s", pi)]
    assert all(MES.store[a][s]["transferredOut"] and MES.store[a][s]["toOrgCode"] == b for s in sns)
    ta, _ = w.mes_account(a)
    assert w.mes_next(ta, batchNo=xo["batch_no"]).code == "BATCH_VOIDED"
