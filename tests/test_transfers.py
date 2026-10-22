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


def org_body(factory, pi, bill, to_factory, to_pi=None, to_bill=None, scope="BILL", to_material="M1", **kw):
    """转组织：来源 生产组织 + PI + 单据（+ 物料 / 号段）；目标 PI、单据、生产组织、物料都必填（默认同 PI、同单据）。"""
    return m(
        factory_code=factory,
        pi_no=pi,
        bill_no=bill,
        scope=scope,
        to_factory=to_factory,
        to_pi=to_pi or pi,
        to_bill=to_bill or bill,
        to_material=to_material,
        reason="调拨",
        **kw,
    )


def org_direct(w, factory, pi, bill, to_factory, **kw):
    return w.api.post("/api/transfers/org/direct", org_body(factory, pi, bill, to_factory, **kw), w.admin)


def bill_body(pi, bill, to_bill, to_factory, scope="BILL", to_pi=None, to_material="M1", reason="转单", **kw):
    """转单：来源 PI + 单据（+ 物料 / 号段）；目标 PI、单据、生产组织（= 目标单据的）、物料都必填。"""
    return m(
        pi_no=pi,
        bill_no=bill,
        scope=scope,
        to_pi=to_pi or pi,
        to_bill=to_bill,
        to_factory=to_factory,
        to_material=to_material,
        reason=reason,
        **kw,
    )


# ---------------------------------------------------------------- 转组织


def test_org_transfer_pushes_reassign_to_target_and_transfer_out_to_source(w, sql):
    a, pi, bill = setup(w, qty=5)
    b = w.org(uid("O")[:10])
    w.enable_mes(b)
    w.allocate(pi)
    w.push()
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND mes_holder = %s", pi, a) == 5
    t = ok(org_direct(w, a, pi, bill, b))
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
    ok(org_direct(w, b, pi, bill, a))
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
    ok(org_direct(w, a, pi, bill, b))
    w.push()
    xo = sql.one("SELECT * FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT'")
    assert (xo["result"], xo["push_state"]) == ("UNCONFIRMED", "EXHAUSTED")
    r = org_direct(w, b, pi, bill, a)
    assert r.code == "TR_FROZEN" and "转出" in r["message"]
    # 来源 MES 按转出前的 PI 取批能取到转出批次（号已全部转走也不拒绝）
    ta, _ = w.mes_account(a)
    got = ok(w.mes_next(ta, pi=pi))
    assert got["batch"]["batchNo"] == xo["batch_no"] and got["batch"]["batchType"] == "TRANSFER_OUT"
    ok(w.mes_receipt(ta, xo["batch_no"], MES.process(a, got["batch"])))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND mes_holder = %s", pi, a) == 0
    ok(org_direct(w, b, pi, bill, a))


def test_holder_correct_when_target_confirms_first(w, sql):
    a, pi, bill = setup(w, qty=2)
    b, c = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    w.enable_mes(b)
    w.enable_mes(c)
    w.allocate(pi)
    w.push()
    MES.behavior[a] = lambda batch, h: (503, {"code": "X"})
    ok(org_direct(w, a, pi, bill, b))
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
    ok(org_direct(w, b, pi, bill, c))
    xo2 = sql.one("SELECT * FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT' AND factory_code = %s", b)
    assert xo2 is not None and xo2["qty"] == 2, "再从 B 转到 C，转出推给 B"


def test_acquired_same_org_change_pi_stays_acquired(w, sql):
    a, pi, bill = setup(w, qty=4)
    w.allocate(pi)
    w.legacy_acquire(a, pi)
    to_pi, to_bill = uid("PI"), uid("MO")
    w.order(to_bill, a, "C1", to_pi, ("M1", 4, "100"))
    ok(org_direct(w, a, pi, bill, a, to_pi=to_pi, to_bill=to_bill))
    rows = sql.all("SELECT status, pi_no, seq_pi_no FROM sn_item WHERE seq_pi_no = %s", pi)
    assert {(r["status"], r["pi_no"]) for r in rows} == {("ACQUIRED", to_pi)}
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_batch") == 0, "页面领取的号不在 MES 中，不推送"
    q = ok(w.api.get(f"/api/sn?pi={to_pi}", w.admin))["items"]
    assert all(x["transferred_in"] and not x["pi_changed"] for x in q), "号随目标单据改挂，单据 PI 与号 PI 一致"


