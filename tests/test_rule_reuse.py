"""SN 全局不重：模板被多张 PI 沿用时各用一版（后缀加 _v版本号）、生成页手动设置规则、解绑与前缀冲突。"""

import pytest
from conftest import m, ok, q, uid

ORG = "沿用测试厂"
FAC = "F-REUSE"


@pytest.fixture(autouse=True)
def setup(w, once):
    if "reuse" not in once:
        once["reuse"] = True
        w.factory(FAC, ORG)


def _rule(api, admin, prefix: str, suffix: str = "", scope: str = "GENERAL", bind: str = "", seq_len: int = 4) -> int:
    body = m(
        rule_code=uid("RU"),
        rule_name="模板" + prefix,
        bind_scope=scope,
        bind_value=bind,
        prefix=prefix,
        suffix=suffix,
        base=10,
        seq_len=seq_len,
    )
    return ok(api.post("/api/rules", body, admin))["rule"]["id"]


def _pi(w, qty: int = 20, customer: str | None = None) -> tuple[str, str]:
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, ORG, customer or uid("CU"), pi, ("A", qty))
    return pi, bill


def _gen(w, pi: str, qty: int, rule_id: int | None = None) -> tuple[str, str]:
    j = w.generate_pi(pi, qty, rule_id)["jobs"][0]
    return j["start_sn"], j["end_sn"]


def _plan(api, admin, pi: str, rule_id: int | None = None, manual: dict | None = None):
    return api.post("/api/generate/rule-plan", m(pi_no=pi, rule_id=rule_id, manual=manual), admin)


def test_template_reused_by_other_pis_gets_a_derived_version_each(api, admin, w, sql):
    tpl = _rule(api, admin, "TR", "K")
    pi1, _ = _pi(w)
    pi2, _ = _pi(w)
    pi3, _ = _pi(w)

    assert ok(_plan(api, admin, pi1, tpl))["rule"]["plan"] == "CLAIM"
    assert _gen(w, pi1, 2, tpl) == ("TR0001K", "TR0002K")

    # 已被 PI1 用过：PI2 另出 v2，后缀加 _v2，流水从本 PI 自己的 1 开始
    p = ok(_plan(api, admin, pi2, tpl))["rule"]
    assert (p["plan"], p["version"], p["suffix"], p["shared_by"]) == ("DERIVE", 2, "K_v2", pi1)
    assert p["start_sn"] == "TR0001K_v2"
    opt = next(
        o for o in ok(api.get("/api/generate/pi-context" + q(pi=pi2), admin))["rule_options"] if o["rule_id"] == tpl
    )
    assert (opt["plan"], opt["sample_sn"]) == ("DERIVE", "TR0001K_v2")
    assert _gen(w, pi2, 3, tpl) == ("TR0001K_v2", "TR0003K_v2")
    assert _gen(w, pi3, 1, tpl) == ("TR0001K_v3", "TR0001K_v3")

    # 各 PI 固定用自己的版本接着排；规则的当前版本不变
    assert _gen(w, pi1, 1) == ("TR0003K", "TR0003K")
    assert _gen(w, pi2, 1) == ("TR0004K_v2", "TR0004K_v2")
    assert sql.count("SELECT current_version FROM sn_rule WHERE id = %s", tpl) == 1
    vs = sql.all("SELECT version, suffix, claimed_pi, derived_from FROM sn_rule_version WHERE rule_id = %s", tpl)
    by_v = {v["version"]: v for v in vs}
    assert (by_v[1]["claimed_pi"], by_v[1]["derived_from"]) == (pi1, None)
    assert (by_v[2]["suffix"], by_v[2]["claimed_pi"]) == ("K_v2", pi2)
    assert (by_v[3]["suffix"], by_v[3]["claimed_pi"]) == ("K_v3", pi3)
    assert sql.count("SELECT COUNT(*) FROM sn_item") == sql.count("SELECT COUNT(DISTINCT sn) FROM sn_item")


def test_template_without_suffix_gets_v_suffix_and_edit_re_derives(api, admin, w):
    tpl = _rule(api, admin, "NS")
    pi1, _ = _pi(w)
    pi2, _ = _pi(w)
    assert _gen(w, pi1, 1, tpl) == ("NS0001", "NS0001")
    assert _gen(w, pi2, 1, tpl) == ("NS0001_v2", "NS0001_v2")

    # 改了参数（已用过 → 另出 v3）：PI1 改用 v3；PI2 在 v3 上另出 v4
    r = ok(api.put(f"/api/rules/{tpl}", m(rule_name="模板NS", prefix="NS", base=10, seq_len=5), admin))
    assert r["action"] == "NEW_VERSION" and r["rule"]["rule"]["current_version"] == 3
    assert _gen(w, pi1, 1) == ("NS00002", "NS00002")
    assert _gen(w, pi2, 1) == ("NS00002_v4", "NS00002_v4")


