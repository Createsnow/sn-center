"""权限调整：系统管理员（唯一）/ 普通操作员 / 审计员；普通操作员只能操作授权组织的单据、号和规则。"""

from __future__ import annotations

from conftest import emp, m, ok, uid


def P() -> str:
    return "P" + uid("").replace("-", "")


def two_org_pi(w, qty_a=3, qty_b=2):
    """一张 PI 两张单据：A 组织一张、B 组织一张（每张单据只属于一个组织）。"""
    a, b = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    pi, bill_a, bill_b = uid("PI"), uid("MO"), uid("MO")
    w.order(bill_a, a, "C1", pi, ("M1", qty_a))
    w.order(bill_b, b, "C1", pi, ("M1", qty_b))
    ok(w.bind_pi(pi, w.rule(P())))
    return a, b, pi, bill_a, bill_b


def run(api, tok, pi, bills):
    p = ok(api.post("/api/generate/preview", m(pi_no=pi, scope="BILLS", bills=bills), tok))
    assert p["generatable"], str(p)
    return ok(api.post("/api/generate/run", m(token=p["token"]), tok))


def test_generate_only_authorized_bills(w, api):
    a, b, pi, bill_a, bill_b = two_org_pi(w)
    op = w.user(emp("F"), "operator", a)
    row = next(x for x in ok(api.get(f"/api/generate/pis?q={pi}", op))["items"] if x["pi_no"] == pi)
    assert (row["bills"], row["total_qty"], row["orgs"]) == (1, 3, [a]), "PI 只按授权组织的单据汇总"
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", op))
    assert [x["bill_no"] for x in ctx["bills"]] == [bill_a], "生成页只含授权组织的单据"
    r = api.post("/api/generate/preview", m(pi_no=pi, scope="BILLS", bills=[bill_b]), op)
    assert r.code == "FACTORY_FORBIDDEN"
    run(api, op, pi, [bill_a])
    outsider = w.user(emp("F"), "operator", w.org(uid("O")[:10]))
    assert api.get(f"/api/generate/context?pi={pi}", outsider).code == "FACTORY_FORBIDDEN"

    # B 组织的号在流水后面：A 的操作员不能撤 A 的行（会连带撤 B 的行）
    w.generate(pi, [bill_b])
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", op))
    assert [(s["bill_no"], s["ok"]) for s in ctx["segments"]] == [(bill_a, False)]
    assert ctx["revocable"] is None and all(not j["revocable"] for j in ctx["jobs"])
    assert all(x["bill_no"] == bill_a for j in ctx["jobs"] for x in j["lines"]), "任务里只列授权组织的物料行"
    r = api.post("/api/generate/revoke", m(pi_no=pi, lines=[m(bill_no=bill_a, seq=1)], reason="x"), op)
    assert r.code == "FACTORY_FORBIDDEN"
    ok(api.post("/api/generate/revoke", m(pi_no=pi, lines=[m(bill_no=bill_b, seq=1)], reason="x"), w.admin))
    ok(api.post("/api/generate/revoke", m(pi_no=pi, lines=[m(bill_no=bill_a, seq=1)], reason="x"), op))


def test_allocate_only_authorized_orgs(w, api, sql):
    a, b, pi, bill_a, bill_b = two_org_pi(w)
    w.generate(pi)
    op = w.user(emp("F"), "operator", a)
    ctx = ok(api.get(f"/api/allocation/context?pi={pi}", op))
    assert {x["bill_no"] for x in ctx["lines"]} == {bill_a} and ctx["summary"]["pending_alloc"] == 3
    assert api.post("/api/allocation/allocate", m(pi_no=pi, bills=[bill_b]), op).code == "FACTORY_FORBIDDEN"
    r = ok(api.post("/api/allocation/allocate", m(pi_no=pi), op))
    assert (r["qty"], r["orgs"]) == (3, [a]), "整张 PI 分配时只分授权组织的号"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PENDING_ALLOC'", pi) == 2


def test_rules_owned_by_org(w, api):
    a, b = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    op = w.user(emp("F"), "operator", a)
    glob = w.rule(P())

    def new(tok, **kw):
        return api.post("/api/rules", m(rule_code=uid("R"), rule_name="r", prefix=P(), base=10, seq_len=6) | kw, tok)

    created = ok(new(op))["rule"]
    mine = created["id"]
    assert created["factory_code"] == a, "只授权一个组织时默认归属它"
    theirs = ok(new(w.admin, factory_code=b))["rule"]["id"]
    ids = {x["rule"]["id"] for x in ok(api.get("/api/rules", op)).body}
    assert glob in ids and mine in ids and theirs not in ids, "全局规则 + 授权组织的规则"
    assert new(op, factory_code=b).code == "FACTORY_FORBIDDEN"
    body = m(rule_name="x", prefix=P(), base=10, seq_len=6)
    assert api.put(f"/api/rules/{glob}", body, op).code == "RULE_GLOBAL_READONLY", "全局规则只能选用"
    assert api.delete(f"/api/rules/{glob}", op).code == "RULE_GLOBAL_READONLY"
    ok(api.put(f"/api/rules/{mine}", body, op))
    ok(api.delete(f"/api/rules/{mine}", op))
    both = w.user(emp("F"), "operator", None, [a, b])
    assert new(both).code == "RULE_FACTORY_REQUIRED", "授权多个组织时须选所属组织"
    # 选用：全局规则可以绑给授权组织的 PI；别的组织的规则不行
    pi = uid("PI")
    w.order(uid("MO"), a, "C1", pi, ("M1", 1))
    ok(api.put("/api/rules/pi-binding", m(pi_no=pi, rule_id=glob, new_prefix=P()), op))
    assert api.put("/api/rules/pi-binding", m(pi_no=pi, rule_id=theirs), op).code == "FACTORY_FORBIDDEN"