def test_same_org_reassign_then_other_org_still_sends_transfer_out(w, sql):
    a, pi, bill = setup(w, qty=2)
    b = w.org(uid("O")[:10])
    w.allocate(pi)
    w.push()
    pi2, bill2 = uid("PI"), uid("MO")
    w.order(bill2, a, "C1", pi2, ("M1", 2, "100"))
    ok(org_direct(w, a, pi, bill, a, to_pi=pi2, to_bill=bill2))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE status = 'TO_PUSH' AND push_type = 'REASSIGN'") == 2
    ok(org_direct(w, a, pi2, bill2, b))
    assert (
        sql.count("SELECT COUNT(*) FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT' AND factory_code = %s", a)
        == 1
    )
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE status = 'ALLOCATED' AND factory_code = %s", b) == 2


def test_transfer_out_after_source_disabled_mes(w, sql):
    a, pi, bill = setup(w, qty=2)
    b = w.org(uid("O")[:10])
    w.allocate(pi)
    w.push()
    ok(w.api.post(f"/api/factories/{a}/mes-toggle", m(enabled=False), w.admin))
    ok(org_direct(w, a, pi, bill, b))
    w.push()
    xo = sql.one("SELECT * FROM sn_delivery_batch WHERE batch_type = 'TRANSFER_OUT'")
    assert xo["result"] == "SUCCESS", "关闭对接后转出照常推送到原接口地址"


def test_apply_withdraw_reject_approve(w, api, sql):
    a, pi, bill = setup(w, qty=3, mes=False)
    b = w.org(uid("O")[:10])
    w.allocate(pi)
    op = w.user(emp("F"), "factory_operator", a)
    body = org_body(None, pi, bill, b)
    t = ok(api.post("/api/transfers/org", body, op))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'APPLYING'", pi) == 3
    ok(api.post(f"/api/transfers/org/{t['id']}/withdraw", {}, op))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'ALLOCATED'", pi) == 3
    t2 = ok(api.post("/api/transfers/org", body, op))
    ok(api.post(f"/api/transfers/org/{t2['id']}/reject", m(note="不同意"), w.admin))
    t3 = ok(api.post("/api/transfers/org", body, op))
    lst = ok(api.get("/api/transfers/org?status=PENDING", op))
    assert lst["pending"] == 1 and lst["items"][0]["direction"] == "OUT"
    ok(api.post(f"/api/transfers/org/{t3['id']}/approve", m(), w.admin))
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s AND status = 'ALLOCATED'", pi, b)
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
    assert org_direct(w, a, pi, bill, d).code == "FACTORY_DISABLED"
    assert org_direct(w, a, pi, bill, a).code == "TR_SAME_TARGET"