def test_customer_rule_shared_by_its_pis_and_pi_can_pick_its_own(api, admin, w, sql):
    cust = uid("CU")
    cr = _rule(api, admin, "CQ", scope="CUSTOMER", bind=cust)
    tpl = _rule(api, admin, "OWN")
    pi1, _ = _pi(w, customer=cust)
    pi2, _ = _pi(w, customer=cust)
    assert _gen(w, pi1, 1) == ("CQ0001", "CQ0001")
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi2), admin))
    assert (ctx["rule"]["source"], ctx["rule"]["plan"], ctx["rule"]["suffix"]) == ("CUSTOMER", "DERIVE", "_v2")
    assert tpl in {o["rule_id"] for o in ctx["rule_options"]}, "按客户绑定时也可为本 PI 单独选模板"
    assert cr not in {o["rule_id"] for o in ctx["rule_options"]}
    assert _gen(w, pi2, 1) == ("CQ0001_v2", "CQ0001_v2")

    # 为本 PI 单独指定模板后优先于按客户
    r = ok(api.put("/api/rules/pi-binding", m(pi_no=pi2, rule_id=tpl), admin))
    assert r["rule"]["source"] == "CHOSEN" and r["rule"]["rule_id"] == tpl
    assert _gen(w, pi2, 1) == ("OWN0002", "OWN0002"), "流水接续，换成新格式"
    r = ok(api.delete("/api/rules/pi-binding" + q(pi=pi2), admin))
    assert r["rule"]["source"] == "CUSTOMER"

    # 生成时直接选模板 / 手动设置也能覆盖按客户绑定的规则
    pi3, _ = _pi(w, customer=cust)
    assert _gen(w, pi3, 1, tpl) == ("OWN0001_v2", "OWN0001_v2")
    assert ok(api.get("/api/rules/effective" + q(pi=pi3), admin)).text("source") == "CHOSEN"
    pi4, _ = _pi(w, customer=cust)
    pv = ok(api.post("/api/generate/pi-preview", m(pi_no=pi4, qty=1, manual=m(prefix="MC", seq_len=3)), admin))
    assert pv.text("start_sn") == "MC001"
    assert sql.count("SELECT COUNT(*) FROM sn_item") == sql.count("SELECT COUNT(DISTINCT sn) FROM sn_item")


def test_manual_rule_is_saved_after_generation_and_unbinds_to_template(api, admin, w, sql):
    pi, _ = _pi(w)
    manual = m(prefix="MX", seq_len=5, suffix="")
    p = ok(_plan(api, admin, pi, manual=manual))["rule"]
    assert (p["plan"], p["source"], p["rule_name"], p["start_sn"]) == ("NEW", "MANUAL", f"{pi}_v1", "MX00001")
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE bind_value = %s", pi) == 0, "只判断，不落库"

    pv = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=2, manual=manual), admin)).body
    assert pv["rule"]["plan"] == "NEW"
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE bind_value = %s", pi) == 0, "预演不落库"
    items = [m(preview_token=x["token"], bill_no=x["bill_no"], qty=x["qty"]) for x in pv["items"]]
    g = ok(api.post("/api/generate/pi", m(pi_no=pi, items=items), admin)).body
    assert (g["jobs"][0]["start_sn"], g["jobs"][0]["end_sn"]) == ("MX00001", "MX00002")
    rule = sql.one("SELECT * FROM sn_rule WHERE bind_scope = 'PI' AND bind_value = %s", pi)
    assert (rule["rule_code"], rule["rule_name"]) == (f"PI-{pi}", f"{pi}_v1")
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi), admin))
    assert (ctx["rule"]["source"], ctx["rule"]["rule_name"]) == ("PI", f"{pi}_v1")
    assert ctx["next"]["start_sn"] == "MX00003"

    # 已绑定后再传手动参数也沿用已绑定的规则
    assert (
        ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=1, manual=m(prefix="ZZ", seq_len=3)), admin))[
            "start_sn"
        ]
        == "MX00003"
    )

    # 解绑：转为通用规则，可作模板；别的 PI 用它时另出派生版
    r = ok(api.delete("/api/rules/pi-binding" + q(pi=pi), admin))
    assert r["rule"] is None
    assert sql.one("SELECT bind_scope FROM sn_rule WHERE id = %s", rule["id"])["bind_scope"] == "GENERAL"
    other, _ = _pi(w)
    assert _gen(w, other, 1, rule["id"]) == ("MX00001_v2", "MX00001_v2")

    # 同一张 PI 再手动设置一条：序号递增
    p2 = ok(_plan(api, admin, pi, manual=m(prefix="MY", seq_len=5)))["rule"]
    assert (p2["rule_code"], p2["rule_name"]) == (f"PI-{pi}-2", f"{pi}_v2")