def test_custom_rule_belongs_to_line_org(w, api, sql):
    a = w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, a, "C1", pi, ("M1", 2))
    op = w.user(emp("F"), "operator", a)
    custom = m(prefix=P(), base=10, seq_len=6, with_length=True)
    ok(api.post("/api/rules/line-select", m(pi_no=pi, lines=[m(bill_no=bill, seq=1)], custom=custom), op))
    run(api, op, pi, [bill])
    assert sql.scalar("SELECT factory_code FROM sn_rule WHERE category_value = %s", pi) == a


def test_org_transfer_scope(w, api, sql):
    a, b = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, a, "C1", pi, ("M1", 2))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    op = w.user(emp("F"), "operator", a)
    body = m(
        factory_code=a, pi_no=pi, bill_no=bill, scope="BILL", to_factory=b, to_pi=pi, to_bill=bill, to_material="M1"
    )
    assert api.post("/api/transfers/org/direct", body | m(reason="x"), op).code == "FACTORY_FORBIDDEN", (
        "目标组织不在授权范围内只能申请"
    )
    t = ok(api.post("/api/transfers/org", body | m(reason="x"), op))
    assert api.post(f"/api/transfers/org/{t['id']}/approve", m(note="ok"), op).code == "FACTORY_FORBIDDEN"
    both = w.user(emp("F"), "operator", None, [a, b])
    ok(api.post(f"/api/transfers/org/{t['id']}/approve", m(note="ok"), both))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s", pi, b) == 2
    au = w.user(emp("Q"), "auditor", a)
    assert api.post("/api/transfers/org", body | m(reason="x"), au).code == "FORBIDDEN"


def test_upgrade_roles_migrates_legacy_accounts(w, api, sql):
    from app.services import users

    a, b = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    every = {r["factory_code"] for r in sql.all("SELECT factory_code FROM sn_factory")}
    legacy = {}
    for role, fac in (("factory_operator", a), ("query", None), ("query", None), ("admin", None)):
        e = emp("L")
        sql.exec(
            "INSERT INTO sn_user(emp_no, name, role, factory_code, status, password_hash, created_at, updated_at) "
            "VALUES (%s,'x',%s,%s,'ACTIVE','x',NOW(),NOW())",
            e,
            role,
            fac,
        )
        legacy[e] = sql.scalar("SELECT id FROM sn_user WHERE emp_no = %s", e)
    bound_query = list(legacy)[2]
    sql.exec("INSERT INTO sn_user_factory(user_id, factory_code) VALUES (%s,%s)", legacy[bound_query], b)
    assert users.upgrade_roles() == 4
    assert users.upgrade_roles() == 0, "幂等"

    def state(e):
        u = sql.one("SELECT role, factory_code FROM sn_user WHERE emp_no = %s", e)
        rows = sql.all("SELECT factory_code FROM sn_user_factory WHERE user_id = %s", legacy[e])
        facs = {r["factory_code"] for r in rows}
        return u["role"], u["factory_code"], facs

    op, unbound, bound, old_admin = legacy
    assert state(op) == ("operator", None, {a}), "生产组织操作员 → 普通操作员，本组织为授权组织"
    assert state(unbound) == ("auditor", None, every), "未绑的查询员 → 审计员，授权全部组织"
    assert state(bound) == ("auditor", None, {b}), "已绑的查询员 → 审计员，绑定不变"
    assert state(old_admin) == ("operator", None, every), "其他管理员 → 普通操作员，授权全部组织"
    assert sql.scalar("SELECT role FROM sn_user WHERE emp_no = 'admin'") == "admin", "系统管理员不变"


def test_account_without_org_cannot_login(w, api, sql):
    a = w.org(uid("O")[:10])
    e = emp("F")
    tok = w.user(e, "operator", a)
    sql.exec("DELETE FROM sn_user_factory WHERE user_id = (SELECT id FROM sn_user WHERE emp_no = %s)", e)
    assert api.get("/api/dashboard", tok).code == "USER_NO_FACTORY"
    assert api.page_login(e, "Pass1234").code == "USER_NO_FACTORY"


def test_sn_query_factory_filter_within_scope(w, api):
    a, b, pi, bill_a, bill_b = two_org_pi(w)
    w.generate(pi)
    w.allocate(pi)
    both = w.user(emp("Q"), "auditor", None, [a, b])
    assert ok(api.get(f"/api/sn?pi={pi}", both))["total"] == 5
    assert ok(api.get(f"/api/sn?pi={pi}&factory_code={a}", both))["total"] == 3, "授权多个组织时可按组织筛选"
    only_a = w.user(emp("Q"), "auditor", a)
    assert ok(api.get(f"/api/sn?pi={pi}", only_a))["total"] == 3
    assert api.get(f"/api/sn?pi={pi}&factory_code={b}", only_a).code == "FACTORY_FORBIDDEN"
