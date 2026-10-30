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


def test_two_orgs_share_one_pi_serial_in_generation_order(w, api, sql):
    """同一 PI 的两张单据分属两个组织：共用 PI 的一套流水，谁先生成谁的流水在前，号不重复也不跳号。"""
    a, b, pi, bill_a, bill_b = two_org_pi(w, qty_a=3, qty_b=2)
    op_a, op_b = w.user(emp("F"), "operator", a), w.user(emp("F"), "operator", b)
    run(api, op_b, pi, [bill_b])
    run(api, op_a, pi, [bill_a])
    rows = sql.all(
        "SELECT bill_no, MIN(seq_dec) lo, MAX(seq_dec) hi, COUNT(DISTINCT rule_version_id) v FROM sn_item "
        "WHERE pi_no = %s GROUP BY bill_no",
        pi,
    )
    got = {r["bill_no"]: (r["lo"], r["hi"], r["v"]) for r in rows}
    assert got == {bill_b: (1, 2, 1), bill_a: (3, 5, 1)}, "B 先生成得 1–2，A 后生成接着 3–5，同一规则版本"
    assert sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) == 5


def test_custom_rule_shared_across_orgs_is_global(w, api, sql):
    """同一 PI 两个组织的物料行选了同一自定义规则：先生成的一方存入规则，另一方接着用；规则为全局（两边都能看到）。"""
    a, b, pi, bill_a, bill_b = two_org_pi(w, qty_a=2, qty_b=2)
    custom = m(prefix=P(), base=10, seq_len=6, with_length=False)
    lines = [m(bill_no=bill_a, seq=1), m(bill_no=bill_b, seq=1)]
    ok(api.post("/api/rules/line-select", m(pi_no=pi, lines=lines, custom=custom), w.admin))
    run(api, w.admin, pi, [bill_a])
    r = sql.one("SELECT * FROM sn_rule WHERE category_value = %s", pi)
    assert r["factory_code"] is None
    op_b = w.user(emp("F"), "operator", b)
    run(api, op_b, pi, [bill_b])
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE category_value = %s", pi) == 1
    assert r["id"] in {x["rule"]["id"] for x in ok(api.get("/api/rules", op_b)).body}


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
        return api.post(
            "/api/rules",
            m(rule_code=uid("R"), rule_name="r", category_value=uid("PI"), prefix=P(), base=10, seq_len=6) | kw,
            tok,
        )

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
    assert api.post("/api/transfers/org/direct", body, op).code == "REASON_REQUIRED", "直接转移须填原因"
    outsider = w.user(emp("F"), "operator", b)
    src_a = body | m(reason="x")
    assert api.post("/api/transfers/org/direct", src_a, outsider).code == "FACTORY_FORBIDDEN", "来源组织须授权"
    # 目标组织不在授权范围内也可直接转
    ok(api.post("/api/transfers/org/direct", src_a, op))
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND factory_code = %s", pi, b) == 2
    au = w.user(emp("Q"), "auditor", a)
    assert api.post("/api/transfers/org/direct", src_a, au).code == "FORBIDDEN"


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
