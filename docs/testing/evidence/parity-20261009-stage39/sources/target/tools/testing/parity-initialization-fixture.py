"""Own only two synthetic PIs for paired initialization UI tests on loopback databases."""

import argparse
import importlib.util
import json
import uuid
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
PIS = ("CMP-UI-INIT-PI", "CMP-UI-INIT-NONE")
BILL = "CMP-UI-INIT-MO"
RULE = "CMP_UI_INIT_RULE"
ALLOWED = {
    "INI00000",
    "INI00005",
    "INI00008",
    "UNPARSED",
    "'KEEP'",
    '"OPEN',
    'TAIL"',
    "RAW-CSV",
    "EDITED-SN",
    "L" * 64,
}
CHUNK_SNS = {f"CMP-INIT-CHUNK-{i:04d}" for i in range(1000)}
spec = importlib.util.spec_from_file_location(
    "generation_fixture", Path(__file__).with_name("parity-generation-fixture.py")
)
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


def inventory(c, java):
    pi = "pi" if java else "pi_no"
    code = "code" if java else "rule_code"
    queries = {
        "items": (f"SELECT * FROM sn_item WHERE {pi} NOT IN (%s,%s)", PIS),
        "keys": (f"SELECT * FROM sn_key WHERE {pi} NOT IN (%s,%s)", PIS),
        "counters": (f"SELECT * FROM sn_pi_counter WHERE {pi} NOT IN (%s,%s)", PIS),
        "rules": (f"SELECT * FROM sn_rule WHERE {code}<>%s", (RULE,)),
        "versions": (
            f"SELECT v.* FROM sn_rule_version v JOIN sn_rule r ON r.id=v.rule_id WHERE r.{code}<>%s",
            (RULE,),
        ),
        "imports": (
            "SELECT * FROM sn_work WHERE kind='PI_INIT' AND pi NOT IN (%s,%s)"
            if java
            else "SELECT * FROM sn_pi_import WHERE pi_no NOT IN (%s,%s)",
            PIS,
        ),
        "ledger": (
            "SELECT q.* FROM sn_source_entry_quota q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no<>%s"
            if java
            else "SELECT * FROM sn_bill_gen WHERE bill_no<>%s",
            (BILL,),
        ),
    }
    if java:
        queries["generation_identity"] = ("SELECT * FROM sn_bill_generation", ())
        queries["choices"] = ("SELECT * FROM sn_pi_rule_choice", ())
    result = {}
    for name, (sql, args) in queries.items():
        c.execute(sql, args)
        result[name] = common.digest(c.fetchall())
    return result


def observe(c, java):
    queries = {
        "keys": (
            "SELECT pi,sn,seq_pi,seq_dec FROM sn_key WHERE pi IN (%s,%s) ORDER BY pi,sn"
            if java
            else "SELECT pi_no pi,sn,seq_pi_no seq_pi,seq_dec FROM sn_key WHERE pi_no IN (%s,%s) ORDER BY pi_no,sn",
            PIS,
        ),
        "items": (
            "SELECT i.sn,i.pi,i.source_pi,i.seq_dec,i.customer,i.material,COALESCE(f.code,'') factory_code,i.status,i.source FROM sn_item i LEFT JOIN sn_factory f ON f.id=i.factory_id WHERE i.pi IN (%s,%s) ORDER BY i.sn"
            if java
            else "SELECT sn,pi_no pi,seq_pi_no source_pi,seq_dec,customer_code customer,material_code material,COALESCE(factory_code,'') factory_code,status,source FROM sn_item WHERE pi_no IN (%s,%s) ORDER BY sn",
            PIS,
        ),
        "counters": (
            "SELECT pi,last_seq,generated_qty,imported_qty,start_seq,start_locked FROM sn_pi_counter WHERE pi IN (%s,%s) ORDER BY pi"
            if java
            else "SELECT pi_no pi,last_seq_dec last_seq,generated_qty,imported_qty,start_seq_dec start_seq,start_locked FROM sn_pi_counter WHERE pi_no IN (%s,%s) ORDER BY pi_no",
            PIS,
        ),
        "history": (
            "SELECT w.pi,w.start_seq,w.quantity qty,w.max_seq,COALESCE(f.code,'') factory_code,w.file_name,w.actor_name operator FROM sn_work w LEFT JOIN sn_factory f ON f.id=w.factory_id WHERE w.pi IN (%s,%s) AND w.kind='PI_INIT' AND w.state='SUCCESS' ORDER BY w.created_at,w.task_id"
            if java
            else "SELECT pi_no pi,start_seq_dec start_seq,import_qty qty,max_seq_dec max_seq,COALESCE(factory_code,'') factory_code,COALESCE(file_name,'') file_name,created_by operator FROM sn_pi_import WHERE pi_no IN (%s,%s) ORDER BY id",
            PIS,
        ),
    }
    result = {}
    for name, (sql, args) in queries.items():
        c.execute(sql, args)
        result[name] = c.fetchall()
    return result


