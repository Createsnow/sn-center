"""订单同步进度（只在内存，不加表）：同步进行中可查到方式、阶段、数量（生产订单页统计栏显示），结束后不再显示；
别的实例在同步时只从同步锁看出正在同步。"""

from __future__ import annotations

from conftest import BILLS, K3, K3_FAIL, FakeK3, emp, install_k3, ok, raw_conn, uid

from app.services import locks, orders


def _spy(seen: list) -> None:
    """假金蝶每次查询 / 查看单据时记下当时的同步进度（同步锁由发起同步的请求持有）。"""
    base = K3.handler

    def handler(call):
        if "ExecuteBillQuery" in call.path:
            data = FakeK3.param(call, 0)
            if data.get("FormId") == "PRD_MO":
                seen.append((data["FieldKeys"].split(",")[1], orders.sync_progress()))
        elif "View" in call.path:
            seen.append(("View", orders.sync_progress()))
        return base(call)

    K3.handler = handler


def test_sync_progress_while_running(w, api):
    org = w.org(uid("O")[:10])
    nos = [uid("MO") for _ in range(3)]
    for no in nos:
        BILLS[no] = w.doc(no, org, "C1", "PI-" + no, ("M1", 5))
    op = w.user(emp("P"), "operator", org)
    auditor = w.user(emp("A"), "auditor", org)
    seen: list = []
    try:
        assert ok(api.get("/api/orders/sync-progress", op))["running"] is False
        assert api.get("/api/orders/sync-progress", auditor).code == "FORBIDDEN"

        # 比对同步：先取单据清单，再拉变动单据明细（总数 = 变动单据数）
        _spy(seen)
        w.compare()
        heads = [p for k, p in seen if k == "FModifyDate"]
        details = [p for k, p in seen if k == "FDocumentStatus"]
        assert heads and heads[0]["running"] is True
        assert heads[0]["mode"] == "COMPARE" and heads[0]["trigger"] == "MANUAL" and heads[0]["operator"] == "admin"
        assert heads[0]["stage"] == "HEADS" and heads[0]["elapsed_sec"] >= 0
        assert details and details[0]["stage"] == "DETAILS" and details[0]["total"] == 3 and details[0]["done"] == 0
        assert ok(api.get("/api/orders/sync-progress", op))["running"] is False, "结束后不再显示"

        # 全量重建：总数按当前快照行数估计
        rows_before = orders.db.count("SELECT COUNT(*) FROM sn_prd_mo")
        seen.clear()
        ok(api.post("/api/orders/rebuild", {}, w.admin))
        p = next(p for k, p in seen if k == "FDocumentStatus")
        assert p["mode"] == "REBUILD" and p["stage"] == "DETAILS" and p["total"] == rows_before and p["done"] == 0

        # 按单据同步：显示单号
        seen.clear()
        ok(api.post("/api/orders/sync", {"bill_no": nos[0]}, op))
        p = next(p for k, p in seen if k == "View")
        assert p["mode"] == "BILL" and p["bill_no"] == nos[0] and p["operator"] != "admin"

        # 同步失败也清掉进度
        K3_FAIL["query"] = True
        assert api.post("/api/orders/rebuild", {}, w.admin).code == "K3_QUERY_FAILED"
        assert ok(api.get("/api/orders/sync-progress", op))["running"] is False
    finally:
        install_k3()

    # 别的实例在同步（本进程内存里没有进度）：从同步锁看出正在同步，不显示明细；锁释放后不再显示
    with raw_conn() as c, c.cursor() as cur:
        cur.execute("SELECT GET_LOCK(%s, 0) AS ok", (locks._name("sync", "orders"),))
        assert cur.fetchone()["ok"] == 1
        assert ok(api.get("/api/orders/sync-progress", op)).body == {"running": True, "mode": ""}
        cur.execute("SELECT RELEASE_LOCK(%s)", (locks._name("sync", "orders"),))
    assert ok(api.get("/api/orders/sync-progress", op))["running"] is False
