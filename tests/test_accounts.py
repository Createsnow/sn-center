"""账户：工号唯一、首次登录强制改密、只停用不删除、停用不能登录、改密 / 重置后旧令牌失效。（对应 AccountIT）"""

from conftest import m, ok, q, uid


def test_first_login_must_change_password_and_old_token_dies(api, admin):
    emp = uid("u")
    ok(api.post("/api/users", m(emp_no=emp, name="张三", role="query", password="Init@1234"), admin))
    login = ok(api.post("/api/auth/login", m(emp_no=emp, password="Init@1234"), None))
    assert login["user"]["must_change_pwd"] is True
    t0 = login.text("token")
    blocked = api.get("/api/sn", t0)
    assert blocked.status == 403
    assert blocked.code == "PASSWORD_CHANGE_REQUIRED"
    assert api.get("/api/auth/me", t0).status == 200, "me stays reachable"
    assert (
        api.post("/api/auth/password", m(old_password="Init@1234", new_password="short"), t0).code
        == "USER_PASSWORD_WEAK"
    )
    assert (
        api.post("/api/auth/password", m(old_password="nope", new_password="Better@123"), t0).code
        == "USER_PASSWORD_WRONG"
    )
    t1 = ok(api.post("/api/auth/password", m(old_password="Init@1234", new_password="Better@123"), t0)).text("token")
    assert api.get("/api/auth/me", t0).code == "TOKEN_INVALID"
    assert api.get("/api/sn", t1).status == 200


def test_emp_no_unique_and_role_factory_rules(api, admin, w):
    emp = uid("dup")
    ok(api.post("/api/users", m(emp_no=emp, name="A", role="query", password="Init@1234"), admin))
    assert (
        api.post("/api/users", m(emp_no=emp, name="B", role="query", password="Init@1234"), admin).code
        == "USER_EMP_NO_EXISTS"
    )
    assert (
        api.post("/api/users", m(emp_no=uid("f"), name="B", role="factory_operator", password="Init@1234"), admin).code
        == "USER_FACTORY_REQUIRED"
    )
    w.factory("F-ACC", "账户测试厂")
    assert (
        api.post(
            "/api/users", m(emp_no=uid("a"), name="B", role="admin", factory_code="F-ACC", password="Init@1234"), admin
        ).code
        == "USER_ADMIN_NO_FACTORY"
    )
    assert (
        api.post("/api/users", m(emp_no="有 空格", name="B", role="query", password="Init@1234"), admin).code
        == "USER_EMP_NO_INVALID"
    )


def test_disable_keeps_record_and_blocks_login(api, admin, w):
    emp = uid("d")
    token = w.user(emp, "query", None)
    uid_ = ok(api.get("/api/users" + q(q=emp), admin))["items"][0]["id"]
    ok(api.post(f"/api/users/{uid_}/disable", None, admin))
    assert api.get("/api/auth/me", token).code == "ACCOUNT_DISABLED"
    assert api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None).code == "ACCOUNT_DISABLED"
    lst = ok(api.get("/api/users" + q(q=emp, status="DISABLED"), admin))
    assert lst["total"] == 1, "disabled account is kept"
    ok(api.post(f"/api/users/{uid_}/enable", None, admin))
    assert api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None).status == 200
    audits = ok(api.get("/api/audits" + q(action="USER_DISABLE", keyword=emp), admin))
    assert audits["total"] >= 1


def test_reset_password_forces_change_again(api, admin, w):
    emp = uid("r")
    token = w.user(emp, "query", None)
    uid_ = ok(api.get("/api/users" + q(q=emp), admin))["items"][0]["id"]
    ok(api.post(f"/api/users/{uid_}/reset-password", m(password="Reset@123"), admin))
    assert api.get("/api/auth/me", token).code == "TOKEN_INVALID"
    login = ok(api.post("/api/auth/login", m(emp_no=emp, password="Reset@123"), None))
    assert login["user"]["must_change_pwd"] is True


def test_cannot_disable_self_or_last_admin_and_only_admin_manages_users(api, admin, w):
    admin_id = ok(api.get("/api/auth/me", admin))["id"]
    assert api.post(f"/api/users/{admin_id}/disable", None, admin).code == "USER_SELF_DISABLE"
    qt = w.user(uid("q"), "query", None)
    assert api.get("/api/users", qt).code == "FORBIDDEN"
    assert api.post("/api/users", m(emp_no=uid("x"), name="x", role="admin", password="Init@1234"), qt).status != 200


def test_login_fails_with_wrong_password(api, admin):
    assert api.post("/api/auth/login", m(emp_no="admin", password="bad"), None).code == "LOGIN_FAILED"
    assert api.get("/api/sn", None).code == "UNAUTHENTICATED"
