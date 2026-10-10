"""Protect existing comparison data around snapshot-picker UI tests; no SN writes."""

import argparse
import hashlib
import json
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
BILLS = (
    "CMP-UI-PICK-LOOK%_UP",
    "CMP-UI-PICK-DUP",
    "CMP-UI-PICK-UNMAPPED",
    "CMP-UI-PICK-NO-CUSTOMER",
    "CMP-UI-PICK-NO-PI",
)
MARKS = ",".join(["%s"] * len(BILLS))


def digest(rows):
    values = sorted(json.dumps(r, sort_keys=True, default=str) for r in rows)
    return {
        "count": len(values),
        "sha256": hashlib.sha256("\n".join(values).encode()).hexdigest(),
    }


def inventory(c, java):
    tables = [
        "sn_item",
        "sn_key",
        "sn_pi_counter",
        "sn_rule",
        "sn_rule_version",
        "sn_acquire_batch",
        "sn_print",
        "sn_transfer",
    ]
    tables += (
        [
            "sn_acquire_member",
            "sn_print_member",
            "sn_transfer_member",
            "sn_bill_generation",
            "sn_generation_job",
            "sn_pi_rule_choice",
        ]
        if java
        else ["sn_transfer_item", "sn_bill_gen", "sn_allocation"]
    )
    result = {}
    for table in tables:
        c.execute("SELECT * FROM " + table)
        result[table] = digest(c.fetchall())
    c.execute(
        "SELECT id,username,password_hash,security_version,must_change_password FROM sys_user"
        if java
        else "SELECT id,emp_no,password_hash,must_change_pwd,role,factory_code,status FROM sn_user"
    )
    result["accountSecurity"] = digest(c.fetchall())
    if java:
        c.execute(
            f"SELECT q.* FROM sn_source_entry_quota q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no NOT IN ({MARKS})",
            BILLS,
        )
        result["quotas"] = digest(c.fetchall())
        c.execute(
            f"SELECT bill_no,customer,pi,org_name,total_qty,line_count FROM sn_order_bill WHERE active=1 AND bill_no NOT IN ({MARKS})",
            BILLS,
        )
    else:
        c.execute(
            f"SELECT bill_no,customer_number,pi,prd_org_name,material_number,material_name,qty FROM sn_prd_mo WHERE bill_no NOT IN ({MARKS})",
            BILLS,
        )
    result["originalSnapshot"] = digest(c.fetchall())
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("kind", choices=("java", "python"))
    p.add_argument("action", choices=("begin", "observe", "clean"))
    args = p.parse_args()
    java = args.kind == "java"
    snapshot = RUN / f"target-fixture-{args.kind}.json"
    with (
        pymysql.connect(
            host="127.0.0.1",
            port=33308,
            user=args.kind + "_compare",
            password=(PRIVATE / ("mysql-" + args.kind)).read_text().strip(),
            database="sn_" + args.kind + "_compare",
            cursorclass=pymysql.cursors.DictCursor,
        ) as con,
        con.cursor() as c,
    ):
        if args.action == "begin":
            if list(RUN.glob("target-fixture-*.json")) or list(
                RUN.glob("acquire*fixture-*.json")
            ):
                raise RuntimeError("Previous comparison fixture remains")
            if json.loads((RUN / "extra-orders.json").read_text()) or json.loads(
                (RUN / "k3-control.json").read_text()
            ):
                raise RuntimeError("Synthetic ERP is not at baseline")
            c.execute(
                f"SELECT COUNT(*) n FROM {'sn_order_bill' if java else 'sn_prd_mo'} WHERE bill_no IN ({MARKS})",
                BILLS,
            )
            if c.fetchone()["n"]:
                raise RuntimeError("Owned bill names already exist")
            snapshot.write_text(json.dumps(inventory(c, java)), encoding="utf-8")
            return
        expected = json.loads(snapshot.read_text())
        if inventory(c, java) != expected:
            raise RuntimeError("Existing inventory, accounts or snapshot changed")
        if args.action == "clean":
            if json.loads((RUN / "extra-orders.json").read_text()):
                raise RuntimeError("Restore synthetic ERP through UI first")
            if java:
                c.execute(
                    "SELECT COUNT(*) n FROM sys_task WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')"
                )
                if c.fetchone()["n"]:
                    raise RuntimeError("Active tasks remain")
                c.execute(
                    f"SELECT COUNT(*) n FROM sn_order_bill WHERE active=1 AND bill_no IN ({MARKS})",
                    BILLS,
                )
                if c.fetchone()["n"]:
                    raise RuntimeError("Owned snapshot still active")
                c.execute(
                    "SELECT TABLE_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='bill_id'"
                )
                for row in c.fetchall():
                    table = row["TABLE_NAME"]
                    if table not in ("sn_order_entry", "sn_source_entry_quota"):
                        c.execute(
                            f"SELECT COUNT(*) n FROM `{table}` r JOIN sn_order_bill b ON b.id=r.bill_id WHERE b.bill_no IN ({MARKS})",
                            BILLS,
                        )
                        if c.fetchone()["n"]:
                            raise RuntimeError("Unexpected downstream bill reference")
                c.execute(
                    f"SELECT COUNT(*) n FROM sn_source_entry_quota q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no IN ({MARKS}) AND (q.generated_qty<>0 OR q.allocated_qty<>0)",
                    BILLS,
                )
                if c.fetchone()["n"]:
                    raise RuntimeError("Owned quota was used")
                for table in ("sn_source_entry_quota", "sn_order_entry"):
                    c.execute(
                        f"DELETE r FROM {table} r JOIN sn_order_bill b ON b.id=r.bill_id WHERE b.bill_no IN ({MARKS})",
                        BILLS,
                    )
                c.execute(
                    f"DELETE FROM sn_order_bill WHERE bill_no IN ({MARKS}) AND active=0",
                    BILLS,
                )
            else:
                c.execute(
                    f"SELECT COUNT(*) n FROM sn_prd_mo WHERE bill_no IN ({MARKS})",
                    BILLS,
                )
                if c.fetchone()["n"]:
                    raise RuntimeError("Owned snapshot still present")
            assert inventory(c, java) == expected
            con.commit()
            snapshot.unlink()
        print(
            json.dumps(
                {
                    "originalRowsUnchanged": expected,
                    "ownedSnapshotRemoved": args.action == "clean",
                }
            )
        )


if __name__ == "__main__":
    main()