def validate_owned(c, java, conflict_keys):
    code = "code" if java else "rule_code"
    c.execute(f"SELECT * FROM sn_rule WHERE {code}=%s", (RULE,))
    rules = c.fetchall()
    if len(rules) > 1 or any(
        r["bind_scope"] != "PI" or r["bind_value"] != PIS[0] for r in rules
    ):
        raise RuntimeError("Unexpected rule ownership")
    facts = observe(c, java)
    allowed = ALLOWED | (CHUNK_SNS if conflict_keys else set())
    limit = 1012 if conflict_keys else 12
    if len(facts["items"]) > limit or any(
        row["sn"] not in allowed
        or row["status"] != "PRINTED"
        or row["source"] != "IMPORT"
        or row["factory_code"] not in ("", "CMP-A")
        or row["source_pi"] != row["pi"]
        for row in facts["items"]
    ):
        raise RuntimeError("Unexpected import members or ownership")
    expected_keys = conflict_keys + [
        {
            "pi": r["pi"],
            "sn": r["sn"],
            "seq_pi": r["source_pi"] if r["seq_dec"] is not None else None,
            "seq_dec": r["seq_dec"],
        }
        for r in facts["items"]
    ]
    if common.digest(facts["keys"]) != common.digest(expected_keys):
        raise RuntimeError("Unexpected permanent key ledger; preserving data")
    if any(
        row["operator"] != "admin" or row["factory_code"] not in ("", "CMP-A")
        for row in facts["history"]
    ):
        raise RuntimeError("Unexpected import history owner")
    if sum(row["qty"] for row in facts["history"]) != len(facts["items"]):
        raise RuntimeError("Import history and members disagree")
    if any(
        row["generated_qty"] != 0 or row["last_seq"] > 1000 for row in facts["counters"]
    ):
        raise RuntimeError("Unexpected generation or counter")
    if java:
        c.execute("SELECT kind,state FROM sn_work WHERE pi IN (%s,%s)", PIS)
        if any(
            r["kind"] != "PI_INIT"
            or r["state"] not in ("SUCCESS", "FAILED", "CANCELLED")
            for r in c.fetchall()
        ):
            raise RuntimeError("Foreign or unfinished task")
        c.execute(
            "SELECT COUNT(*) n FROM sn_item WHERE pi IN (%s,%s) AND (allocation_id IS NOT NULL OR current_batch_id IS NOT NULL OR current_transfer_id IS NOT NULL OR current_print_id IS NOT NULL)",
            PIS,
        )
        if c.fetchone()["n"]:
            raise RuntimeError("Imported SN has downstream references")
        c.execute(
            "SELECT COUNT(*) n FROM sn_source_entry_quota q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no=%s AND (q.generated_qty<>0 OR q.allocated_qty<>0)",
            (BILL,),
        )
        if c.fetchone()["n"]:
            raise RuntimeError("Owned order has consumed quota; preserving data")
    allowed = {
        "sn_item",
        "sn_key",
        "sn_pi_counter",
        "sn_pi_import",
        "sn_work",
        "sn_order_bill",
        "sn_prd_mo",
        "sn_audit",
        "sn_audit_archive",
    }
    c.execute(
        "SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('pi','pi_no','seq_pi','seq_pi_no','source_pi','first_pi','from_pi','to_pi')"
    )
    for column in c.fetchall():
        if column["TABLE_NAME"] not in allowed:
            c.execute(
                f"SELECT COUNT(*) n FROM `{column['TABLE_NAME']}` WHERE `{column['COLUMN_NAME']}` IN (%s,%s)",
                PIS,
            )
            if c.fetchone()["n"]:
                raise RuntimeError("Unrecognized PI reference; preserving data")
    c.execute(
        "SELECT * FROM sn_order_bill WHERE bill_no=%s"
        if java
        else "SELECT bill_no FROM sn_prd_mo WHERE bill_no=%s",
        (BILL,),
    )
    heads = c.fetchall()
    if (not java and heads) or (
        java
        and any(
            r["active"]
            or r["source_system"] != "K3"
            or r["source_account"] != "COMPARISON_SYNTHETIC"
            or r["source_bill_id"] != "9007199254781001"
            or r["pi"] != PIS[0]
            for r in heads
        )
    ):
        raise RuntimeError("Restore owned order snapshot through UI first")
    return rules