def test_org_range_transfer_takes_inclusive_serials_still_in_source(w, sql):
    a, pi, bill = setup(w, qty=10)
    b = w.org(uid("O")[:10])
    w.enable_mes(b)
    w.allocate(pi)
    w.push()
    sns = [x["sn"] for x in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    # 先单枚转走号段中间的一枚（只填起始 = 单枚）：之后号段只取仍在来源组织该单据、物料下的号
    ok(org_direct(w, a, pi, bill, b, scope="RANGE", material_code="M1", start_sn=sns[4]))
    w.push()
    rng = {"scope": "RANGE", "material_code": "M1", "start_sn": sns[6], "end_sn": sns[2]}
    c = ok(w.api.post("/api/transfers/org/candidates", org_body(a, pi, bill, b, **rng), w.admin))
    assert c["total"] == 4 and c["transferable"] == 4 and c["xo_qty"] == 4
    t = ok(org_direct(w, a, pi, bill, b, **rng))
    assert (t["scope"], t["from_sn"], t["from_end_sn"], t["qty"]) == ("RANGE", sns[6], sns[2], 4)
    moved = {
        x["sn"] for x in sql.all("SELECT sn FROM sn_transfer_item WHERE kind = 'ORG' AND transfer_id = %s", t["id"])
    }
    assert moved == {sns[2], sns[3], sns[5], sns[6]}, "起止号可倒序；含两端；已转走的不在其中"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s", pi, a) == 5
    w.push()
    assert all(MES.store[a][s]["transferredOut"] for s in moved)
    assert all(MES.store[b][s]["rev"] == 2 for s in moved)


def test_org_range_transfer_validation(w, api, sql):
    a, pi, bill = setup(w, qty=3, mes=False)
    b = w.org(uid("O")[:10])
    w.allocate(pi)
    sns = [x["sn"] for x in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    rng = {"scope": "RANGE", "material_code": "M1"}
    assert org_direct(w, a, pi, bill, b, **rng).code == "TR_SN_REQUIRED"
    assert org_direct(w, a, pi, bill, b, start_sn=sns[0], end_sn="NOPE", **rng).code == "TR_RANGE_INVALID"
    # 起止号须在来源组织该 PI、单据、物料下
    _, pi2, _ = setup(w, qty=1, mes=False, org=a)
    w.allocate(pi2)
    other = sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s", pi2)
    assert org_direct(w, a, pi, bill, b, start_sn=sns[0], end_sn=other, **rng).code == "TR_RANGE_INVALID"
    # 起止号相同 = 一枚；生产组织操作员也可按号段申请
    op = w.user(emp("F"), "factory_operator", a)
    body = org_body(None, pi, bill, b, start_sn=sns[1], end_sn=sns[1], **rng)
    t = ok(api.post("/api/transfers/org", body, op))
    assert t["qty"] == 1 and t["status"] == "PENDING"
    assert sql.scalar("SELECT status FROM sn_item WHERE sn = %s", sns[1]) == "APPLYING"


def test_org_transfer_material_length_check(w, api):
    a, pi, bill = setup(w, qty=1, mes=False)
    w.allocate(pi)
    other = uid("MO")
    w.order(other, a, "C1", pi, ("M2", 1, "200"))
    assert org_direct(w, a, pi, bill, a, to_bill=other, to_material="M2").code == "TR_LENGTH_MISMATCH"
    assert org_direct(w, a, pi, bill, a, to_bill=other, to_material="MX").code == "TR_TARGET_MATERIAL_INVALID"


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
    seg = bill_body(
        pi,
        src,
        a,
        org,
        scope="RANGE",
        material_code="M1",
        start_sn=first[0]["sn"],
        end_sn=first[59]["sn"],
        reason="拆单",
    )
    pre = ok(api.post("/api/transfers/bill/preview", seg, w.admin))
    assert pre["transferable"] == 60
    assert {x["bill_no"]: (x["before"], x["after"]) for x in pre["generated_changes"]} == {a: (0, 60), src: (100, 40)}
    ok(api.post("/api/transfers/bill", seg, w.admin))
    # 按物料编码转移该物料下剩余全部号
    ok(api.post("/api/transfers/bill", bill_body(pi, src, b, org, scope="MATERIAL", material_code="M1"), w.admin))
    gc = {r["bill_no"]: r for r in sql.all("SELECT * FROM sn_line_count")}
    assert (gc[src]["gen_count"], gc[a]["gen_count"], gc[b]["gen_count"]) == (0, 60, 40)
    assert gc[src]["own_generated"] == 100 and gc[a]["own_generated"] == 0
    lines = {x["bill_no"]: x for x in ok(api.get("/api/orders?page_size=100", w.admin))["items"]}
    assert lines[a]["quota"] == 60, "未生成额度不变（新单仍可按订单数量生成）"
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'PENDING_ALLOC' AND rev = 2", a) == 60
    )


def test_material_scope_and_single_sn(w, api, sql):
    """来源 PI → 单据 → 物料：只转该物料编码下的号（同物料的多行一起）；号段只填起始 = 单枚。"""
    org = w.org(uid("O")[:10])
    pi, src, tgt = uid("PI"), uid("MO"), uid("MO")
    w.order(src, org, "C1", pi, ("M1", 3), ("M2", 2), ("M1", 4))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.order(tgt, org, "C1", pi, ("M1", 10), ("M2", 10))
    pre = ok(
        api.post(
            "/api/transfers/bill/preview", bill_body(pi, src, tgt, org, scope="MATERIAL", material_code="M1"), w.admin
        )
    )
    assert pre["total"] == 7 and pre["to_seq"] == 1
    one = sql.scalar("SELECT sn FROM sn_item WHERE bill_no = %s AND material_code = 'M2' ORDER BY seq_dec LIMIT 1", src)
    t = ok(
        api.post(
            "/api/transfers/bill",
            bill_body(pi, src, tgt, org, scope="RANGE", material_code="M2", start_sn=one, to_material="M2"),
            w.admin,
        )
    )
    assert t["qty"] == 1 and t["scope"] == "RANGE" and t["to_seq"] == 2
    assert sql.scalar("SELECT bill_no FROM sn_item WHERE sn = %s", one) == tgt
    # 号段不在所选物料下
    m1 = sql.scalar("SELECT sn FROM sn_item WHERE bill_no = %s AND material_code = 'M1' LIMIT 1", src)
    r = api.post(
        "/api/transfers/bill/preview",
        bill_body(pi, src, tgt, org, scope="RANGE", material_code="M2", start_sn=m1, to_material="M2"),
        w.admin,
    )
    assert r.code == "TR_RANGE_INVALID"
    # 整张单据
    ok(api.post("/api/transfers/bill", bill_body(pi, src, tgt, org, scope="BILL", to_material="M1"), w.admin))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s", src) == 0


def test_target_fields_required_and_checked(w, api):
    org, pi, src = setup(w, qty=2, mes=False)
    other = w.org(uid("O")[:10])
    tgt, dup = uid("MO"), uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 2, "100"))
    w.order(dup, org, "C1", pi, ("M1", 1, "100"), ("M1", 1, "100"))

    def post(**kw):
        return api.post("/api/transfers/bill/preview", {**bill_body(pi, src, tgt, org), **kw}, w.admin)

    assert post(to_bill=None).code == "TR_TO_BILL_REQUIRED"
    assert post(to_factory=None).code == "TR_TO_FACTORY_REQUIRED"
    assert post(to_material=None).code == "TR_TO_MATERIAL_REQUIRED"
    assert post(to_pi=uid("PI")).code == "TR_TARGET_PI_MISMATCH"
    assert post(to_factory=other).code == "TR_TARGET_ORG_MISMATCH", "转单的生产组织 = 目标单据的生产组织"
    assert post(to_bill=src).code == "TR_TARGET_BILL_INVALID", "转单的目标不能是来源单据"
    assert post(bill_no=None).code == "ORDER_BILL_REQUIRED"
    assert post(scope="PI").code == "TR_SCOPE_INVALID"
    assert post(scope="MATERIAL").code == "TR_MATERIAL_REQUIRED"
    assert post(scope="RANGE", material_code="M1").code == "TR_SN_REQUIRED"
    assert post(to_bill=dup).code == "TR_TARGET_LINE_REQUIRED"
    ok(post(to_bill=dup, to_seq=2))


