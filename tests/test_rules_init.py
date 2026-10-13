"""规则版本、分区与痕迹归档。（对应 RuleInitIT）"""

import pytest
from conftest import m, ok, q, uid

ORG = "规则测试厂"
FAC = "F-RULE"


@pytest.fixture(autouse=True)
def setup(w, once):
    if "rule" not in once:
        once["rule"] = True
        w.factory(FAC, ORG)


def test_rename_in_place_edit_and_new_version(api, admin, w, sql):
    pi, bill, code = uid("PI"), uid("MO"), uid("RV")
    rid = ok(
        api.post(
            "/api/rules",
            m(rule_code=code, rule_name="v", bind_scope="PI", bind_value=pi, prefix="A", base=10, seq_len=4),
            admin,
        )
    )["rule"]["id"]
    r1 = ok(api.put(f"/api/rules/{rid}", m(rule_name="改名", prefix="A", base=10, seq_len=4), admin))
    assert r1.text("action") == "RENAMED"
    r2 = ok(api.put(f"/api/rules/{rid}", m(rule_name="改名", prefix="B", base=10, seq_len=4), admin))
    assert r2.text("action") == "UPDATED", "unused version edited in place"
    assert r2["rule"]["rule"]["current_version"] == 1
    w.order(bill, ORG, "C1", pi, ("A", 10))
    w.generate(bill, 2)
    r3 = ok(api.put(f"/api/rules/{rid}", m(rule_name="改名", prefix="C", base=16, seq_len=4), admin))
    assert r3.text("action") == "NEW_VERSION"
    assert r3["rule"]["rule"]["current_version"] == 2
    # 新版本只作用于之后生成的号；流水接续
    j = w.generate(bill, 1)
    assert j["start_sn"] == "C0003"
    items = ok(api.get("/api/sn" + q(pi=pi), admin))["items"]
    assert items[0]["sn"] == "B0001"
    assert items[0]["seq_text"] == "0001"
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'RULE_VERSION' AND detail LIKE %s", code + "%") == 1
    assert (
        api.post(
            "/api/rules",
            m(rule_code=uid("RV"), rule_name="dup", bind_scope="PI", bind_value=pi, prefix="Z", base=10, seq_len=4),
            admin,
        ).code
        == "RULE_BIND_EXISTS"
    )
    assert (
        api.post(
            "/api/rules",
            m(rule_code=uid("RV"), rule_name="bad", bind_scope="PI", bind_value=uid("PI"), base=8, seq_len=4),
            admin,
        ).code
        == "RULE_BASE_INVALID"
    )


def test_rule_resolution_pi_over_customer_over_general(api, admin, w):
    cust, pi, bill = uid("CU"), uid("PI"), uid("MO")
    ok(
        api.post(
            "/api/rules",
            m(
                rule_code=uid("RC"),
                rule_name="客户",
                bind_scope="CUSTOMER",
                bind_value=cust,
                prefix="CUS",
                base=10,
                seq_len=4,
            ),
            admin,
        )
    )
    w.order(bill, ORG, cust, pi, ("A", 3))
    assert ok(w.preview(bill, 1)).text("start_sn") == "CUS0001"
    w.pi_rule(pi, "PIR", 10, 4)
    assert ok(w.preview(bill, 1)).text("start_sn") == "PIR0001"
    assert ok(api.get("/api/rules/effective" + q(pi=pi), admin)).text("prefix") == "PIR"


