"""工厂停用 / 改名 / 金蝶同步启停；操作痕迹归档后仍可在页面查询，归档表按总保存期清理。"""

import random
from datetime import datetime, timedelta

import conftest
import pytest
from conftest import m, ok, q, uid

ORG = "停用测试厂"
FAC = "F-DIS"
ORG2 = "停用测试二厂"
FAC2 = "F-DIS2"


@pytest.fixture(autouse=True)
def factories(w, api, admin, once):
    if "dis" not in once:
        once["dis"] = True
        w.factory(FAC, ORG)
        w.factory(FAC2, ORG2)
    yield
    # 每个用例结束都恢复启用，互不影响
    for code in (FAC, FAC2):
        api.post(f"/api/factories/{code}/enable", m(), admin)


def _row(api, admin, code):
    return next(f for f in ok(api.get("/api/factories", admin)).body if f["factory_code"] == code)


# ---------------------------------------------------------------- 工厂


def test_disabled_factory_blocks_generate_allocate_transfer_and_binding(api, admin, w, sql):
    pi = uid("PI")
    b1, b2 = pi + "-MO1", pi + "-MO2"
    w.pi_rule(pi, "DS", 10, 5)
    w.order(b1, ORG, "C1", pi, ("A", 3))
    w.order(b2, ORG2, "C1", pi, ("A", 2))
    w.generate(b2, 2)
    ok(api.post(f"/api/factories/{FAC}/disable", m(), admin))
    assert _row(api, admin, FAC)["enabled"] is False

    # 生成：整张 PI 不能生成
    r = api.post("/api/generate/pi-preview", m(pi_no=pi, qty=3), admin)
    assert r.code == "FACTORY_DISABLED"
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin)).body
    assert "FACTORY_DISABLED" in ctx["issues"]
    assert next(b for b in ctx["bills"] if b["bill_no"] == b1)["factory_disabled"] is True
    assert ok(api.get("/api/orders/pis" + q(q=pi), admin)).body[0]["disabled"] is True
    # 转入目标、账户绑定
    tr = api.post(
        "/api/transfers/direct",
        m(factory_code=FAC2, pi_no=pi, scope="PI", to_factory=FAC, to_pi=pi, reason="x"),
        admin,
    )
    assert tr.code == "FACTORY_DISABLED"
    u = api.post(
        "/api/users",
        m(emp_no=uid("E"), name="n", role="factory_operator", factory_code=FAC, password="Pass@1234"),
        admin,
    )
    assert u.code == "FACTORY_DISABLED"
    # 分配：停用的是 FAC2 时，分到 FAC2 的待分配号不能分
    ok(api.post(f"/api/factories/{FAC2}/disable", m(), admin))
    assert api.post("/api/generate/allocate", m(bill_no=b2), admin).code == "FACTORY_DISABLED"
    ok(api.post(f"/api/factories/{FAC2}/enable", m(), admin))
    w.allocate(b2)
    # 已分到停用工厂的号照常领取
    ok(api.post(f"/api/factories/{FAC2}/disable", m(), admin))
    ok(w.acquire(admin, FAC2, pi, uid("REQ")))
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'FACTORY_DISABLE' AND factory_code = %s", FAC) >= 1


def test_account_keeps_its_disabled_factory_but_cannot_be_rebound_to_one(api, admin, w):
    emp = uid("E")
    uid_ = ok(
        api.post(
            "/api/users",
            m(emp_no=emp, name="n", role="factory_operator", factory_code=FAC2, password="Pass@1234"),
            admin,
        )
    )["id"]
    ok(api.post(f"/api/factories/{FAC2}/disable", m(), admin))
    assert _row(api, admin, FAC2)["bound_users"] >= 1
    # 改名不动绑定：允许
    ok(api.put(f"/api/users/{uid_}", m(name="改名", role="factory_operator", factory_code=FAC2), admin))
    ok(api.post(f"/api/factories/{FAC}/disable", m(), admin))
    r = api.put(f"/api/users/{uid_}", m(name="改名", role="factory_operator", factory_code=FAC), admin)
    assert r.code == "FACTORY_DISABLED"


