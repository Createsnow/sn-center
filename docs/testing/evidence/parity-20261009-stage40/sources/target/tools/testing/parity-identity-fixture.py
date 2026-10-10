"""Two owned PIs and three synthetic bills for comparison UI identity tests only."""

import argparse
import importlib.util
import json
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
PIS = ("CMP-UI-ID-PI-A", "CMP-UI-ID-PI-B")
BILLS = ("CMP-UI-ID-MO", "CMP-UI-ID-SIBLING", "CMP-UI-ID-UNUSED")
RULES = ("CMP_UI_ID_A", "CMP_UI_ID_B")
PREFIXES = dict(zip(PIS, ("IDA", "IDB"), strict=True))
SOURCE_IDS = dict(
    zip(
        BILLS, ("9007199254771001", "9007199254771002", "9007199254771003"), strict=True
    )
)
spec = importlib.util.spec_from_file_location(
    "generation_fixture", Path(__file__).with_name("parity-generation-fixture.py")
)
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


def inventory(c, java):
    """只排除声明的两个 PI / 三张单 / 两个规则，其余逐字段摘要保护。"""
    pi = "pi" if java else "pi_no"
    code = "code" if java else "rule_code"
    queries = {
        "items": (f"SELECT * FROM sn_item WHERE {pi} NOT IN (%s,%s)", PIS),
        "keys": (f"SELECT * FROM sn_key WHERE {pi} NOT IN (%s,%s)", PIS),
        "counters": (f"SELECT * FROM sn_pi_counter WHERE {pi} NOT IN (%s,%s)", PIS),
        "rules": (f"SELECT * FROM sn_rule WHERE {code} NOT IN (%s,%s)", RULES),
        "versions": (
            f"SELECT v.* FROM sn_rule_version v JOIN sn_rule r ON r.id=v.rule_id WHERE r.{code} NOT IN (%s,%s)",
            RULES,
        ),
        "allocations": (
            "SELECT * FROM sn_work WHERE kind='ALLOCATE' AND pi NOT IN (%s,%s)"
            if java
            else "SELECT * FROM sn_allocation WHERE pi_no NOT IN (%s,%s)",
            PIS,
        ),
        "ledger": (
            "SELECT q.* FROM sn_source_entry_quota q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no NOT IN (%s,%s,%s)"
            if java
            else "SELECT * FROM sn_bill_gen WHERE bill_no NOT IN (%s,%s,%s)",
            BILLS,
        ),
    }
    if java:
        queries["generation_identity"] = (
            "SELECT g.* FROM sn_bill_generation g JOIN sn_order_bill b ON b.id=g.bill_id WHERE b.bill_no NOT IN (%s,%s,%s)",
            BILLS,
        )
        queries["choices"] = (
            "SELECT * FROM sn_pi_rule_choice WHERE pi NOT IN (%s,%s)",
            PIS,
        )
    result = {}
    for key, (sql, args) in queries.items():
        c.execute(sql, args)
        result[key] = common.digest(c.fetchall())
    return result