def test_manual_rule_validation_and_no_rule_left_when_generation_fails(api, admin, w, sql):
    _rule(api, admin, "VA")
    pi, _ = _pi(w)
    assert _plan(api, admin, pi, manual=m(prefix="", seq_len=4)).code == "RULE_PREFIX_REQUIRED"
    assert _plan(api, admin, pi, manual=m(prefix="A-B", seq_len=4)).code == "RULE_AFFIX_INVALID"
    assert _plan(api, admin, pi, manual=m(prefix="VB", seq_len=4, suffix="#")).code == "RULE_AFFIX_INVALID"
    for prefix in ("VA", "V", "VAX"):
        r = _plan(api, admin, pi, manual=m(prefix=prefix, seq_len=4))
        assert r.code == "RULE_PREFIX_CONFLICT", prefix
        assert r["params"]["other"] == "VA"
    assert _plan(api, admin, pi, manual=m(prefix="VB", seq_len=13)).code == "RULE_SEQ_LEN_INVALID"
    assert ok(_plan(api, admin, pi, manual=m(prefix="V_B", seq_len=4, suffix="_x")))["rule"]["sample_sn"] == "V_B0001_x"

    # 生成失败（撞上库里已有的号）：整批回滚，手动规则也不留
    other = uid("PI")
    sql.exec(
        "INSERT INTO sn_item(sn, pi_no, seq_pi_no, seq_dec, status, created_at, updated_at) "
        "VALUES ('WB0002', %s, %s, 1, 'PRINTED', NOW(), NOW())",
        other,
        other,
    )
    pv = ok(api.post("/api/generate/pi-preview", m(pi_no=pi, qty=3, manual=m(prefix="WB", seq_len=4)), admin)).body
    items = [m(preview_token=x["token"], bill_no=x["bill_no"], qty=x["qty"]) for x in pv["items"]]
    r = api.post("/api/generate/pi", m(pi_no=pi, items=items), admin)
    assert r.code == "GEN_DUPLICATE_SN" and r["params"]["pi"] == other, str(r)
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE bind_value = %s", pi) == 0


def test_admin_rules_need_a_prefix_not_overlapping_others(api, admin):
    _rule(api, admin, "PX")
    body = m(rule_code=uid("RU"), rule_name="x", bind_scope="GENERAL", base=10, seq_len=4)
    assert api.post("/api/rules", {**body, "prefix": ""}, admin).code == "RULE_PREFIX_REQUIRED"
    for prefix in ("P", "PX", "PXY"):
        assert api.post("/api/rules", {**body, "prefix": prefix}, admin).code == "RULE_PREFIX_CONFLICT", prefix
    rid = ok(api.post("/api/rules", {**body, "prefix": "PY"}, admin))["rule"]["id"]
    # 改自己的参数不和自己冲突；改成别人的前缀不行
    ok(api.put(f"/api/rules/{rid}", m(rule_name="x", prefix="PY", suffix="S", base=10, seq_len=4), admin))
    r = api.put(f"/api/rules/{rid}", m(rule_name="x", prefix="PXZ", base=10, seq_len=4), admin)
    assert r.code == "RULE_PREFIX_CONFLICT"
    # 只改名不校验前缀
    assert (
        ok(api.put(f"/api/rules/{rid}", m(rule_name="y", prefix="PY", suffix="S", base=10, seq_len=4), admin))["action"]
        == "RENAMED"
    )


def test_customer_binding_can_be_removed_on_generate_page(api, admin, w, sql):
    """规则库不管绑定：按客户绑定的规则在生成页解除，转为通用规则（该客户全部 PI 都解除），归类不变。"""
    cust = uid("CU")
    cr = _rule(api, admin, "CUB", scope="CUSTOMER", bind=cust)
    pi1, _ = _pi(w, customer=cust)
    pi2, _ = _pi(w, customer=cust)
    assert ok(api.get("/api/rules/effective" + q(pi=pi1), admin)).text("source") == "CUSTOMER"
    r = ok(api.delete("/api/rules/pi-binding" + q(pi=pi1), admin))
    assert r["rule"] is None
    row = sql.one("SELECT * FROM sn_rule WHERE id = %s", cr)
    assert (row["bind_scope"], row["bind_value"]) == ("GENERAL", "")
    assert (row["category_scope"], row["category_value"]) == ("CUSTOMER", cust), "归类不变"
    ctx = ok(api.get("/api/generate/pi-context" + q(pi=pi2), admin))
    assert ctx["rule"] is None and cr in {o["rule_id"] for o in ctx["rule_options"]}
