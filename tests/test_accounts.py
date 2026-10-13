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
    assert api.get("/api/users", qt).code == "USER_MANAGE_FORBIDDEN"
    assert api.post("/api/users", m(emp_no=uid("x"), name="x", role="admin", password="Init@1234"), qt).status != 200


def test_login_fails_with_wrong_password(api, admin):
    assert api.post("/api/auth/login", m(emp_no="admin", password="bad"), None).code == "LOGIN_FAILED"
    assert api.get("/api/sn", None).code == "UNAUTHENTICATED"


def test_repeated_login_failures_never_lock_and_are_audited(api, w, sql):
    emp = uid("lk")
    w.user(emp, "query", None)
    for _ in range(8):
        assert api.post("/api/auth/login", m(emp_no=emp, password="bad"), None).code == "LOGIN_FAILED"
    ok(api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None))
    assert (
        sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'LOGIN' AND result = 'FAIL' AND operator = %s", emp)
        == 8
    )
    assert (
        sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'LOGIN' AND result = 'OK' AND operator = %s", emp) >= 1
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


def test_only_builtin_admin_manages_accounts(api, admin, w):
    """只有内置 admin 能建各角色账户；被创建的系统管理员不能建、改账户。"""
    me = ok(api.get("/api/auth/me", admin))
    assert me["super_admin"] is True
    hq = w.user(uid("hq"), "admin", None)
    assert ok(api.get("/api/auth/me", hq))["super_admin"] is False
    assert api.get("/api/users", hq).code == "USER_MANAGE_FORBIDDEN"
    created = api.post("/api/users", m(emp_no=uid("x"), name="x", role="query", password="Init@1234"), hq)
    assert created.status == 403
    assert created.code == "USER_MANAGE_FORBIDDEN"
    assert api.get("/api/sn", hq).status == 200, "other admin permissions are unchanged"
    assert api.put(f"/api/users/{me['id']}", m(name="管理员", role="query"), admin).code == "USER_SUPER_ADMIN_FIXED"


def test_same_machine_can_stay_logged_in_as_several_accounts(api, admin, w):
    a = w.user(uid("ma"), "query", None)
    b = w.user(uid("mb"), "query", None)
    assert api.get("/api/auth/me", a).status == 200
    assert api.get("/api/auth/me", b).status == 200
    assert api.get("/api/auth/me", admin).status == 200


def test_page_login_replaces_earlier_page_of_same_account_only(api, w):
    emp = uid("s")
    w.user(emp, "query", None)
    first = ok(api.page_login(emp, "Pass@1234")).text("token")
    other_emp = uid("o")
    w.user(other_emp, "query", None)
    other = ok(api.page_login(other_emp, "Pass@1234")).text("token")
    second = ok(api.page_login(emp, "Pass@1234")).text("token")
    replaced = api.get("/api/auth/me", first)
    assert replaced.status == 401
    assert replaced.code == "SESSION_REPLACED"
    assert api.get("/api/auth/me", second).status == 200
    assert api.get("/api/auth/me", other).status == 200, "other accounts on the same machine stay signed in"
    third = ok(api.page_login(emp, "Pass@1234")).text("token")
    assert api.get("/api/auth/me", second).code == "SESSION_REPLACED"
    assert api.get("/api/sn", third).status == 200


def test_mes_login_neither_replaces_nor_is_replaced(api, w):
    emp = uid("mes")
    mes1 = w.user(emp, "query", None)
    mes2 = ok(api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None)).text("token")
    page = ok(api.page_login(emp, "Pass@1234")).text("token")
    mes3 = ok(api.post("/api/auth/login", m(emp_no=emp, password="Pass@1234"), None)).text("token")
    for t in (mes1, mes2, mes3, page):
        assert api.get("/api/sn", t).status == 200
    ok(api.page_login(emp, "Pass@1234"))
    assert api.get("/api/sn", page).code == "SESSION_REPLACED"
    for t in (mes1, mes2, mes3):
        assert api.get("/api/sn", t).status == 200, "MES tokens are never replaced"


def test_password_change_keeps_current_page_session(api, w):
    emp = uid("p")
    w.user(emp, "query", None)
    token = ok(api.page_login(emp, "Pass@1234")).text("token")
    fresh = ok(api.post("/api/auth/password", m(old_password="Pass@1234", new_password="Better@123"), token))
    assert api.get("/api/sn", fresh.text("token")).status == 200
    ok(api.page_login(emp, "Better@123"))
    assert api.get("/api/sn", fresh.text("token")).code == "SESSION_REPLACED"


def test_query_account_binds_one_many_or_all_factories(api, admin, w):
    w.factory("F-QB1", "查询绑定一厂")
    w.factory("F-QB2", "查询绑定二厂")
    emp = uid("qb")
    u = ok(
        api.post(
            "/api/users",
            m(emp_no=emp, name="Q", role="query", factory_codes=["F-QB1", "F-QB2"], password="Init@1234"),
            admin,
        )
    )
    assert u["factory_codes"] == ["F-QB1", "F-QB2"]
    assert u["factory_names"] == ["查询绑定一厂", "查询绑定二厂"]
    assert ok(api.get("/api/users" + q(factory_code="F-QB2", q=emp), admin))["total"] == 1
    one = ok(api.put(f"/api/users/{u['id']}", m(name="Q", role="query", factory_codes=["F-QB2"]), admin))
    assert one["factory_codes"] == ["F-QB2"]
    every = ok(api.put(f"/api/users/{u['id']}", m(name="Q", role="query", factory_codes=[]), admin))
    assert every["factory_codes"] == []
    assert (
        api.post(
            "/api/users",
            m(emp_no=uid("qx"), name="Q", role="query", factory_codes=["NO-SUCH-F"], password="Init@1234"),
            admin,
        ).code
        == "FACTORY_NOT_FOUND"
    )
    assert (
        api.post(
            "/api/users",
            m(
                emp_no=uid("fx"),
                name="F",
                role="factory_operator",
                factory_codes=["F-QB1", "F-QB2"],
                password="Init@1234",
            ),
            admin,
        ).code
        == "USER_FACTORY_REQUIRED"
    )
    # 改成管理员时清掉查询员的绑定
    adm = ok(api.put(f"/api/users/{u['id']}", m(name="Q", role="query", factory_codes=["F-QB1"]), admin))
    assert adm["factory_codes"] == ["F-QB1"]
    assert ok(api.put(f"/api/users/{u['id']}", m(name="Q", role="admin"), admin))["factory_codes"] == []