def clean(c, java, conflict_keys):
    rules = validate_owned(c, java, conflict_keys)
    pi = "pi" if java else "pi_no"
    for table in (
        "sn_key",
        "sn_item",
        "sn_pi_counter",
        "sn_work" if java else "sn_pi_import",
    ):
        c.execute(f"DELETE FROM {table} WHERE {pi} IN (%s,%s)", PIS)
    if java:
        for table in ("sn_source_entry_quota", "sn_order_entry"):
            c.execute(
                f"DELETE q FROM {table} q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no=%s",
                (BILL,),
            )
        c.execute("DELETE FROM sn_order_bill WHERE bill_no=%s AND active=0", (BILL,))
    for rule in rules:
        c.execute("DELETE FROM sn_rule_version WHERE rule_id=%s", (rule["id"],))
        c.execute("DELETE FROM sn_rule WHERE id=%s", (rule["id"],))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument(
        "action",
        choices=(
            "begin",
            "seed-rule",
            "seed-sn-conflict",
            "seed-sequence-conflict",
            "failures",
            "observe",
            "clean",
        ),
    )
    args = parser.parse_args()
    java = args.kind == "java"
    snapshot = RUN / f"initialization-fixture-{args.kind}.json"
    if args.action == "seed-rule":
        if not snapshot.exists():
            raise RuntimeError("Begin fixture ownership first")
        common.seed_rules(args.kind, [(RULE, PIS[0], "INI")])
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
            if (
                snapshot.exists()
                or list(RUN.glob("generation-fixture-*.json"))
                or list(RUN.glob("identity-fixture-*.json"))
            ):
                raise RuntimeError("Previous fixture still requires cleanup")
            for table in (
                "sn_item",
                "sn_key",
                "sn_pi_counter",
                "sn_work" if java else "sn_pi_import",
            ):
                c.execute(f"SELECT COUNT(*) n FROM {table} WHERE {pi} IN (%s,%s)", PIS)
                if c.fetchone()["n"]:
                    raise RuntimeError("Unowned PI already exists")
            c.execute(
                "SELECT COUNT(*) n FROM sn_rule WHERE "
                + ("code" if java else "rule_code")
                + "=%s OR (bind_scope='PI' AND bind_value IN (%s,%s))",
                (RULE,) + PIS,
            )
            if c.fetchone()["n"]:
                raise RuntimeError("Unowned rule exists")
            c.execute(
                "SELECT COUNT(*) n FROM sn_order_bill WHERE bill_no=%s"
                if java
                else "SELECT COUNT(*) n FROM sn_prd_mo WHERE bill_no=%s",
                (BILL,),
            )
            if c.fetchone()["n"]:
                raise RuntimeError("Unowned bill exists")
            c.execute(
                "SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('pi','pi_no','seq_pi','seq_pi_no','source_pi','first_pi','from_pi','to_pi')"
            )
            for column in c.fetchall():
                # 真实操作审计永久保留；它们不表示待清理的业务成员。
                if column["TABLE_NAME"] in ("sn_audit", "sn_audit_archive"):
                    continue
                c.execute(
                    f"SELECT COUNT(*) n FROM `{column['TABLE_NAME']}` WHERE `{column['COLUMN_NAME']}` IN (%s,%s)",
                    PIS,
                )
                if c.fetchone()["n"]:
                    raise RuntimeError("Unowned PI reference exists")
            snapshot.write_text(
                json.dumps(inventory(c, java), indent=2), encoding="utf-8"
            )
        elif args.action in ("seed-sn-conflict", "seed-sequence-conflict"):
            before = json.loads(snapshot.read_text(encoding="utf-8"))
            if "_conflictKeys" in before or inventory(c, java) != before:
                raise RuntimeError("Conflict ownership or original rows changed")
            current = observe(c, java)
            if current["items"] or current["keys"] or current["history"]:
                raise RuntimeError("Conflict seed requires empty owned business state")
            # 合成持久查重账本，用于模拟已归档成员；不触碰原有 SN 或伪造界面响应。
            sequence = args.action == "seed-sequence-conflict"
            key = {
                "pi": PIS[1],
                "sn": "ARCHIVED-SEQ-5" if sequence else "UNPARSED",
                "seq_pi": PIS[0] if sequence else None,
                "seq_dec": 5 if sequence else None,
            }
            if java:
                c.execute(
                    "INSERT INTO sn_key(id,gen_month,pi,sn,seq_pi,seq_dec) VALUES(%s,'2026-10-01',%s,%s,%s,%s)",
                    (
                        str(uuid.uuid4()),
                        key["pi"],
                        key["sn"],
                        key["seq_pi"],
                        key["seq_dec"],
                    ),
                )
            else:
                c.execute(
                    "INSERT INTO sn_key(pi_no,sn,seq_pi_no,seq_dec) VALUES(%s,%s,%s,%s)",
                    (key["pi"], key["sn"], key["seq_pi"], key["seq_dec"]),
                )
            conn.commit()
            before["_conflictKeys"] = [key]
            snapshot.write_text(json.dumps(before, indent=2), encoding="utf-8")
        elif args.action == "failures":
            if not java:
                raise RuntimeError("Task facts only apply to Java")
            c.execute(
                "SELECT w.state,w.error_code,CAST(w.error_params AS CHAR) error_params,p.processed FROM sn_work w JOIN sys_task_progress p ON p.task_id=w.task_id WHERE w.pi IN (%s,%s) AND w.state='FAILED' ORDER BY w.created_at,w.task_id",
                PIS,
            )
            print(json.dumps(c.fetchall(), default=str))
        elif args.action == "observe":
            print(json.dumps(observe(c, java), default=str))
        else:
            before = json.loads(snapshot.read_text(encoding="utf-8"))
            conflict_keys = before.pop("_conflictKeys", [])
            if conflict_keys and conflict_keys not in (
                [{"pi": PIS[1], "sn": "UNPARSED", "seq_pi": None, "seq_dec": None}],
                [
                    {
                        "pi": PIS[1],
                        "sn": "ARCHIVED-SEQ-5",
                        "seq_pi": PIS[0],
                        "seq_dec": 5,
                    }
                ],
            ):
                raise RuntimeError("Unexpected conflict fixture ownership")
            if inventory(c, java) != before:
                raise RuntimeError("Original rows changed; preserving fixture")
            clean(c, java, conflict_keys)
            if inventory(c, java) != before:
                raise RuntimeError("Protected state changed; rollback cleanup")
            conn.commit()
            (RUN / f"initialization-clean-{args.kind}.json").write_text(
                json.dumps(
                    {"originalRowsUnchanged": before, "ownedFixtureRemoved": True},
                    indent=2,
                ),
                encoding="utf-8",
            )
            snapshot.unlink()


if __name__ == "__main__":
    main()