def test_org_transfer_to_other_bill_moves_counts(w, api, sql):
    a, pi, bill = setup(w, qty=3, mes=False)
    b = w.org(uid("O")[:10])
    w.allocate(pi)
    tgt = uid("MO")
    w.order(tgt, b, "C9", pi, ("M1", 3, "100"))
    ok(org_direct(w, a, pi, bill, b, to_bill=tgt))
    rows = sql.all("SELECT factory_code, bill_no, customer_code, status FROM sn_item WHERE pi_no = %s", pi)
    assert {tuple(r.values()) for r in rows} == {(b, tgt, "C9", "ALLOCATED")}
    gc = {r["bill_no"]: r for r in sql.all("SELECT * FROM sn_line_count WHERE bill_no IN (%s, %s)", bill, tgt)}
    assert (gc[bill]["gen_count"], gc[tgt]["gen_count"], gc[tgt]["alloc_count"]) == (0, 3, 3)


def test_sources_lists_bills_and_materials(w, api):
    a, pi, bill = setup(w, qty=3, mes=False)
    w.allocate(pi)
    r = ok(api.get(f"/api/transfers/sources?kind=ORG&pi={pi}&factory_code={a}", w.admin))
    assert r["bills"] == [{"bill_no": bill, "qty": 3, "materials": [{"material_code": "M1", "qty": 3}]}]
    op = w.user(emp("F"), "factory_operator", w.org(uid("O")[:10]))
    assert ok(api.get(f"/api/transfers/sources?kind=ORG&pi={pi}", op))["bills"] == [], "操作员只看本组织"
    assert api.get(f"/api/transfers/sources?kind=BILL&pi={pi}", op).code == "FORBIDDEN"
    assert ok(api.get(f"/api/orders/targets?pi={pi}", op))[0]["bill_no"] == bill, "操作员申请转组织也能选目标"


