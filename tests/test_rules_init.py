"""规则版本、起始号与历史导入、分区与痕迹归档。（对应 RuleInitIT）"""

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


def test_pi_init_start_and_history_import(api, admin, w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "H", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 10))
    assert api.post("/api/pi-init", m(pi_no=pi, sns=["H00010", "H00010"]), admin).code == "PI_IMPORT_DUP_IN_LIST"
    out = ok(
        api.post(
            "/api/pi-init",
            m(pi_no=pi, start_seq=100, factory_code=FAC, sns=["H00120", "H00005", "LEGACY-XYZ", ""]),
            admin,
        )
    ).body
    assert out["imported_qty"] == 3
    assert out["decoded_qty"] == 2
    assert out["last_seq_dec"] == 120
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PRINTED' AND source = 'IMPORT'", pi) == 3
    )
    assert api.post("/api/pi-init", m(pi_no=pi, start_seq=1), admin).code == "PI_INIT_LOCKED"
    assert w.preview(bill, 1, 500).code == "GEN_START_NOT_ALLOWED"
    assert w.generate(bill, 1)["start_sn"] == "H00121"
    st = ok(api.get("/api/pi-init" + q(pi=pi), admin)).body
    assert st["start_allowed"] is False
    assert len(st["imports"]) == 1


def test_imported_sn_participates_in_duplicate_check(api, admin, w, sql):
    pi, bill = uid("PI"), uid("MO")
    w.pi_rule(pi, "I", 10, 5)
    w.order(bill, ORG, "C1", pi, ("A", 10))
    ok(api.post("/api/pi-init", m(pi_no=pi, sns=["LEGACY-1"]), admin))
    pi2 = uid("PI")
    ok(api.post("/api/pi-init", m(pi_no=pi2, sns=["LEGACY-1"]), admin))
    assert sql.count("SELECT COUNT(*) FROM sn_key WHERE sn = 'LEGACY-1' AND pi_no = %s", pi) == 1, (
        "same SN allowed in a different PI"
    )
    assert api.post("/api/pi-init", m(pi_no=uid("PI")), admin).code == "PI_INIT_EMPTY"


def test_partitions_maintained_and_old_audit_archived(app_client, sql):
    from app.services import partitions

    partitions.maintain()
    partitions.maintain()
    item_parts = sql.count(
        "SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = DATABASE() "
        "AND TABLE_NAME = 'sn_item' AND PARTITION_NAME LIKE 'p2%%'"
    )
    assert item_parts >= 4, "current + 3 months ahead"
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


def test_pi_init_rejects_over_long_pi_instead_of_crashing(api, admin, sql):
    pi = "P" * 80
    r = api.post("/api/pi-init", m(pi_no=pi, start_seq=5), admin)
    assert r.status == 422 and r.code == "VALIDATION", str(r)
    # 不能被截断成 64 位写进计数器
    assert sql.count("SELECT COUNT(*) FROM sn_pi_counter WHERE pi_no = %s", pi[:64]) == 0
    assert api.post("/api/pi-init", m(pi_no="P" * 64, start_seq=5), admin).status == 200


def _template(api, admin, prefix: str) -> int:
    """借一条按其它客户绑定的规则当模板，返回规则 id。"""
    r = ok(
        api.post(
            "/api/rules",
            m(
                rule_code=uid("TP"),
                rule_name="模板" + prefix,
                bind_scope="CUSTOMER",
                bind_value=uid("OTHER"),
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
    # 升级前生成过的 PI 没有选定记录：沿用最近一次成功生成所用的规则
    sql.exec("UPDATE sn_pi_counter SET rule_id = NULL WHERE pi_no = %s", pi)
    assert ok(api.get("/api/generate/context" + q(bill_no=bill), admin))["rule"]["rule_id"] == tpl_a
    sql.exec("UPDATE sn_pi_counter SET rule_id = %s WHERE pi_no = %s", tpl_a, pi)
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
    token = ok(w.preview(bill1, 1, rule_id=tpl_b)).text("token")
    # 同 PI 另一张订单先以另一条规则生成：这张 PI 已选定 A，按 B 做的预演失效
    w.generate(bill2, 1, rule_id=tpl_a)
    r = api.post("/api/generate", m(preview_token=token, bill_no=bill1, qty=1), admin)
    assert r.code in ("GEN_PREVIEW_CHANGED", "GEN_PREVIEW_STALE"), str(r)


def test_multiple_general_rules_all_offered_to_unbound_pi(api, admin, w):
    codes = [uid("GEN"), uid("GEN")]
    ids = [
        ok(
            api.post(
                "/api/rules",
                m(
                    rule_code=c,
                    rule_name="通用" + c,
                    bind_scope="GENERAL",
                    bind_value="x",
                    prefix="G",
                    base=10,
                    seq_len=4,
                ),
                admin,
            )
        )["rule"]["id"]
        for c in codes
    ]
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, ORG, uid("CU"), pi, ("A", 5))
    opts = ok(api.get("/api/generate/context" + q(bill_no=bill), admin))["rule_options"]
    scopes = [o["bind_scope"] for o in opts]
    assert set(ids) <= {o["rule_id"] for o in opts if o["bind_scope"] == "GENERAL"}
    # 通用规则全部排在其它规则前面
    assert scopes == sorted(scopes, key=lambda s: s != "GENERAL")
    assert all(o["bind_value"] == "" for o in opts if o["bind_scope"] == "GENERAL")
    assert ok(api.get("/api/rules/effective" + q(pi=pi), admin)).text("source") == "GENERAL"
    # 按客户仍然一个对象只能绑一条
    cust = uid("CU")
    body = m(rule_code=uid("RC"), rule_name="c", bind_scope="CUSTOMER", bind_value=cust, base=10, seq_len=4)
    ok(api.post("/api/rules", body, admin))
    body = m(rule_code=uid("RC"), rule_name="c", bind_scope="CUSTOMER", bind_value=cust, base=10, seq_len=4)
    assert api.post("/api/rules", body, admin).code == "RULE_BIND_EXISTS"