def test_partitions_maintained_and_old_audit_archived(app_client, sql):
    from app.services import partitions

    partitions.maintain()
    partitions.maintain()
    audit_parts = sql.count(
        "SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = DATABASE() "
        "AND TABLE_NAME = 'sn_audit' AND PARTITION_NAME LIKE 'p2%%'"
    )
    assert audit_parts >= 4, "current + 3 months ahead"
    item_parts = sql.count(
        "SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = DATABASE() "
        "AND TABLE_NAME = 'sn_item' AND PARTITION_NAME IS NOT NULL"
    )
    assert item_parts == 0, "sn_item 不分区"
    # 造一个很早的月分区并放一条痕迹，按 30 天保存期归档
    first = sql.one(
        "SELECT PARTITION_NAME n FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = DATABASE() "
        "AND TABLE_NAME = 'sn_audit' ORDER BY PARTITION_ORDINAL_POSITION LIMIT 1"
    )["n"]
    bound = sql.one(
        "SELECT PARTITION_DESCRIPTION d FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = DATABASE() "
        "AND TABLE_NAME = 'sn_audit' AND PARTITION_NAME = %s",
        first,
    )["d"]
    if first != "p202001":
        sql.exec(
            f"ALTER TABLE sn_audit REORGANIZE PARTITION {first} INTO (PARTITION p202001 VALUES LESS THAN "
            f"('2020-02-01 00:00:00'), PARTITION {first} VALUES LESS THAN ({bound}))"
        )
    sql.exec(
        "INSERT INTO sn_audit(created_at, operator, source, action, result, detail) "
        "VALUES ('2020-01-15 10:00:00', 'old', 'SYSTEM', 'OTHER', 'OK', 'archive-me')"
    )
    assert partitions.archive(30) >= 1
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE detail = 'archive-me'") == 0
    assert sql.count("SELECT COUNT(*) FROM sn_audit_archive WHERE detail = 'archive-me'") == 1
    assert partitions.archive(0) == 0, "0 = keep forever"


def _template(api, admin, prefix: str) -> int:
    """新建一条通用规则当模板，返回规则 id。"""
    r = ok(
        api.post(
            "/api/rules",
            m(
                rule_code=uid("TP"),
                rule_name="模板" + prefix,
                bind_scope="GENERAL",
                prefix=prefix,
                base=10,
                seq_len=4,
            ),
            admin,
        )
    )
    return r["rule"]["id"]


def test_unbound_pi_must_pick_a_rule_and_then_keeps_it(api, admin, w, sql):
    tpl_a, tpl_b = _template(api, admin, "TPA"), _template(api, admin, "TPB")
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, ORG, uid("CU"), pi, ("A", 5))

    ctx = ok(api.get("/api/generate/context" + q(bill_no=bill), admin))
    assert ctx["rule"] is None
    assert {tpl_a, tpl_b} <= {o["rule_id"] for o in ctx["rule_options"]}
    assert "RULE_MISSING" not in ctx["issues"]
    assert w.preview(bill, 1).code == "RULE_CHOICE_REQUIRED"

    # 预演只看不记；生成后这张 PI 记下所选规则
    assert ok(w.preview(bill, 1, rule_id=tpl_b)).text("start_sn") == "TPB0001"
    assert sql.count("SELECT COUNT(*) FROM sn_pi_counter WHERE pi_no = %s AND rule_id IS NOT NULL", pi) == 0
    w.generate(bill, 2, rule_id=tpl_a)
    assert sql.count("SELECT COUNT(*) FROM sn_pi_counter WHERE pi_no = %s AND rule_id = %s", pi, tpl_a) == 1

    ctx = ok(api.get("/api/generate/context" + q(bill_no=bill), admin))
    assert ctx["rule"]["rule_id"] == tpl_a and ctx["rule"]["source"] == "CHOSEN"
    assert ctx["rule_options"] == []
    # 选定后再传别的规则也沿用已选定的
    assert ok(w.preview(bill, 1, rule_id=tpl_b)).text("start_sn") == "TPA0003"
    assert ok(api.get("/api/rules/effective" + q(pi=pi), admin)).text("prefix") == "TPA"

    # 之后按 PI 绑定规则：绑定优先
    w.pi_rule(pi, "PIB", 10, 4)
    assert ok(w.preview(bill, 1)).text("start_sn") == "PIB0003"


def test_preview_of_picked_rule_goes_stale_when_pi_chose_another_meanwhile(api, admin, w):
    tpl_a, tpl_b = _template(api, admin, "SPA"), _template(api, admin, "SPB")
    pi, bill1, bill2, cust = uid("PI"), uid("MO"), uid("MO"), uid("CU")
    w.order(bill1, ORG, cust, pi, ("A", 5))
    w.order(bill2, ORG, cust, pi, ("A", 5))
    p = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=1, rule_id=tpl_b), admin)).body["items"][0]
    # 期间这张 PI 先以另一条规则生成：已选定 A，按 B 做的预演失效
    w.generate_pi(pi, 1, rule_id=tpl_a)
    r = w.gen(p["token"], p["bill_no"], 1)
    assert r.code in ("GEN_PREVIEW_CHANGED", "GEN_PREVIEW_STALE"), str(r)