def observe(c, java):
    result = {}
    queries = {
        "items": (
            "SELECT i.sn,i.pi,i.source_pi,i.seq_dec,i.customer,i.status,f.code factory_code FROM sn_item i LEFT JOIN sn_factory f ON f.id=i.factory_id WHERE i.pi IN (%s,%s) ORDER BY i.sn"
            if java
            else "SELECT sn,pi_no pi,seq_pi_no source_pi,seq_dec,customer_code customer,status,factory_code FROM sn_item WHERE pi_no IN (%s,%s) ORDER BY sn",
            PIS,
        ),
        "allocations": (
            "SELECT w.pi,JSON_UNQUOTE(JSON_EXTRACT(w.payload,'$.customer')) customer,w.first_sn start_sn,w.last_sn end_sn,w.quantity qty,f.code factory_code FROM sn_work w JOIN sn_factory f ON f.id=w.factory_id WHERE w.pi IN (%s,%s) AND w.kind='ALLOCATE' AND w.state='SUCCESS' ORDER BY w.created_at,w.task_id"
            if java
            else "SELECT a.pi_no pi,g.customer_code customer,a.start_sn,a.end_sn,a.qty,a.factory_code FROM sn_allocation a JOIN sn_bill_gen g ON g.bill_no=a.bill_no WHERE a.pi_no IN (%s,%s) ORDER BY a.id",
            PIS,
        ),
        "ledger": (
            "SELECT b.bill_no,g.first_pi pi,g.first_customer customer,f.code factory_code FROM sn_bill_generation g JOIN sn_order_bill b ON b.id=g.bill_id JOIN sn_factory f ON f.id=g.factory_id WHERE g.first_pi IN (%s,%s) ORDER BY b.bill_no"
            if java
            else "SELECT bill_no,pi_no pi,customer_code customer,factory_code FROM sn_bill_gen WHERE pi_no IN (%s,%s) ORDER BY bill_no",
            PIS,
        ),
    }
    for key, (sql, args) in queries.items():
        c.execute(sql, args)
        result[key] = c.fetchall()
    return result


def validate_owned(c, java):
    """先完成所有所有权/下游/任务检查，再开始删除；异常时整个事务回滚。"""
    pi = "pi" if java else "pi_no"
    code = "code" if java else "rule_code"
    c.execute(f"SELECT * FROM sn_rule WHERE {code} IN (%s,%s)", RULES)
    rules = c.fetchall()
    if len(rules) > 2 or any(
        r["bind_scope"] != "PI" or r["bind_value"] != PIS[RULES.index(r[code])]
        for r in rules
    ):
        raise RuntimeError("Rule ownership changed")
    c.execute(f"SELECT * FROM sn_item WHERE {pi} IN (%s,%s)", PIS)
    items = c.fetchall()
    if len(items) > 30 or any(
        r["source"] != "GEN"
        or r["bill_no"] != BILLS[0]
        or r["status"] not in ("PENDING_ALLOC", "TO_ACQUIRE")
        or r["seq_dec"] is None
        or not 1 <= r["seq_dec"] <= 30
        or r["sn"] != f"{PREFIXES[r[pi]]}{r['seq_dec']:05}"
        or r["source_pi" if java else "seq_pi_no"] != r[pi]
        for r in items
    ):
        raise RuntimeError("Unexpected owned members; preserving all data")
    c.execute(
        "SELECT code FROM sn_factory WHERE id IN (SELECT factory_id FROM sn_item WHERE pi IN (%s,%s) AND status='TO_ACQUIRE')"
        if java
        else "SELECT DISTINCT factory_code code FROM sn_item WHERE pi_no IN (%s,%s) AND status='TO_ACQUIRE'",
        PIS,
    )
    if any(r["code"] != "CMP-A" for r in c.fetchall()):
        raise RuntimeError("Identity fixture only allocates to CMP-A")
    c.execute(
        "SELECT w.*,b.bill_no FROM sn_work w JOIN sn_order_bill b ON b.id=w.bill_id WHERE w.pi IN (%s,%s) AND w.kind='ALLOCATE'"
        if java
        else "SELECT * FROM sn_allocation WHERE pi_no IN (%s,%s)",
        PIS,
    )
    allocations = c.fetchall()
    if any(
        r["bill_no"] != BILLS[0]
        or r[pi] != PIS[0]
        or (java and r["state"] != "SUCCESS")
        for r in allocations
    ):
        raise RuntimeError("Unexpected allocation identity or state")
    allocated = [r for r in items if r["status"] == "TO_ACQUIRE"]
    if sum(r["quantity" if java else "qty"] for r in allocations) != len(allocated):
        raise RuntimeError("Allocation ledger does not match actual members")
    if java and any(
        r["allocation_id"] not in {a["task_id"] for a in allocations} for r in allocated
    ):
        raise RuntimeError("Foreign allocation references")
    c.execute(
        "SELECT w.state,w.kind,b.bill_no FROM sn_work w LEFT JOIN sn_order_bill b ON b.id=w.bill_id WHERE w.pi IN (%s,%s)"
        if java
        else "SELECT status state,bill_no FROM sn_gen_job WHERE pi_no IN (%s,%s)",
        PIS,
    )
    if any(
        r["bill_no"] != BILLS[0]
        or r["state"] in ("PENDING", "RUNNING")
        or (java and r["kind"] not in ("GENERATE", "ALLOCATE"))
        for r in c.fetchall()
    ):
        raise RuntimeError("Unexpected or active generation task")
    c.execute(
        "SELECT g.first_pi pi,g.first_customer customer,b.bill_no FROM sn_bill_generation g JOIN sn_order_bill b ON b.id=g.bill_id WHERE g.first_pi IN (%s,%s)"
        if java
        else "SELECT pi_no pi,customer_code customer,bill_no FROM sn_bill_gen WHERE pi_no IN (%s,%s)",
        PIS,
    )
    if any(
        r != {"pi": PIS[0], "customer": "CMP-ID-CUSTOMER-A", "bill_no": BILLS[0]}
        for r in c.fetchall()
    ):
        raise RuntimeError("Unexpected first generation identity")
    allowed = {
        "sn_item",
        "sn_key",
        "sn_pi_counter",
        "sn_pi_rule_choice",
        "sn_preview",
        "sn_work",
        "sn_order_bill",
        "sn_gen_preview",
        "sn_gen_job",
        "sn_bill_gen",
        "sn_bill_generation",
        "sn_allocation",
        "sn_prd_mo",
        "sn_audit",
        "sn_audit_archive",
    }
    c.execute(
        "SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('pi','pi_no','seq_pi','seq_pi_no','source_pi','first_pi','from_pi','to_pi')"
    )
    for ref in c.fetchall():
        if ref["TABLE_NAME"] not in allowed:
            c.execute(
                f"SELECT COUNT(*) n FROM `{ref['TABLE_NAME']}` WHERE `{ref['COLUMN_NAME']}` IN (%s,%s)",
                PIS,
            )
            if c.fetchone()["n"]:
                raise RuntimeError("Downstream reference exists; preserving data")
    c.execute(
        "SELECT * FROM sn_order_bill WHERE bill_no IN (%s,%s,%s)"
        if java
        else "SELECT bill_no FROM sn_prd_mo WHERE bill_no IN (%s,%s,%s)",
        BILLS,
    )
    heads = c.fetchall()
    if not java and heads:
        raise RuntimeError("Restore snapshot through UI before cleanup")
    if java and any(
        r["active"]
        or r["source_system"] != "K3"
        or r["source_account"] != "COMPARISON_SYNTHETIC"
        or r["source_bill_id"] != SOURCE_IDS[r["bill_no"]]
        or r["pi"] not in PIS
        for r in heads
    ):
        raise RuntimeError("Restore owned snapshot through UI before cleanup")
    return rules