def test_rename_factory_requires_unique_name_and_is_audited(api, admin, w, sql):
    code = uid("F")
    w.factory(code, uid("厂"))
    new = uid("新名")
    assert ok(api.put(f"/api/factories/{code}", m(factory_name=new), admin))["factory_name"] == new
    assert api.put(f"/api/factories/{code}", m(factory_name=ORG), admin).code == "FACTORY_EXISTS"
    assert api.put("/api/factories/NOPE", m(factory_name="x"), admin).code == "FACTORY_NOT_FOUND"
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'FACTORY_UPDATE' AND factory_code = %s", code) == 1


def test_k3_sync_follows_forbid_status_for_k3_factories_only(api, admin, w, sql):
    k3_code, manual_code = uid("K"), uid("M")
    w.factory(manual_code, uid("手工厂"))
    conftest.ORGS[:] = [(k3_code, uid("金蝶厂"), "A")]
    try:
        ok(api.post("/api/factories/sync", m(), admin))
        assert _row(api, admin, k3_code)["enabled"] is True
        name = _row(api, admin, k3_code)["factory_name"]
        conftest.ORGS[:] = [(k3_code, name, "B"), (manual_code, "随便", "B"), (uid("K"), uid("新禁用"), "B")]
        r = ok(api.post("/api/factories/sync", m(), admin)).body
        assert r["disabled"] == 1 and r["inserted"] == 0, "金蝶禁用的新组织不建档"
        assert _row(api, admin, k3_code)["enabled"] is False
        assert _row(api, admin, manual_code)["enabled"] is True, "手工建档的工厂不跟金蝶启停"
        conftest.ORGS[:] = [(k3_code, name, "A")]
        assert ok(api.post("/api/factories/sync", m(), admin)).body["enabled"] == 1
        assert _row(api, admin, k3_code)["enabled"] is True
    finally:
        conftest.ORGS[:] = []


# ---------------------------------------------------------------- 痕迹归档


def _archive_row(sql, when: datetime, pi: str) -> None:
    """模拟已归档的痕迹（归档表保留原 id；这里取负数，不与在线表冲突）。"""
    sql.exec(
        "INSERT INTO sn_audit_archive(id, created_at, operator, source, action, result, pi_no, archived_at) "
        "VALUES (%s, %s, 'old', 'PAGE', 'SN_ACQUIRE', 'OK', %s, NOW())",
        -random.randint(1, 2**62),
        when,
        pi,
    )


def test_archived_audit_shows_on_page_and_export(api, admin, sql):
    pi = uid("PI")
    when = datetime.now() - timedelta(days=1200)
    _archive_row(sql, when, pi)
    frm = (when - timedelta(days=1)).date().isoformat()
    r = ok(api.get("/api/audits" + q(pi=pi, **{"from": frm}), admin)).body
    assert r["total"] == 1 and r["items"][0]["archived"] is True and r["items"][0]["operator"] == "old"
    # 默认最近 30 天不碰归档
    assert ok(api.get("/api/audits" + q(pi=pi), admin))["total"] == 0
    meta = ok(api.get("/api/audits/meta", admin)).body
    assert meta["archive_from"] is not None and meta["archive_retention_days"] == 1825
    exp = api.get("/api/audits/export" + q(pi=pi, format="csv", **{"from": frm}), admin)
    assert exp.status == 200 and "SN_ACQUIRE" in exp.raw.decode("utf-8-sig")


def test_archive_purge_keeps_at_least_online_retention(sql):
    from app.services import partitions

    pi = uid("PI")
    _archive_row(sql, datetime.now() - timedelta(days=2000), pi)
    _archive_row(sql, datetime.now() - timedelta(days=1500), pi)
    # 设得比在线保存期（1095 天）还短：按 1095 天算，两条都过期
    # 设 1825 天：只删 2000 天前的那条
    assert partitions.purge_archive(1825) >= 1
    assert sql.count("SELECT COUNT(*) FROM sn_audit_archive WHERE pi_no = %s", pi) == 1
    assert partitions.purge_archive(0) == 0, "0 = 永久"
    assert partitions.purge_archive(30) >= 1
    assert sql.count("SELECT COUNT(*) FROM sn_audit_archive WHERE pi_no = %s", pi) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'AUDIT_ARCHIVE_PURGE'") >= 2
