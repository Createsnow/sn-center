"""账户（F-01）、角色与权限（F-02）、生产组织同步（F-03）、MES 对接配置（F-04）。"""

from __future__ import annotations

from conftest import ADMIN_PASSWORD, MES, emp, m, ok, uid


def test_rules_for_emp_no_password_and_roles(w, api):
    assert api.post("/api/users", m(emp_no="ab", name="x", role="query", password="Pass1234"), w.admin).code == (
        "USER_EMP_NO_INVALID"
    )
    assert api.post("/api/users", m(emp_no="ab_cd", name="x", role="query", password="Pass1234"), w.admin).code == (
        "USER_EMP_NO_INVALID"
    )
    for bad in ("short1", "onlyletters", "12345678", "Pass 1234", "密码Pass1234", "Pass1234%"):
        r = api.post("/api/users", m(emp_no=emp(), name="x", role="query", password=bad), w.admin)
        assert r.code == "USER_PASSWORD_WEAK", bad
    ok(api.post("/api/users", m(emp_no=emp(), name="x", role="query", password="Pa$s1234&*"), w.admin))


def test_admin_created_admin_cannot_manage_accounts(w, api):
    tok = w.user(emp("A"), "admin", None)
    assert api.get("/api/users", tok).code == "USER_MANAGE_FORBIDDEN"
    assert api.get("/api/generate/pis", tok).status == 200


def test_single_page_session(w, api):
    e = emp()
    t1 = w.user(e, "query", None)
    t2 = ok(api.page_login(e, "Pass1234")).text("token")
    assert api.get("/api/auth/me", t1).code == "SESSION_REPLACED"
    assert api.get("/api/auth/me", t2).status == 200


def test_password_change_and_reset_require_relogin_without_forced_change(w, api):
    e = emp()
    t1 = w.user(e, "query", None)
    assert api.get("/api/dashboard", t1).status == 200, "新账户直接可用，不强制改密"
    r = ok(api.post("/api/auth/password", m(old_password="Pass1234", new_password="Pass5678"), t1))
    assert r["relogin"] is True, "本人改密不发新令牌，须重新登录"
    assert api.get("/api/auth/me", t1).code == "PASSWORD_CHANGED", "改密后当前页面也须重新登录"
    assert api.page_login(e, "Pass1234").code == "LOGIN_FAILED"
    t2 = ok(api.page_login(e, "Pass5678")).text("token")
    assert api.get("/api/dashboard", t2).status == 200

    me = ok(api.get("/api/auth/me", t2))
    ok(api.post(f"/api/users/{me['id']}/reset-password", m(password="Reset123"), w.admin))
    assert api.get("/api/auth/me", t2).code == "PASSWORD_CHANGED", "重置后已登录的页面须重新登录"
    t3 = ok(api.page_login(e, "Reset123")).text("token")
    assert api.get("/api/dashboard", t3).status == 200, "重置后用新密码登录即可使用，不强制改密"


def test_mes_account_cannot_login_page_and_secret_once(w, api, sql):
    org = w.org(uid("O")[:10])
    r = api.post("/api/users", m(emp_no=emp("M"), name="M", role="mes", factory_code=org), w.admin)
    assert r.code == "USER_MES_FACTORY_REQUIRED", "MES 接口账户须绑「对接 MES = 是」的组织"
    w.enable_mes(org)
    e = emp("M")
    r = ok(
        api.post(
            "/api/users",
            m(emp_no=e, name="M", role="mes", factory_code=org, ip_whitelist="127.0.0.1, 10.0.0.0/8"),
            w.admin,
        )
    )
    assert r["secret"] and r["role"] == "mes"
    lst = ok(api.get(f"/api/users?q={e}", w.admin))["items"][0]
    assert "secret" not in lst and lst["ip_whitelist"] == "127.0.0.1,10.0.0.0/8"
    assert api.page_login(e, r["secret"]).code == "MES_NO_PAGE_LOGIN"
    cred = f"{e}:{r['secret']}"
    assert api.post("/api/mes/v1/auth/token", m(empNo=e, secret=r["secret"]), None).status == 404, "不再换令牌"
    t = ok(api.call("POST", "/api/mes/v1/batches/next", m(), cred, {"X-Real-IP": "10.1.2.3"}))
    assert t["code"] == "OK", "每次请求直接带工号:密钥"
    assert api.get("/api/dashboard", cred).status == 401, "MES 接口账户只能调 MES 接口"
    assert ok(api.get(f"/api/users?q={e}", w.admin))["items"][0]["last_login_at"], "账户页显示最近认证时间"


