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
