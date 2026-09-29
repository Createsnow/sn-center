"""生产订单快照：按物料行、同单同物料多行不合并、只留计划 / 计划确认、客户为空不显示、单张替换、失败不动。
（对应 OrderSnapshotIT）"""

from conftest import K3, install_k3, m, ok, q, uid


def test_bill_level_totals_and_lines(api, admin, w):
    w.factory("F-ORD", "订单测试厂")
    bill, pi = uid("MO"), uid("PI")
    w.order(bill, "订单测试厂", "C100", pi, ("M1", 10), ("M1", 5), ("", 3), ("M9", 99, "4"))
    row = ok(api.get("/api/orders" + q(q=bill), admin))["items"][0]
    assert row["total_qty"] == 18, "sum of line qty incl. empty material, excl. status 4"
    assert row["line_count"] == 3
    assert row["factory_code"] == "F-ORD"
    assert row["quota"] == 18
    lines = ok(api.get(f"/api/orders/{bill}/lines", admin)).body
    assert len(lines) == 3
    assert lines[1]["material_number"] == "M1", "same material kept as separate lines"
    assert lines[2]["material_number"] == ""


def test_empty_customer_hidden_and_cannot_generate(api, admin, w):
    w.factory("F-ORD", "订单测试厂")
    bill = uid("MO")
    w.order(bill, "订单测试厂", "", uid("PI"), ("M1", 10))
    assert ok(api.get("/api/orders" + q(q=bill), admin))["total"] == 0
    assert w.preview(bill, 1).code == "ORDER_NO_CUSTOMER"


def test_single_bill_sync_replaces_and_removes_released_order(api, admin, w):
    w.factory("F-ORD", "订单测试厂")
    bill, pi = uid("MO"), uid("PI")
    w.order(bill, "订单测试厂", "C1", pi, ("M1", 10))
    w.order(bill, "订单测试厂", "C1", pi, ("M1", 7), ("M2", 1))
    assert len(ok(api.get(f"/api/orders/{bill}/lines", admin)).body) == 2
    # 订单下达：金蝶里不再是计划 / 计划确认 → 从快照消失
    w.order(bill, "订单测试厂", "C1", pi, ("M1", 7, "4"))
    assert len(ok(api.get(f"/api/orders/{bill}/lines", admin)).body) == 0
    assert w.preview(bill, 1).code == "ORDER_NOT_FOUND"


def test_full_sync_failure_keeps_snapshot(api, admin, w):
    w.factory("F-ORD", "订单测试厂")
    bill = uid("MO")
    w.order(bill, "订单测试厂", "C1", uid("PI"), ("M1", 4))
    K3.handle(lambda call: {"LoginResultType": 0, "Message": "密码错误"} if "LoginByAppSecret" in call.path else [])
    try:
        r = api.post("/api/orders/sync", None, admin)
        assert r.code == "K3_LOGIN_FAILED"
        assert len(ok(api.get(f"/api/orders/{bill}/lines", admin)).body) == 1
    finally:
        install_k3()
    full = ok(api.post("/api/orders/sync", m(), admin))
    assert full.text("mode") == "all"
    assert full["bills"] >= 1
    assert len(ok(api.get(f"/api/orders/{bill}/lines", admin)).body) == 1


def test_factory_operator_cannot_see_orders_but_bound_query_only_sees_own_factory(api, admin, w):
    w.factory("F-ORD", "订单测试厂")
    w.factory("F-ORD2", "订单测试二厂")
    b1, b2 = uid("MO"), uid("MO")
    w.order(b1, "订单测试厂", "C1", uid("PI"), ("M1", 4))
    w.order(b2, "订单测试二厂", "C1", uid("PI"), ("M1", 4))
    op = w.user(uid("op"), "factory_operator", "F-ORD")
    assert api.get("/api/orders", op).code == "FORBIDDEN"
    qb = w.user(uid("qb"), "query", "F-ORD")
    assert ok(api.get("/api/orders" + q(q=b1), qb))["total"] == 1
    assert ok(api.get("/api/orders" + q(q=b2), qb))["total"] == 0


def test_bound_query_cannot_read_other_factory_lines_or_customers(api, admin, w):
    w.factory("F-ORD3", "订单范围甲厂")
    w.factory("F-ORD4", "订单范围乙厂")
    own_c, other_c = uid("CO"), uid("CX")
    b1, b2 = uid("MO"), uid("MO")
    w.order(b1, "订单范围甲厂", own_c, uid("PI"), ("M1", 2))
    w.order(b2, "订单范围乙厂", other_c, uid("PI"), ("M1", 3))
    qb = w.user(uid("qb"), "query", "F-ORD3")
    assert len(ok(api.get(f"/api/orders/{b1}/lines", qb)).body) == 1
    assert api.get(f"/api/orders/{b2}/lines", qb).code == "FACTORY_FORBIDDEN"
    assert ok(api.get("/api/orders/customers" + q(q=own_c), qb)).body == [own_c]
    assert ok(api.get("/api/orders/customers" + q(q=other_c), qb)).body == []
    # 总部与未绑厂查询员仍可看全部
    assert len(ok(api.get(f"/api/orders/{b2}/lines", admin)).body) == 1
    assert ok(api.get("/api/orders/customers" + q(q=other_c), admin)).body == [other_c]
    qa = w.user(uid("qa"), "query", None)
    assert len(ok(api.get(f"/api/orders/{b2}/lines", qa)).body) == 1