def test_mes_secret_viewable_and_resettable(w, api, sql):
    org = w.org(uid("O")[:10])
    w.enable_mes(org)
    e = emp("M")
    r = ok(api.post("/api/users", m(emp_no=e, name="M", role="mes", factory_code=org), w.admin))
    u_id, first = r["id"], r["secret"]
    assert sql.scalar("SELECT secret_sealed FROM sn_user WHERE id = %s", u_id) not in ("", first), "密钥加密保存"
    v = ok(api.get(f"/api/users/{u_id}/secret", w.admin))
    assert (v["emp_no"], v["secret"]) == (e, first), "关闭后仍可查看"
    assert (
        sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'USER_VIEW_SECRET' AND detail LIKE %s", f"%{e}%") >= 1
    )
    second = ok(api.post(f"/api/users/{u_id}/reset-secret", {}, w.admin))["secret"]
    assert second != first and ok(api.get(f"/api/users/{u_id}/secret", w.admin))["secret"] == second
    ok(w.mes_next(f"{e}:{second}"))
    assert w.mes_next(f"{e}:{first}").code == "INVALID_CREDENTIALS"
    sql.exec("UPDATE sn_user SET secret_sealed = '' WHERE id = %s", u_id)
    assert api.get(f"/api/users/{u_id}/secret", w.admin).code == "USER_SECRET_UNAVAILABLE", "解不开时须重置"
    admin_id = sql.scalar("SELECT id FROM sn_user WHERE emp_no = 'admin'")
    assert api.get(f"/api/users/{admin_id}/secret", w.admin).code == "USER_NOT_MES"


def test_mes_ip_whitelist(w, api):
    org = w.org(uid("O")[:10])
    w.enable_mes(org)
    e = emp("M")
    r = ok(
        api.post("/api/users", m(emp_no=e, name="M", role="mes", factory_code=org, ip_whitelist="10.9.9.9"), w.admin)
    )
    x = api.post("/api/mes/v1/batches/next", m(), f"{e}:{r['secret']}")
    assert (x.status, x.code) == (403, "IP_NOT_ALLOWED")


def test_role_pages(w, api):
    org = w.org(uid("O")[:10])
    op = w.user(emp("F"), "factory_operator", org)
    q = w.user(emp("Q"), "query", None)
    assert api.get("/api/orders", op).code == "FORBIDDEN"
    assert api.get("/api/generate/pis", q).code == "FORBIDDEN"
    assert api.get("/api/rules", op).code == "FORBIDDEN"
    assert api.get("/api/orders", q).status == 200
    assert api.post("/api/transfers/bill", m(), op).code == "FORBIDDEN", "操作员不能转单"


def test_org_sync_keeps_code_and_marks_disabled(w, api, sql):
    code = uid("O")[:10]
    w.org(code, "旧名")
    w.org(code, "新名")
    assert sql.scalar("SELECT factory_name FROM sn_factory WHERE factory_code = %s", code) == "新名"
    w.org(code, "新名", enabled=False)
    assert sql.scalar("SELECT enabled FROM sn_factory WHERE factory_code = %s", code) == 0
    q = emp()
    assert (
        api.post(
            "/api/users",
            m(emp_no=q, name="x", role="factory_operator", factory_code=code, password="Pass1234"),
            w.admin,
        ).code
        == "FACTORY_DISABLED"
    )


