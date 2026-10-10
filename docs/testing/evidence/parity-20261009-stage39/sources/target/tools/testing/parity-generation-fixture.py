"""Guarded generation fixtures for the two disposable comparison schemas only."""

import argparse
import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pymysql

PI = "CMP-UI-GEN-PI"
BILL = "CMP-UI-GEN-MO"
RULE = "CMP_UI_GEN_RULE"
PREFIX = "G06"
ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"


def digest(rows):
    encoded = sorted(json.dumps(row, sort_keys=True, default=str) for row in rows)
    return {
        "count": len(rows),
        "sha256": hashlib.sha256("\n".join(encoded).encode()).hexdigest(),
    }


def inventory(cursor, java):
    """原有 SN、唯一护栏、规则、计数器及累计必须逐字段不变；同步快照由 UI 恢复。"""
    pi = "pi" if java else "pi_no"
    code = "code" if java else "rule_code"
    queries = {
        "items": f"SELECT * FROM sn_item WHERE {pi}<>%s",
        "keys": f"SELECT * FROM sn_key WHERE {pi}<>%s",
        "counters": f"SELECT * FROM sn_pi_counter WHERE {pi}<>%s",
        "rules": f"SELECT * FROM sn_rule WHERE {code}<>%s",
        "versions": f"SELECT v.* FROM sn_rule_version v JOIN sn_rule r ON r.id=v.rule_id WHERE r.{code}<>%s",
        "ledger": (
            "SELECT q.* FROM sn_source_entry_quota q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no<>%s"
            if java
            else "SELECT * FROM sn_bill_gen WHERE bill_no<>%s"
        ),
        "allocations": (
            "SELECT * FROM sn_work WHERE kind='ALLOCATE' AND pi<>%s"
            if java
            else "SELECT * FROM sn_allocation WHERE pi_no<>%s"
        ),
    }
    if java:
        queries["generation_identity"] = (
            "SELECT g.* FROM sn_bill_generation g JOIN sn_order_bill b ON b.id=g.bill_id WHERE b.bill_no<>%s"
        )
    result = {}
    for name, sql in queries.items():
        cursor.execute(
            sql,
            (
                RULE
                if name in ("rules", "versions")
                else BILL
                if name in ("ledger", "generation_identity")
                else PI,
            ),
        )
        result[name] = digest(cursor.fetchall())
    return result


def seed_rule(kind):
    seed_rules(kind, [(RULE, PI, PREFIX)])


def seed_rules(kind, definitions):
    """测试准备共用一次登录；业务预演、生成和分配始终由页面操作。"""
    java = kind == "java"
    prefix = "/api/v1" if java else "/api"
    with httpx.Client(
        base_url="http://127.0.0.1:" + ("18080" if java else "8000"), trust_env=False
    ) as client:
        token = None

        def post(path, body):
            headers = {"Authorization": "Bearer " + token} if token else {}
            if java:
                csrf = client.get(prefix + "/system/auth/csrf").json()["data"]
                headers[csrf["headerName"]] = csrf["token"]
            response = client.post(prefix + path, json=body, headers=headers)
            if not response.is_success:
                raise RuntimeError(
                    f"Comparison setup rejected: HTTP {response.status_code}"
                )
            return response.json()["data"] if java else response.json()

        login = post(
            "/system/auth/login" if java else "/auth/login",
            {
                "username" if java else "emp_no": "admin",
                "password": (PRIVATE / f"{kind}-admin-password")
                .read_text(encoding="utf-8")
                .strip(),
            },
        )
        token = login["accessToken" if java else "token"]
        for rule, pi, sn_prefix in definitions:
            body = {
                "code" if java else "rule_code": rule,
                "name" if java else "rule_name": rule,
                "bindScope" if java else "bind_scope": "PI",
                "bindValue" if java else "bind_value": pi,
                "prefix": sn_prefix,
                "suffix": "",
                "base": 10,
                "length" if java else "seq_len": 5,
                "alphabet" if java else "charset": "",
            }
            post("/sn/rules" if java else "/rules", body)