def test_same_org_bill_transfer_reassigns_pushed(w, api, sql):
    org, pi, src = setup(w, qty=3)
    w.allocate(pi)
    w.push()
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 3))
    ok(api.post("/api/transfers/bill", bill_body(pi, src, tgt, org, reason="整单作废"), w.admin))
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
    assert api.post("/api/transfers/bill", bill_body(pi, src, t1, org), w.admin).code == "TR_LENGTH_MISMATCH"
    w.order(t2, other, "C1", pi, ("M1", 2, "100"))
    ok(api.post("/api/transfers/bill", bill_body(pi, src, t2, other), w.admin))
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
    ok(api.post("/api/transfers/bill", bill_body(pi, src, tgt, org, reason="作废转单"), w.admin))
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND suspend IS NULL AND status = 'ALLOCATED'", tgt)
        == 2
    )
    op = w.user(emp("F"), "factory_operator", org)
    rows = ok(api.get(f"/api/export/bills?q={tgt}", op))["items"]
    assert [(r["bill_no"], r["qty"], r["suspended"]) for r in rows] == [(tgt, 2, 0)]


def test_bill_list_and_items(w, api):
    org, pi, src = setup(w, qty=1, mes=False)
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 1))
    t = ok(api.post("/api/transfers/bill", bill_body(pi, src, tgt, org), w.admin))
    lst = ok(api.get(f"/api/transfers/bill?bill_no={src}", w.admin))["items"]
    assert lst[0]["transfer_no"] == t["transfer_no"] and lst[0]["mode"] == "VOID"
    items = ok(api.get(f"/api/transfers/bill/{t['id']}/items", w.admin))["items"]
    assert items[0]["from_bill"] == src and items[0]["to_bill"] == tgt and items[0]["to_rev"] == 2
    assert BILLS[tgt]["BillNo"] == tgt


def test_bill_transfer_view_respects_read_scope(w, api):
    org, pi, src = setup(w, qty=1, mes=False)
    w.allocate(pi)
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 1))
    t = ok(api.post("/api/transfers/bill", bill_body(pi, src, tgt, org), w.admin))
    own = w.user(emp("F"), "factory_operator", org)
    other = w.user(emp("F"), "factory_operator", w.org(uid("O")[:10]))
    assert ok(api.get(f"/api/transfers/bill/{t['id']}/items", own))["total"] == 1
    assert api.get(f"/api/transfers/bill/{t['id']}", other).code == "FACTORY_FORBIDDEN"
    assert api.get(f"/api/transfers/bill/{t['id']}/items", other).code == "FACTORY_FORBIDDEN"


def test_all_list_merges_bill_and_org(w, api):
    """记录页：转单与转组织合在一张表，新的在前；按类型、状态筛选；转单记已完成。"""
    org, pi, src = setup(w, qty=2, mes=False)
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 2))
    w.allocate(pi)
    bt = ok(api.post("/api/transfers/bill", bill_body(pi, src, tgt, org), w.admin))
    op = w.user(emp("F"), "factory_operator", org)
    ot = ok(api.post("/api/transfers/org", org_body(None, pi, tgt, w.org(uid("O")[:10])), op))

    lst = ok(api.get(f"/api/transfers?pi={pi}", w.admin))
    assert [(x["kind"], x["transfer_no"]) for x in lst["items"]] == [
        ("ORG", ot["transfer_no"]),
        ("BILL", bt["transfer_no"]),
    ]
    assert lst["total"] == 2 and lst["pending"] >= 1
    assert lst["items"][1]["status"] == "APPROVED" and lst["items"][1]["to_bill"] == tgt
    assert [x["kind"] for x in ok(api.get(f"/api/transfers?pi={pi}&status=PENDING", w.admin))["items"]] == ["ORG"]
    assert [x["kind"] for x in ok(api.get(f"/api/transfers?pi={pi}&kind=BILL", w.admin))["items"]] == ["BILL"]
    assert ok(api.get(f"/api/transfers?pi={pi}&kind=BILL&status=PENDING", w.admin))["items"] == []
    mine = ok(api.get(f"/api/transfers?pi={pi}", op))["items"]
    assert {x["kind"]: x["direction"] for x in mine} == {"ORG": "OUT", "BILL": ""}


def test_void_transfer_out_rebuilds_and_delivers(w, sql):
    """9.2-15：转出批次作废后按原明细重建新的转出批次并送达；作废的转出批次不能再取回。"""
    a, pi, bill = setup(w, qty=2)
    b = w.org(uid("O")[:10])
    w.enable_mes(b)
    w.allocate(pi)
    w.push()
    MES.behavior[a] = lambda batch, h: (503, {"code": "X"})
    ok(org_direct(w, a, pi, bill, b))
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