def test_mes_enable_requires_test_and_disable_blocked(w, api, sql):
    org = w.org(uid("O")[:10])
    assert api.post(f"/api/factories/{org}/mes-toggle", m(enabled=True), w.admin).code == "MES_TEST_REQUIRED"
    MES.secrets[org] = "t"
    bad = api.post(f"/api/factories/{org}/mes-test", m(mes_url=MES.url(org), mes_credential="wrong"), w.admin)
    assert ok(bad)["ok"] is False
    w.enable_mes(org, batch_size=5)
    f = [x for x in ok(api.get("/api/factories", w.admin)) if x["factory_code"] == org][0]
    assert f["mes_enabled"] and f["cfg_status"] == "OK" and f["credential_set"] and "mes_credential" not in f
    # 有未确认的批次时不能关闭
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 8))
    ok(w.bind_pi(pi, w.rule("P" + uid("").replace("-", ""))))
    w.generate(pi)
    w.allocate(pi)
    MES.behavior[org] = lambda b, h: (503, {"code": "X"})
    w.push()
    r = api.post(f"/api/factories/{org}/mes-toggle", m(enabled=False), w.admin)
    assert r.code == "MES_DISABLE_BLOCKED"
    MES.behavior.pop(org)
    ok(api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin))
    w.push()
    ok(api.post(f"/api/factories/{org}/mes-toggle", m(enabled=False), w.admin))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 8


def test_mes_toggle_moves_unbatched_and_allocated(w, api, sql):
    org = w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 5))
    ok(w.bind_pi(pi, w.rule("P" + uid("").replace("-", ""))))
    w.generate(pi)
    w.allocate(pi)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'ALLOCATED'", pi) == 5
    w.enable_mes(org)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'TO_PUSH'", pi) == 5
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_batch") == 0, "开启时不自动推送"
    ok(api.post(f"/api/factories/{org}/mes-toggle", m(enabled=False), w.admin))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'ALLOCATED'", pi) == 5


def test_hmac_push(w, api, sql):
    org = w.org(uid("O")[:10])
    w.enable_mes(org, auth="HMAC")
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 2))
    ok(w.bind_pi(pi, w.rule("P" + uid("").replace("-", ""))))
    w.generate(pi)
    w.allocate(pi)
    w.push()
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE pi_no = %s", pi) == "SUCCESS"
    h = MES.received[-1]["headers"]
    assert "x-sn-signature" in h and "x-sn-timestamp" in h and "authorization" not in h


def test_builtin_admin_reset_via_env(w, api):
    # 不带页面头登录（不挤掉会话里共用的总部页面令牌）
    assert api.post("/api/auth/login", m(emp_no="admin", password=ADMIN_PASSWORD), None).status == 200


def test_mes_gzip_switch_needs_retest_and_compresses_push(w, api, sql):
    """「gzip 压缩」默认关（推送发原始 JSON）；已开启对接时改它须先连接测试，开启后推送带 Content-Encoding: gzip。"""
    org = w.org(uid("O")[:10])
    w.enable_mes(org, batch_size=5)
    f = [x for x in ok(api.get("/api/factories", w.admin)) if x["factory_code"] == org][0]
    assert f["mes_gzip"] is False and f["tested"]
    cfg = m(mes_url=MES.url(org), mes_auth_type="TOKEN", mes_gzip=True)
    assert api.put(f"/api/factories/{org}/mes-config", cfg, w.admin).code == "MES_TEST_REQUIRED"
    t = ok(api.post(f"/api/factories/{org}/mes-test", cfg, w.admin))
    assert t["ok"] and MES.received[-1]["headers"].get("content-encoding") == "gzip"
    saved = ok(api.put(f"/api/factories/{org}/mes-config", cfg, w.admin))
    assert saved["mes_gzip"] is True and saved["tested"] and saved["cfg_status"] == "OK"
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 3))
    ok(w.bind_pi(pi, w.rule("P" + uid("").replace("-", ""))))
    w.generate(pi)
    w.allocate(pi)
    w.push()
    assert MES.received[-1]["headers"].get("content-encoding") == "gzip"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 3