def clean(cursor, java, allocation_factories):
    """只清理独占 PI 的未分配/待领取合成 SN；分配数量、归属及下游引用均先逐项核对。"""
    pi = "pi" if java else "pi_no"
    code = "code" if java else "rule_code"
    scope = "bind_scope"
    cursor.execute(f"SELECT * FROM sn_rule WHERE {code}=%s", (RULE,))
    rules = cursor.fetchall()
    if len(rules) > 1 or any(r[scope] != "PI" or r["bind_value"] != PI for r in rules):
        raise RuntimeError("Unexpected rule ownership")
    cursor.execute(f"SELECT * FROM sn_item WHERE {pi}=%s", (PI,))
    items = cursor.fetchall()
    if len(items) > 30 or any(
        r["status"] not in ("PENDING_ALLOC", "TO_ACQUIRE")
        or r["source"] != "GEN"
        or r["bill_no"] != BILL
        or r["sn"] != f"{PREFIX}{r['seq_dec']:05}"
        for r in items
    ):
        raise RuntimeError("Unexpected generation fixture state; no cleanup performed")
    cursor.execute(
        "SELECT code FROM sn_factory WHERE id IN (SELECT factory_id FROM sn_item WHERE pi=%s AND status='TO_ACQUIRE')"
        if java
        else "SELECT DISTINCT factory_code code FROM sn_item WHERE pi_no=%s AND status='TO_ACQUIRE'",
        (PI,),
    )
    if any(row["code"] not in allocation_factories for row in cursor.fetchall()):
        raise RuntimeError("Unexpected allocation factory; preserving fixture")
    cursor.execute(
        "SELECT w.*,b.bill_no owned_bill FROM sn_work w JOIN sn_order_bill b ON b.id=w.bill_id WHERE w.pi=%s AND w.kind='ALLOCATE'"
        if java
        else "SELECT *,bill_no owned_bill FROM sn_allocation WHERE pi_no=%s",
        (PI,),
    )
    allocations = cursor.fetchall()
    allocated = [row for row in items if row["status"] == "TO_ACQUIRE"]
    if any(
        row["owned_bill"] != BILL or (java and row["state"] != "SUCCESS")
        for row in allocations
    ):
        raise RuntimeError("Unexpected allocation history; preserving fixture")
    if sum(int(row["quantity" if java else "qty"]) for row in allocations) != len(
        allocated
    ):
        raise RuntimeError("Allocation ledger does not match owned fixture members")
    if java:
        allocation_ids = {row["task_id"] for row in allocations}
        if any(row["allocation_id"] not in allocation_ids for row in allocated):
            raise RuntimeError("Fixture contains members from another allocation")
    elif any(row["factory_code"] not in allocation_factories for row in allocations):
        raise RuntimeError("Unexpected allocation history factory")
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
        "sn_allocation",
        "sn_prd_mo",
        "sn_audit",
        "sn_audit_archive",
    }
    cursor.execute(
        "SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('pi','pi_no','seq_pi','seq_pi_no')"
    )
    for ref in cursor.fetchall():
        if ref["TABLE_NAME"] in allowed:
            continue
        cursor.execute(
            f"SELECT COUNT(*) n FROM `{ref['TABLE_NAME']}` WHERE `{ref['COLUMN_NAME']}`=%s",
            (PI,),
        )
        if cursor.fetchone()["n"]:
            raise RuntimeError(
                "Generation fixture has downstream references; preserving data"
            )
    cursor.execute(
        "SELECT COUNT(*) n FROM sn_work WHERE pi=%s AND state='PENDING'"
        if java
        else "SELECT COUNT(*) n FROM sn_gen_job WHERE pi_no=%s AND status='RUNNING'",
        (PI,),
    )
    if cursor.fetchone()["n"]:
        raise RuntimeError("Generation is still running; preserving data")
    cursor.execute(f"DELETE FROM sn_key WHERE {pi}=%s", (PI,))
    cursor.execute(f"DELETE FROM sn_item WHERE {pi}=%s", (PI,))
    if java:
        cursor.execute(
            "DELETE j FROM sn_generation_job j JOIN sn_work w ON w.task_id=j.task_id WHERE w.pi=%s",
            (PI,),
        )
        for table in ("sn_preview", "sn_work", "sn_pi_rule_choice", "sn_pi_counter"):
            cursor.execute(f"DELETE FROM {table} WHERE pi=%s", (PI,))
        # 原型删除快照行，新工程保留离开快照的订单头；这里只删除本测试的独占头和台账。
        cursor.execute(
            "SELECT g.* FROM sn_bill_generation g JOIN sn_order_bill b ON b.id=g.bill_id WHERE b.bill_no=%s",
            (BILL,),
        )
        identities = cursor.fetchall()
        if any(g["first_pi"] != PI for g in identities):
            raise RuntimeError(
                "Generation identity belongs to another PI; preserving fixture"
            )
        for table in ("sn_bill_generation", "sn_source_entry_quota", "sn_order_entry"):
            cursor.execute(
                f"DELETE q FROM {table} q JOIN sn_order_bill b ON b.id=q.bill_id WHERE b.bill_no=%s AND b.pi=%s",
                (BILL, PI),
            )
        cursor.execute(
            "DELETE FROM sn_order_bill WHERE bill_no=%s AND pi=%s AND active=0",
            (BILL, PI),
        )
        cursor.execute("SELECT COUNT(*) n FROM sn_order_bill WHERE bill_no=%s", (BILL,))
    else:
        for table in (
            "sn_gen_preview",
            "sn_gen_job",
            "sn_allocation",
            "sn_bill_gen",
            "sn_pi_counter",
        ):
            cursor.execute(f"DELETE FROM {table} WHERE pi_no=%s", (PI,))
        cursor.execute("SELECT COUNT(*) n FROM sn_prd_mo WHERE bill_no=%s", (BILL,))
    if cursor.fetchone()["n"]:
        raise RuntimeError("Restore the order snapshot using the UI before cleanup")
    for rule in rules:
        cursor.execute("DELETE FROM sn_rule_version WHERE rule_id=%s", (rule["id"],))
        cursor.execute(
            f"DELETE FROM sn_rule WHERE id=%s AND {code}=%s", (rule["id"], RULE)
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument(
        "action",
        choices=(
            "begin",
            "begin-factory",
            "seed-rule",
            "expire-preview",
            "observe",
            "today",
            "clean",
        ),
    )
    args = parser.parse_args()
    java = args.kind == "java"
    snapshot = RUN / f"generation-fixture-{args.kind}.json"
    if args.action == "seed-rule":
        if not snapshot.exists():
            raise RuntimeError("Begin the protected fixture first")
        seed_rule(args.kind)
        return
    with (
        pymysql.connect(
            host="127.0.0.1",
            port=33308,
            database=f"sn_{args.kind}_compare",
            user=f"{args.kind}_compare",
            password=(PRIVATE / f"mysql-{args.kind}")
            .read_text(encoding="utf-8")
            .strip(),
            cursorclass=pymysql.cursors.DictCursor,
        ) as connection,
        connection.cursor() as cursor,
    ):
        pi = "pi" if java else "pi_no"
        if args.action in ("begin", "begin-factory"):
            if snapshot.exists():
                raise RuntimeError("Previous generation fixture still needs cleanup")
            for table in ("sn_item", "sn_pi_counter"):
                cursor.execute(f"SELECT COUNT(*) n FROM {table} WHERE {pi}=%s", (PI,))
                if cursor.fetchone()["n"]:
                    raise RuntimeError("Unowned generation fixture exists")
            cursor.execute(
                "SELECT COUNT(*) n FROM sn_rule WHERE "
                + ("code" if java else "rule_code")
                + "=%s",
                (RULE,),
            )
            if cursor.fetchone()["n"]:
                raise RuntimeError("Unowned generation rule exists")
            snapshot.write_text(
                json.dumps(
                    {
                        "inventory": inventory(cursor, java),
                        "allocationFactories": ["CMP-A", "CMP-B"]
                        if args.action == "begin-factory"
                        else ["CMP-A"],
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )
        elif args.action == "expire-preview":
            # 仅推进本用例唯一、未使用且属于 admin 的预演到期；不改系统时钟或其他 PI。
            if not snapshot.exists():
                raise RuntimeError("Begin the protected fixture first")
            cursor.execute(
                "SELECT p.id FROM sn_preview p JOIN sn_order_bill b ON b.id=p.bill_id JOIN sys_user u ON u.id=p.actor_id WHERE p.pi=%s AND b.bill_no=%s AND u.username='admin' AND p.task_id IS NULL FOR UPDATE"
                if java
                else "SELECT token id FROM sn_gen_preview WHERE pi_no=%s AND bill_no=%s AND created_by='admin' AND used_at IS NULL FOR UPDATE",
                (PI, BILL),
            )
            previews = cursor.fetchall()
            if len(previews) != 1:
                raise RuntimeError("Expected exactly one owned unused preview")
            cursor.execute(
                "UPDATE sn_preview SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 YEAR) WHERE id=%s AND task_id IS NULL"
                if java
                else "UPDATE sn_gen_preview SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 YEAR) WHERE token=%s AND used_at IS NULL",
                (previews[0]["id"],),
            )
            if cursor.rowcount != 1:
                raise RuntimeError("Owned preview changed concurrently")
            connection.commit()
            print(json.dumps({"expiredPreviewCount": 1}))
        elif args.action == "today":
            # 只读审计核对首页；清理合成业务行不清除审计，因此不能硬编码今日累计为初始 10 枚。
            start = datetime.now(timezone(timedelta(hours=8))).replace(
                hour=0, minute=0, second=0, microsecond=0
            )
            if java:
                start = start.astimezone(timezone.utc)
            start = start.replace(tzinfo=None)
            cursor.execute(
                "SELECT action,"
                + ("quantity" if java else "qty")
                + " quantity FROM sn_audit WHERE result=%s AND created_at>=%s AND created_at<%s",
                ("SUCCESS" if java else "OK", start, start + timedelta(days=1)),
            )
            actions = {
                "sn:generation:execute": "SN_GENERATE",
                "sn:allocation:execute": "SN_ALLOCATE",
                "sn:acquire:take": "SN_ACQUIRE",
                "sn:print:execute": "SN_PRINT",
                "sn:acquire:callback": "SN_CALLBACK",
            }
            totals = {}
            for row in cursor.fetchall():
                name = actions.get(row["action"]) if java else row["action"]
                if name in actions.values():
                    totals[name] = totals.get(name, 0) + int(row["quantity"] or 0)
            print(
                json.dumps(
                    {name: str(count) for name, count in totals.items()}, sort_keys=True
                )
            )
        elif args.action == "observe":
            cursor.execute(
                f"SELECT sn,seq_dec,status,{('material' if java else 'material_code')} material,{('customer' if java else 'customer_code')} customer FROM sn_item WHERE {pi}=%s ORDER BY seq_dec",
                (PI,),
            )
            observed = {"kind": args.kind, "items": cursor.fetchall()}
            cursor.execute(
                "SELECT w.quantity qty,w.first_sn start_sn,w.last_sn end_sn,w.actor_name created_by,f.code factory_code FROM sn_work w JOIN sn_factory f ON f.id=w.factory_id WHERE w.pi=%s AND w.kind='ALLOCATE' AND w.state='SUCCESS' ORDER BY w.created_at DESC,w.task_id DESC"
                if java
                else "SELECT qty,start_sn,end_sn,created_by,factory_code FROM sn_allocation WHERE pi_no=%s ORDER BY id DESC",
                (PI,),
            )
            observed["allocations"] = cursor.fetchall()
            cursor.execute(
                "SELECT state,quantity qty,error_code,error_params FROM sn_work WHERE pi=%s AND kind='GENERATE' ORDER BY created_at,task_id"
                if java
                else "SELECT status state,qty,error_code,error_msg FROM sn_gen_job WHERE pi_no=%s ORDER BY id",
                (PI,),
            )
            observed["jobs"] = cursor.fetchall()
            cursor.execute(
                "SELECT i.sn,i.source_pi pi,i.customer,f.code factory_code,s.code source_factory_code"
                " FROM sn_item i LEFT JOIN sn_factory f ON f.id=i.factory_id"
                " LEFT JOIN sn_factory s ON s.id=i.source_factory_id WHERE i.bill_no=%s ORDER BY i.seq_dec"
                if java
                else "SELECT sn,pi_no pi,customer_code customer,factory_code FROM sn_item WHERE bill_no=%s ORDER BY seq_dec",
                (BILL,),
            )
            observed["identities"] = cursor.fetchall()
            cursor.execute(
                "SELECT g.first_pi pi,g.first_customer customer,f.code factory_code FROM sn_bill_generation g"
                " JOIN sn_order_bill b ON b.id=g.bill_id JOIN sn_factory f ON f.id=g.factory_id WHERE b.bill_no=%s"
                if java
                else "SELECT pi_no pi,customer_code customer,factory_code FROM sn_bill_gen WHERE bill_no=%s",
                (BILL,),
            )
            observed["generationIdentity"] = cursor.fetchall()
            print(json.dumps(observed, default=str))
        else:
            saved = json.loads(snapshot.read_text(encoding="utf-8"))
            baseline = saved["inventory"]
            allowed_factories = saved["allocationFactories"]
            if allowed_factories not in (["CMP-A"], ["CMP-A", "CMP-B"]):
                raise RuntimeError("Unexpected fixture factory scope")
            if inventory(cursor, java) != baseline:
                raise RuntimeError("Original business rows changed; refusing cleanup")
            clean(cursor, java, allowed_factories)
            if inventory(cursor, java) != baseline:
                raise RuntimeError(
                    "Protected business state differs; transaction rolled back"
                )
            connection.commit()
            (RUN / f"generation-clean-{args.kind}.json").write_text(
                json.dumps(
                    {
                        "kind": args.kind,
                        "originalRowsUnchanged": baseline,
                        "ownedFixtureRemoved": True,
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )
            snapshot.unlink()


if __name__ == "__main__":
    main()