def _general(api, admin, prefix: str = "G") -> int:
    return _template(api, admin, prefix)


def test_multiple_general_rules_all_offered_to_unbound_pi(api, admin, w):
    ids = [_general(api, admin, "G1"), _general(api, admin, "G2")]
    bound = ok(
        api.post(
            "/api/rules",
            m(
                rule_code=uid("RC"),
                rule_name="c",
                bind_scope="CUSTOMER",
                bind_value=uid("CU"),
                prefix="CA",
                base=10,
                seq_len=4,
            ),
            admin,
        )
    )["rule"]["id"]
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, ORG, uid("CU"), pi, ("A", 5))
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin))
    opts = ctx["rule_options"]
    # 通用规则全部可见，按编码排序；按客户绑定的不列
    assert {o["bind_scope"] for o in opts} == {"GENERAL"}
    assert set(ids) <= {o["rule_id"] for o in opts}
    assert bound not in {o["rule_id"] for o in opts}
    assert [o["rule_code"] for o in opts] == sorted(o["rule_code"] for o in opts)
    assert {o["plan"] for o in opts} == {"CLAIM"}, "还没有 PI 用过：直接用当前版本"
    eff = ok(api.get("/api/rules/effective" + q(pi=pi), admin))
    assert eff.text("source") == "PICKED" and eff["rule_id"] == opts[0]["rule_id"]
    # 不能挑按客户绑定的规则来生成
    assert w.preview(bill, 1, rule_id=bound).code == "RULE_NOT_GENERAL"
    # 按客户仍然一个对象只能绑一条
    cust = uid("CU")
    body = m(
        rule_code=uid("RC"), rule_name="c", bind_scope="CUSTOMER", bind_value=cust, prefix="CB", base=10, seq_len=4
    )
    ok(api.post("/api/rules", body, admin))
    body = m(
        rule_code=uid("RC"), rule_name="c", bind_scope="CUSTOMER", bind_value=cust, prefix="CC", base=10, seq_len=4
    )
    assert api.post("/api/rules", body, admin).code == "RULE_BIND_EXISTS"


def test_assign_rule_to_pi_then_unbind_and_pick_again(api, admin, w, sql):
    tpl_a, tpl_b = _general(api, admin, "ASA"), _general(api, admin, "ASB")
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, ORG, uid("CU"), pi, ("A", 9))

    # 生成前就给 PI 指定规则：生成默认用它，不再要求选择
    r = ok(api.put("/api/rules/pi-binding", m(pi_no=pi, rule_id=tpl_b), admin))
    assert r["rule"]["rule_id"] == tpl_b and r["rule"]["source"] == "CHOSEN"
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin))
    assert ctx["rule"]["rule_id"] == tpl_b and ctx["rule"]["source"] == "CHOSEN"
    w.generate(bill, 2)
    assert ok(w.preview(bill, 1)).text("start_sn") == "ASB0003"

    # 解绑：重新出现可选规则，必须重新选；改选另一条后流水接续、换成新格式
    r = ok(api.delete("/api/rules/pi-binding" + q(pi=pi), admin))
    assert r["rule"] is None
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin))
    assert ctx["rule"] is None and tpl_a in {o["rule_id"] for o in ctx["rule_options"]}
    assert w.preview(bill, 1).code == "RULE_CHOICE_REQUIRED"
    w.generate(bill, 1, rule_id=tpl_a)
    assert sql.count("SELECT COUNT(*) FROM sn_pi_counter WHERE pi_no = %s AND rule_id = %s", pi, tpl_a) == 1
    assert ok(w.preview(bill, 1)).text("start_sn") == "ASA0004"

    # 直接改指定为另一条
    ok(api.put("/api/rules/pi-binding", m(pi_no=pi, rule_id=tpl_b), admin))
    assert ok(w.preview(bill, 1)).text("start_sn") == "ASB0004"

    # 不能指定按客户绑定的规则
    cr = ok(
        api.post(
            "/api/rules",
            m(
                rule_code=uid("RC"),
                rule_name="c",
                bind_scope="CUSTOMER",
                bind_value=uid("CU"),
                prefix="CU",
                base=10,
                seq_len=4,
            ),
            admin,
        )
    )["rule"]["id"]
    assert api.put("/api/rules/pi-binding", m(pi_no=pi, rule_id=cr), admin).code == "RULE_NOT_GENERAL"
    assert api.put("/api/rules/pi-binding", m(pi_no=pi, rule_id=999999999), admin).code == "RULE_NOT_FOUND"
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'RULE_PI_BIND' AND pi_no = %s", pi) == 2
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'RULE_PI_UNBIND' AND pi_no = %s", pi) == 1


