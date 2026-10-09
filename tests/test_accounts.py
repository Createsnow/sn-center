"""账户：工号唯一、新建直接可用、重置后强制改密、只停用不删除、停用不能登录、改密 / 重置后旧令牌失效。"""

from conftest import m, ok, q, uid


def test_new_account_works_without_password_change(api, admin):
    emp = uid("u")
    ok(api.post("/api/users", m(emp_no=emp, name="张三", role="query", password="Init@1234"), admin))
    login = ok(api.post("/api/auth/login", m(emp_no=emp, password="Init@1234"), None))
    assert login["user"]["must_change_pwd"] is False
    assert api.get("/api/sn", login.text("token")).status == 200


def test_reset_forces_change_and_old_token_dies(api, admin):
    emp = uid("u")
    ok(api.post("/api/users", m(emp_no=emp, name="张三", role="query", password="Init@1234"), admin))
    uid_ = ok(api.get("/api/users" + q(q=emp), admin))["items"][0]["id"]
    ok(api.post(f"/api/users/{uid_}/reset-password", m(password="Reset@123"), admin))
    login = ok(api.post("/api/auth/login", m(emp_no=emp, password="Reset@123"), None))
    assert login["user"]["must_change_pwd"] is True
    t0 = login.text("token")
    blocked = api.get("/api/sn", t0)
    assert blocked.status == 403
    assert blocked.code == "PASSWORD_CHANGE_REQUIRED"
    assert api.get("/api/auth/me", t0).status == 200, "me stays reachable"
    assert (
        api.post("/api/auth/password", m(old_password="Reset@123", new_password="short"), t0).code
        == "USER_PASSWORD_WEAK"
    )
    assert (
        api.post("/api/auth/password", m(old_password="nope", new_password="Better@123"), t0).code
        == "USER_PASSWORD_WRONG"
    )
    t1 = ok(api.post("/api/auth/password", m(old_password="Reset@123", new_password="Better@123"), t0)).text("token")
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


def test_cannot_disable_self_or_last_admin_and_only_admin_manages_users(api, admin, w):
    admin_id = ok(api.get("/api/auth/me", admin))["id"]
    assert api.post(f"/api/users/{admin_id}/disable", None, admin).code == "USER_SELF_DISABLE"
    qt = w.user(uid("q"), "query", None)
    assert api.get("/api/users", qt).code == "FORBIDDEN"
    assert api.post("/api/users", m(emp_no=uid("x"), name="x", role="admin", password="Init@1234"), qt).status != 200


def test_login_fails_with_wrong_password(api, admin):
    assert api.post("/api/auth/login", m(emp_no="admin", password="bad"), None).code == "LOGIN_FAILED"
    assert api.get("/api/sn", None).code == "UNAUTHENTICATED"


def test_login_locks_after_repeated_failures_and_is_audited(api, w, sql):
    from app.services import login_guard

    emp = uid("lk")
    w.user(emp, "query", None)
    for _ in range(4):
        assert api.post("/api/auth/login", m(emp_no=emp, password="bad"), None).code == "LOGIN_FAILED"
    fifth = api.post("/api/auth/login", m(emp_no=emp, password="bad"), None)
    assert fifth.code == "LOGIN_FAILED", "the attempt that reaches the limit still reports wrong password"
    locked = api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None)
    assert locked.status == 429
    assert locked.code == "LOGIN_LOCKED"
    assert locked["params"]["minutes"] == 15
    assert (
        sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'LOGIN' AND result = 'FAIL' AND operator = %s", emp)
        == 6
    )
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE operator = %s AND detail LIKE '%%locked=15m%%'", emp) == 1
    login_guard.reset()
    ok(api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None))
    assert (
        sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'LOGIN' AND result = 'OK' AND operator = %s", emp) >= 2
    )


def test_login_success_clears_failure_count(api, w):
    emp = uid("lc")
    w.user(emp, "query", None)
    for _ in range(4):
        assert api.post("/api/auth/login", m(emp_no=emp, password="bad"), None).code == "LOGIN_FAILED"
    ok(api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None))
    for _ in range(4):
        assert api.post("/api/auth/login", m(emp_no=emp, password="bad"), None).code == "LOGIN_FAILED"
    ok(api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None))


def test_login_with_unknown_emp_no_is_audited(api, sql):
    emp = uid("ghost")
    assert api.post("/api/auth/login", m(emp_no=emp, password="whatever1"), None).code == "LOGIN_FAILED"
    row = sql.one("SELECT result, error_msg, detail FROM sn_audit WHERE action = 'LOGIN' AND operator = %s", emp)
    assert row["result"] == "FAIL"
    assert row["error_msg"] == "工号或密码错误"
    assert row["detail"].startswith("client=")
