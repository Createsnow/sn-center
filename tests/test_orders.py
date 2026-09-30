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


def test_pi_list_customer_filter_and_pending_only(api, admin, w):
    """生成页选单：客户 → PI → 单据；pending 只留还有额度或待分配的。"""
    w.factory("F-ORD5", "订单选单厂")
    cust, other = uid("CP"), uid("CQ")
    pi1, pi2, pi3 = uid("PI"), uid("PI"), uid("PI")
    b1, b2, b3, b4 = uid("MO"), uid("MO"), uid("MO"), uid("MO")
    w.order(b1, "订单选单厂", cust, pi1, ("M1", 2))
    w.order(b2, "订单选单厂", cust, pi1, ("M1", 3))
    w.order(b3, "订单选单厂", cust, pi2, ("M1", 1))
    w.order(b4, "订单选单厂", other, pi3, ("M1", 1))
    w.pi_rule(pi2, "S", 10, 6)

    rows = ok(api.get("/api/orders/pis" + q(customer=cust), admin)).body
    assert [r["pi"] for r in rows] == [pi2, pi1], "latest bill first; other customer excluded"
    one = rows[1]
    assert (one["customer_number"], one["bills"], one["total_qty"], one["quota"]) == (cust, 2, 5, 5)
    assert [r["pi"] for r in ok(api.get("/api/orders/pis" + q(q=pi3[-6:]), admin)).body] == [pi3]

    # 客户编码也能搜
    assert ok(api.get("/api/orders" + q(q=cust), admin))["total"] == 3
    assert ok(api.get("/api/orders" + q(customer=cust, pi=pi1), admin))["total"] == 2

    # b3 生成完未分配 → 仍待办；分配后 → 不再出现
    w.generate(b3, 1)
    bill = ok(api.get("/api/orders" + q(pi=pi2, pending=1), admin))["items"]
    assert [(b["bill_no"], b["quota"], b["pending_alloc"]) for b in bill] == [(b3, 0, 1)]
    assert [r["pi"] for r in ok(api.get("/api/orders/pis" + q(customer=cust, pending=1), admin)).body] == [pi2, pi1]
    w.allocate(b3)
    assert ok(api.get("/api/orders" + q(pi=pi2, pending=1), admin))["total"] == 0
    assert ok(api.get("/api/orders" + q(pi=pi2), admin))["total"] == 1, "without pending the bill is still listed"
    assert [r["pi"] for r in ok(api.get("/api/orders/pis" + q(customer=cust, pending=1), admin)).body] == [pi1]


def test_pi_list_respects_bound_query_factory(api, admin, w):
    w.factory("F-ORD6", "订单PI甲厂")
    w.factory("F-ORD7", "订单PI乙厂")
    cust = uid("CR")
    pa, pb = uid("PI"), uid("PI")
    w.order(uid("MO"), "订单PI甲厂", cust, pa, ("M1", 1))
    w.order(uid("MO"), "订单PI乙厂", cust, pb, ("M1", 1))
    qb = w.user(uid("qb"), "query", "F-ORD6")
    assert [r["pi"] for r in ok(api.get("/api/orders/pis" + q(customer=cust), qb)).body] == [pa]
    op = w.user(uid("op"), "factory_operator", "F-ORD6")
    assert api.get("/api/orders/pis", op).code == "FORBIDDEN"


def test_sync_schedule_settings_and_scheduled_run(api, admin, w, sql):
    from app.services import order_sync_schedule

    w.factory("F-ORD8", "定时同步厂")
    bill = uid("MO")
    w.order(bill, "定时同步厂", "C1", uid("PI"), ("M1", 4))
    qa = w.user(uid("qa"), "query", None)
    try:
        # 查询员能看不能改；间隔越界拒绝
        assert ok(api.get("/api/orders/sync-schedule", qa))["min_interval"] == 10
        assert api.put("/api/orders/sync-schedule", m(enabled=True, interval_minutes=60), qa).code == "FORBIDDEN"
        bad = api.put("/api/orders/sync-schedule", m(enabled=True, interval_minutes=5), admin)
        assert bad.code == "ORDER_SYNC_INTERVAL_INVALID"

        s = ok(api.put("/api/orders/sync-schedule", m(enabled=True, interval_minutes=120), admin))
        assert s["enabled"] is True and s["interval_minutes"] == 120 and s["next_run_at"]
        assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'ORDER_SYNC_SCHEDULE'") >= 1
        # 只改开关以外不动：同间隔再保存，下次时间不变
        again = ok(api.put("/api/orders/sync-schedule", m(enabled=True), admin))
        assert again["next_run_at"] == s["next_run_at"]

        assert order_sync_schedule.run_due() is False, "not due yet"
        sql.exec("UPDATE sn_order_sync_schedule SET next_run_at = '2000-01-01 00:00:00' WHERE id = 1")
        assert order_sync_schedule.run_due() is True
        st = ok(api.get("/api/orders/sync-schedule", admin))
        assert st["last_ok"] is True and st["last_bills"] >= 1 and st["last_run_at"]
        assert st["next_run_at"] > st["last_run_at"]
        assert order_sync_schedule.run_due() is False, "claimed run pushes next_run_at forward"
        assert len(ok(api.get(f"/api/orders/{bill}/lines", admin)).body) == 1

        # 金蝶失败：记失败原因，快照不动，上次成功量保留
        K3.handle(lambda call: {"LoginResultType": 0, "Message": "密码错误"} if "LoginByAppSecret" in call.path else [])
        sql.exec("UPDATE sn_order_sync_schedule SET next_run_at = '2000-01-01 00:00:00' WHERE id = 1")
        assert order_sync_schedule.run_due() is True
        st = ok(api.get("/api/orders/sync-schedule", admin))
        assert st["last_ok"] is False and "密码错误" in st["last_message"]
        assert st["last_bills"] >= 1
        assert len(ok(api.get(f"/api/orders/{bill}/lines", admin)).body) == 1

        off = ok(api.put("/api/orders/sync-schedule", m(enabled=False), admin))
        assert off["enabled"] is False and off["next_run_at"] is None
        sql.exec("UPDATE sn_order_sync_schedule SET next_run_at = '2000-01-01 00:00:00' WHERE id = 1")
        assert order_sync_schedule.run_due() is False, "disabled never runs"
    finally:
        install_k3()
        sql.exec("UPDATE sn_order_sync_schedule SET enabled = 0, next_run_at = NULL WHERE id = 1")