def test_rule_binding_can_be_changed_and_unbound(api, admin, w):
    pi, bill, cust = uid("PI"), uid("MO"), uid("CU")
    w.order(bill, ORG, cust, pi, ("A", 5))
    rid = ok(
        api.post(
            "/api/rules",
            m(rule_code=uid("RB"), rule_name="rb", bind_scope="GENERAL", prefix="RB", base=10, seq_len=4),
            admin,
        )
    )["rule"]["id"]
    spec = dict(rule_name="rb", prefix="RB", base=10, seq_len=4)
    # 通用 → 按客户：该客户的 PI 生成默认用它
    r = ok(api.put(f"/api/rules/{rid}", m(bind_scope="CUSTOMER", bind_value=cust, **spec), admin))
    assert r.text("action") == "REBOUND"
    assert r["rule"]["rule"]["bind_scope"] == "CUSTOMER" and r["rule"]["rule"]["bind_value"] == cust
    eff = ok(api.get("/api/rules/effective" + q(pi=pi), admin))
    assert eff["rule_id"] == rid and eff.text("source") == "CUSTOMER"
    # 不传绑定方式：绑定不变
    assert ok(api.put(f"/api/rules/{rid}", m(**spec), admin)).text("action") == "UNCHANGED"
    # 按客户 → 按 PI
    r = ok(api.put(f"/api/rules/{rid}", m(bind_scope="PI", bind_value=pi, **spec), admin))
    assert ok(api.get("/api/rules/effective" + q(pi=pi), admin)).text("source") == "PI"
    # 同一对象已有规则时不能再绑
    other = ok(
        api.post(
            "/api/rules",
            m(rule_code=uid("RB"), rule_name="o", bind_scope="GENERAL", prefix="O", base=10, seq_len=4),
            admin,
        )
    )["rule"]["id"]
    body = m(bind_scope="PI", bind_value=pi, rule_name="o", prefix="O", base=10, seq_len=4)
    assert api.put(f"/api/rules/{other}", body, admin).code == "RULE_BIND_EXISTS"
    assert api.put(f"/api/rules/{rid}", m(bind_scope="PI", bind_value="", **spec), admin).code == "RULE_BIND_REQUIRED"
    # 解绑（改回通用）：该 PI 又要选规则
    r = ok(api.put(f"/api/rules/{rid}", m(bind_scope="GENERAL", bind_value="x", **spec), admin))
    assert r["rule"]["rule"]["bind_scope"] == "GENERAL" and r["rule"]["rule"]["bind_value"] == ""
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin))
    assert ctx["rule"] is None and rid in {o["rule_id"] for o in ctx["rule_options"]}


