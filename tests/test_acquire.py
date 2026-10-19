"""页面领取与导出（F-14）。"""

from __future__ import annotations

import threading

from conftest import MES, emp, ok, uid


def P() -> str:
    return "P" + uid("").replace("-", "")


def ready(w, qty, mes=False, batch_size=5000):
    org = w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org, batch_size=batch_size)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", qty))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    return org, pi, bill


def test_request_no_is_idempotent_and_file_downloads(w, api, sql):
    org, pi, bill = ready(w, 5)
    op = w.user(emp("F"), "factory_operator", org)
    a = ok(w.acquire(op, None, pi, "REQ-1"))
    b = ok(w.acquire(op, None, pi, "REQ-1"))
    assert a["batch_no"] == b["batch_no"] and b["replayed"] is True and a["qty"] == 5
    assert sql.count("SELECT COUNT(*) FROM sn_acquire_batch WHERE factory_code = %s", org) == 1
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'ACQUIRED' AND delivery_mode = 'PAGE'", pi
        )
        == 5
    )
    r = api.client.get(
        f"/api/acquire/batches/{a['batch_no']}/file?format=csv", headers={"Authorization": f"Bearer {op}"}
    )
    text = r.content.decode("utf-8-sig")
    assert r.status_code == 200 and text.splitlines()[0].startswith(
        "行号,完整SN,进制流水,十进制流水,PI,单据编号,单据序号"
    )
    assert len(text.splitlines()) == 6


def test_mes_org_only_unbatched_and_not_reassign_waiting(w, api, sql):
    org, pi, bill = ready(w, 13, mes=True, batch_size=5)
    MES.behavior[org] = lambda b, h: (503, {"code": "X"})
    w.push()  # 第 1 批 5 枚未确认，另 8 枚未入批
    op = w.user(emp("F"), "factory_operator", org)
    pre = ok(api.get(f"/api/acquire/preview?pi={pi}", op))
    assert (pre["acquirable"], pre["frozen"]) == (8, 5)
    got = ok(w.acquire(op, None, pi))
    assert got["qty"] == 8
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'TO_PUSH' AND delivery_batch_no IS NOT NULL", pi
        )
        == 5
    )
    # 领走的号 MES 也拉不到
    token, _ = w.mes_account(org)
    r = ok(w.mes_next(token))
    assert r["batch"]["count"] == 5 and r["remaining"] == 0


def test_concurrent_push_and_acquire_do_not_overlap(w, api, sql):
    org, pi, bill = ready(w, 3000, mes=True, batch_size=500)
    op = w.user(emp("F"), "factory_operator", org)
    out = {}

    def take():
        out["r"] = w.acquire(op, None, pi)

    t = threading.Thread(target=take)
    t.start()
    w.push()
    t.join()
    dup = sql.count(
        "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND acquire_batch_no IS NOT NULL "
        "AND delivery_batch_no IS NOT NULL",
        pi,
    )
    assert dup == 0, "同一枚号不会既进推送批次又进领取文件"
    total = sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status IN ('PUSHED','ACQUIRED')", pi)
    assert total == 3000


def test_query_cannot_acquire_and_operator_only_own(w, api):
    org, pi, bill = ready(w, 2)
    other = w.org(uid("O")[:10])
    q = w.user(emp("Q"), "query", None)
    assert w.acquire(q, org, pi).code == "FORBIDDEN"
    op = w.user(emp("F"), "factory_operator", other)
    assert w.acquire(op, org, pi).code == "FACTORY_FORBIDDEN"
    rows = ok(api.get("/api/acquire/pis", op))
    assert all(x["factory_code"] == other for x in rows)
    ok(w.acquire(w.admin, org, pi))


def test_csv_neutralizes_formula_bill_numbers(w, api):
    org = w.org(uid("O")[:10])
    pi, bill = uid("PI"), "=" + uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 1))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    a = ok(w.acquire(w.admin, org, pi))
    r = api.client.get(
        f"/api/acquire/batches/{a['batch_no']}/file?format=csv", headers={"Authorization": f"Bearer {w.admin}"}
    )
    assert ",'=" in r.content.decode("utf-8-sig")