def clean(c, java):
    rules = validate_owned(c, java)
    pi = "pi" if java else "pi_no"
    c.execute(f"DELETE FROM sn_key WHERE {pi} IN (%s,%s)", PIS)
    c.execute(f"DELETE FROM sn_item WHERE {pi} IN (%s,%s)", PIS)
    if java:
        c.execute(
            "DELETE j FROM sn_generation_job j JOIN sn_work w ON w.task_id=j.task_id WHERE w.pi IN (%s,%s)",
            PIS,
        )
        for table in ("sn_preview", "sn_work", "sn_pi_rule_choice", "sn_pi_counter"):
            c.execute(f"DELETE FROM {table} WHERE pi IN (%s,%s)", PIS)
        for table in ("sn_bill_generation", "sn_source_entry_quota", "sn_order_entry"):
            c.execute(
                f"DELETE q FROM {table} q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no IN (%s,%s,%s)",
                BILLS,
            )
        c.execute(
            "DELETE FROM sn_order_bill WHERE bill_no IN (%s,%s,%s) AND active=0", BILLS
        )
    else:
        for table in (
            "sn_gen_preview",
            "sn_gen_job",
            "sn_allocation",
            "sn_bill_gen",
            "sn_pi_counter",
        ):
            c.execute(f"DELETE FROM {table} WHERE pi_no IN (%s,%s)", PIS)
    for rule in rules:
        c.execute("DELETE FROM sn_rule_version WHERE rule_id=%s", (rule["id"],))
        c.execute("DELETE FROM sn_rule WHERE id=%s", (rule["id"],))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument("action", choices=("begin", "seed-rules", "observe", "clean"))
    args = parser.parse_args()
    java = args.kind == "java"
    snapshot = RUN / f"identity-fixture-{args.kind}.json"
    if args.action == "seed-rules":
        if not snapshot.exists():
            raise RuntimeError("Begin fixture ownership first")
        common.seed_rules(
            args.kind,
            [(rule, pi, PREFIXES[pi]) for rule, pi in zip(RULES, PIS, strict=True)],
        )
        return
    with (
        pymysql.connect(
            host="127.0.0.1",
            port=33308,
            user=args.kind + "_compare",
            password=(PRIVATE / ("mysql-" + args.kind)).read_text().strip(),
            database="sn_" + args.kind + "_compare",
            cursorclass=pymysql.cursors.DictCursor,
        ) as conn,
        conn.cursor() as c,
    ):
        pi = "pi" if java else "pi_no"
        if args.action == "begin":
            if snapshot.exists() or list(RUN.glob("generation-fixture-*.json")):
                raise RuntimeError("Previous fixture still requires cleanup")
            tables = (
                (
                    "sn_item",
                    "sn_key",
                    "sn_pi_counter",
                    "sn_work",
                    "sn_preview",
                    "sn_pi_rule_choice",
                )
                if java
                else (
                    "sn_item",
                    "sn_key",
                    "sn_pi_counter",
                    "sn_gen_job",
                    "sn_gen_preview",
                    "sn_bill_gen",
                    "sn_allocation",
                )
            )
            for table in tables:
                c.execute(f"SELECT COUNT(*) n FROM {table} WHERE {pi} IN (%s,%s)", PIS)
                if c.fetchone()["n"]:
                    raise RuntimeError("Unowned PI exists")
            if java:
                c.execute(
                    "SELECT COUNT(*) n FROM sn_bill_generation WHERE first_pi IN (%s,%s)",
                    PIS,
                )
                if c.fetchone()["n"]:
                    raise RuntimeError("Unowned generation identity exists")
            c.execute(
                "SELECT COUNT(*) n FROM sn_order_bill WHERE bill_no IN (%s,%s,%s)"
                if java
                else "SELECT COUNT(*) n FROM sn_prd_mo WHERE bill_no IN (%s,%s,%s)",
                BILLS,
            )
            if c.fetchone()["n"]:
                raise RuntimeError("Unowned bill exists")
            c.execute(
                "SELECT COUNT(*) n FROM sn_rule WHERE "
                + ("code" if java else "rule_code")
                + " IN (%s,%s) OR (bind_scope='PI' AND bind_value IN (%s,%s))",
                RULES + PIS,
            )
            if c.fetchone()["n"]:
                raise RuntimeError("Unowned rule exists")
            snapshot.write_text(
                json.dumps(inventory(c, java), indent=2), encoding="utf-8"
            )
        elif args.action == "observe":
            print(json.dumps(observe(c, java), default=str))
        else:
            before = json.loads(snapshot.read_text(encoding="utf-8"))
            if inventory(c, java) != before:
                raise RuntimeError("Original rows changed; preserving fixture")
            clean(c, java)
            if inventory(c, java) != before:
                raise RuntimeError("Protected state changed; cleanup rolled back")
            conn.commit()
            (RUN / f"identity-clean-{args.kind}.json").write_text(
                json.dumps(
                    {"originalRowsUnchanged": before, "ownedFixtureRemoved": True},
                    indent=2,
                ),
                encoding="utf-8",
            )
            snapshot.unlink()


if __name__ == "__main__":
    main()