def test_rule_library_only_categorizes(api, admin, w):
    """规则库只归类：同一客户 / PI 下可以有多条，都不生效（仍是通用规则），生成页可按名称找到并选用。"""
    pi, bill, cust = uid("PI"), uid("MO"), uid("CU")
    w.order(bill, ORG, cust, pi, ("A", 5))

    def make(prefix, **cat):
        body = m(rule_code=uid("RK"), rule_name="归类" + prefix, prefix=prefix, base=10, seq_len=4, **cat)
        return ok(api.post("/api/rules", body, admin)).body

    a = make("CTGA", category_scope="CUSTOMER", category_value=cust)
    b = make("CTGB", category_scope="CUSTOMER", category_value=cust)
    c = make("CTGC")
    assert (a["rule"]["bind_scope"], a["rule"]["category_scope"], a["rule"]["category_value"]) == (
        "GENERAL",
        "CUSTOMER",
        cust,
    )
    assert (c["rule"]["category_scope"], c["rule"]["category_value"]) == ("NONE", "")
    # 归类对客户的 PI 不生效，生成页仍要选；选项里带归类
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin))
    assert ctx["rule"] is None
    opts = {o["rule_id"]: o for o in ctx["rule_options"]}
    assert opts[a["rule"]["id"]]["category_value"] == cust and opts[c["rule"]["id"]]["category_scope"] == "NONE"
    # 按归类筛选
    got = ok(api.get("/api/rules" + q(category="CUSTOMER", category_value=cust), admin))
    assert {r["rule"]["id"] for r in got} == {a["rule"]["id"], b["rule"]["id"]}
    assert cust in {r["rule"]["category_value"] for r in ok(api.get("/api/rules" + q(q=cust), admin))}
    # 改归类：不出版本；不传归类时不变
    spec = dict(rule_name=c["rule"]["rule_name"], prefix=c["current"]["prefix"], base=10, seq_len=4)
    r = ok(api.put(f"/api/rules/{c['rule']['id']}", m(category_scope="PI", category_value=pi, **spec), admin))
    assert r.text("action") == "RECATEGORIZED" and r["rule"]["rule"]["current_version"] == 1
    assert r["rule"]["rule"]["bind_scope"] == "GENERAL"
    assert ok(api.put(f"/api/rules/{c['rule']['id']}", m(**spec), admin)).text("action") == "UNCHANGED"
    r = ok(api.put(f"/api/rules/{c['rule']['id']}", m(category_scope="NONE", category_value="x", **spec), admin))
    assert (r["rule"]["rule"]["category_scope"], r["rule"]["rule"]["category_value"]) == ("NONE", "")
    # 校验
    bad = m(rule_code=uid("RK"), rule_name="x", prefix="CTGX", base=10, seq_len=4, category_scope="PI")
    assert api.post("/api/rules", bad, admin).code == "RULE_CATEGORY_REQUIRED"
    bad = m(rule_code=uid("RK"), rule_name="x", prefix="CTGY", base=10, seq_len=4, category_scope="FOO")
    assert api.post("/api/rules", bad, admin).code == "RULE_CATEGORY_INVALID"


def test_delete_rule_only_when_unused_and_unassigned(api, admin, w, sql):
    def new_rule(prefix: str) -> tuple[int, str]:
        code = uid("RD")
        rid = ok(
            api.post(
                "/api/rules",
                m(rule_code=code, rule_name="rd", bind_scope="GENERAL", prefix=prefix, base=10, seq_len=4),
                admin,
            )
        )["rule"]["id"]
        return rid, code

    # 没用过：连同全部版本删除，并留痕
    rid, code = new_rule(uid("DA").replace("-", "_"))
    assert ok(api.delete(f"/api/rules/{rid}", admin)).text("rule_code") == code
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE id = %s", rid) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_rule_version WHERE rule_id = %s", rid) == 0
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_audit WHERE action = 'RULE_DELETE' AND result = 'OK' AND detail LIKE %s",
            code + " %",
        )
        == 1
    )
    assert api.delete(f"/api/rules/{rid}", admin).code == "RULE_NOT_FOUND"

    # 已指定给 PI：先解除指定才能删
    pi = uid("PI")
    rid, code = new_rule(uid("DB").replace("-", "_"))
    ok(api.put("/api/rules/pi-binding", m(pi_no=pi, rule_id=rid), admin))
    assert api.delete(f"/api/rules/{rid}", admin).code == "RULE_ASSIGNED"
    ok(api.delete("/api/rules/pi-binding" + q(pi=pi), admin))
    ok(api.delete(f"/api/rules/{rid}", admin))

    # 已生成过号：不能删；失败也留痕
    pi, bill, prefix = uid("PI"), uid("MO"), uid("DC").replace("-", "_")
    rid = ok(
        api.post(
            "/api/rules",
            m(rule_code=uid("RD"), rule_name="rd", bind_scope="PI", bind_value=pi, prefix=prefix, base=10, seq_len=4),
            admin,
        )
    )["rule"]["id"]
    w.order(bill, ORG, "C1", pi, ("A", 3))
    w.generate(bill, 1)
    assert api.delete(f"/api/rules/{rid}", admin).code == "RULE_IN_USE"
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE id = %s", rid) == 1
    code = sql.one("SELECT rule_code FROM sn_rule WHERE id = %s", rid)["rule_code"]
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_audit WHERE action = 'RULE_DELETE' AND result = 'FAIL' AND error_msg LIKE %s",
            "%" + code + "%",
        )
        == 1
    )
