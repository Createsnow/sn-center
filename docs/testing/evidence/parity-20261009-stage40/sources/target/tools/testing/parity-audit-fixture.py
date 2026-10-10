"""Own synthetic audit display rows only; preserve every pre-existing audit and business row."""

import argparse
import hashlib
import json
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
PI = "CMP-UI-AUDIT-VIEW-PI"
ACTOR = "CMP_AUDIT_VIEW"


def digest(value):
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(raw.encode()).hexdigest()


def audits(c):
    c.execute("SELECT * FROM sn_audit")
    return {str(r["id"]) + "|" + str(r["created_at"]): digest(r) for r in c.fetchall()}


def inventory(c, java):
    out = {}
    for table in (
        "sn_item",
        "sn_key",
        "sn_rule",
        "sn_rule_version",
        "sn_pi_counter",
        "sn_acquire_batch",
        "sn_print",
        "sn_transfer",
    ):
        c.execute("SELECT * FROM " + table)
        out[table] = digest(sorted(digest(r) for r in c.fetchall()))
    c.execute(
        "SELECT id,username,password_hash,security_version,must_change_password FROM sys_user"
        if java
        else "SELECT id,emp_no,password_hash,must_change_pwd,role,factory_code,status FROM sn_user"
    )
    out["accounts"] = digest(sorted(digest(r) for r in c.fetchall()))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=["java", "python"])
    parser.add_argument("action", choices=["begin", "clean"])
    parser.add_argument("--file-cases", action="store_true")
    args = parser.parse_args()
    java = args.kind == "java"
    marker = RUN / f"audit-view-fixture-{args.kind}.json"
    with (
        pymysql.connect(
            host="127.0.0.1",
            port=33308,
            user=args.kind + "_compare",
            password=(PRIVATE / ("mysql-" + args.kind)).read_text().strip(),
            database="sn_" + args.kind + "_compare",
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=False,
        ) as connection,
        connection.cursor() as c,
    ):
        pi_field = "pi" if java else "pi_no"
        if args.action == "begin":
            assert not marker.exists(), "Existing fixture requires inspection"
            c.execute(
                f"SELECT COUNT(*) n FROM sn_audit WHERE {pi_field}=%s OR operator=%s",
                (PI, ACTOR),
            )
            assert c.fetchone()["n"] == 0
            before = {"audit": audits(c), "business": inventory(c, java)}
            if java:
                c.execute("SELECT * FROM sn_audit_identity")
                before["identities"] = {
                    str(r["creation_order"]): digest(r) for r in c.fetchall()
                }
            c.execute(
                "SELECT id,code,name FROM sn_factory WHERE name IN (%s,%s) ORDER BY name"
                if java
                else "SELECT factory_code code,factory_name name FROM sn_factory WHERE factory_name IN (%s,%s) ORDER BY factory_name",
                ("比较测试甲厂", "比较测试乙厂"),
            )
            factories = {r["name"]: r for r in c.fetchall()}
            assert len(factories) == 2
            if java:
                c.execute("SELECT id FROM sys_user WHERE username='admin'")
                actor_id = c.fetchone()["id"]
            day = datetime.now(ZoneInfo("Asia/Shanghai")).replace(
                hour=12, minute=0, second=0, microsecond=0, tzinfo=None
            ) - timedelta(days=1)
            ids = []
            for number in range(1, 55):
                factory = factories["比较测试甲厂" if number % 2 else "比较测试乙厂"]
                created = day + timedelta(seconds=number)
                if args.file_cases and number in (51, 53, 54):
                    created = day + timedelta(seconds={51: 50, 53: 54, 54: 1}[number])
                operation = f"31000000-0000-4000-8000-{number:012d}"
                common = {
                    "created_at": created - timedelta(hours=8) if java else created,
                    "operator": ACTOR,
                    "operator_name": "审计界面样本",
                    "source": ["PAGE", "API", "SYSTEM"][number % 3],
                    "action": ("sn:generation:execute" if java else "SN_GENERATE")
                    if number % 2
                    else ("sn:allocation:execute" if java else "SN_ALLOCATE"),
                    "result": ("FAILED" if java else "FAIL")
                    if number % 9 == 0
                    else ("SUCCESS" if java else "OK"),
                    "factory_code": factory["code"],
                    "bill_no": f"CMP-AUDIT-MO-{number:03d}",
                    "start_sn": f"AV{number:05d}",
                    "end_sn": f"AV{number:05d}",
                    "before_status": "PENDING_ALLOC",
                    "after_status": "TO_ACQUIRE",
                    "reason": "界面范围样本",
                    "trace_id": operation,
                    "detail": json.dumps(
                        {"case": f"CMP_AUDIT_VIEW_{number:03d}"},
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                }
                if java:
                    common.update(
                        id=str(uuid.uuid4()),
                        actor_id=actor_id,
                        roles='["SN_HQ"]',
                        factory_id=factory["id"],
                        factory_name=factory["name"],
                        pi=PI,
                        customer="CMP-AUDIT-CUSTOMER",
                        material="CMP-AUDIT-MATERIAL",
                        quantity=number,
                        operation_id=operation,
                    )
                else:
                    common.update(
                        role="admin",
                        pi_no=PI,
                        customer_code="CMP-AUDIT-CUSTOMER",
                        material_code="CMP-AUDIT-MATERIAL",
                        qty=number,
                        request_no=operation,
                    )
                if args.file_cases:
                    common["quantity" if java else "qty"] = {
                        7: 999999999999999,
                        8: 9999999999999999,
                        9: 0,
                    }.get(number, number)
                    if number == 10:
                        common["operator_name"] = "=2+2"
                        common["customer" if java else "customer_code"] = "@客户"
                        common["material" if java else "material_code"] = "000001"
                        common["reason"] = '逗号,引号"与\r\n换行'
                    if number in (12, 13):
                        common["reason"] = "" if number == 12 else None
                    if number == 9:
                        common["error_code" if java else "error_msg"] = "夹具失败"
                    if number in (15, 16):
                        common["roles" if java else "role"] = (
                            (
                                '["FACTORY_OPERATOR"]'
                                if number == 15
                                else '["SN_QUERY_ALL"]'
                            )
                            if java
                            else ("factory_operator" if number == 15 else "query")
                        )
                columns = ",".join(common)
                placeholders = ",".join(["%s"] * len(common))
                c.execute(
                    f"INSERT INTO sn_audit({columns}) VALUES({placeholders})",
                    list(common.values()),
                )
                ids.append(common["id"] if java else c.lastrowid)
                if java:
                    c.execute(
                        "INSERT INTO sn_audit_identity(audit_id,created_at) VALUES(%s,%s)",
                        (common["id"], common["created_at"]),
                    )
            c.execute(
                f"SELECT * FROM sn_audit WHERE {pi_field}=%s AND operator=%s ORDER BY created_at,id",
                (PI, ACTOR),
            )
            owned = c.fetchall()
            assert len(owned) == 54
            state = {
                "kind": args.kind,
                "pi": PI,
                "operator": ACTOR,
                "ids": ids,
                "rows": digest(owned),
                "before": before,
                "date": day.date().isoformat(),
            }
            if java:
                c.execute(
                    "SELECT * FROM sn_audit_identity WHERE audit_id IN ("
                    + ",".join(["%s"] * len(ids))
                    + ") ORDER BY creation_order",
                    ids,
                )
                state["identities"] = digest(c.fetchall())
            marker.write_text(
                json.dumps(state, ensure_ascii=False, indent=2), encoding="utf8"
            )
            connection.commit()
            print(
                json.dumps(
                    {"date": state["date"], "rows": 54, "pi": PI, "operator": ACTOR}
                )
            )
        else:
            state = json.loads(marker.read_text(encoding="utf8"))
            assert (
                state["kind"] == args.kind
                and state["pi"] == PI
                and state["operator"] == ACTOR
            )
            c.execute(
                f"SELECT * FROM sn_audit WHERE {pi_field}=%s AND operator=%s ORDER BY created_at,id FOR UPDATE",
                (PI, ACTOR),
            )
            owned = c.fetchall()
            assert len(owned) == 54 and digest(owned) == state["rows"], (
                "Owned rows changed; refusing cleanup"
            )
            assert {r["id"] for r in owned} == set(state["ids"])
            assert inventory(c, java) == state["before"]["business"]
            current = audits(c)
            assert all(
                current.get(key) == value
                for key, value in state["before"]["audit"].items()
            )
            if java:
                c.execute("SELECT * FROM sn_audit_identity")
                identities = {str(r["creation_order"]): digest(r) for r in c.fetchall()}
                assert all(
                    identities.get(k) == v
                    for k, v in state["before"]["identities"].items()
                )
                c.execute(
                    "SELECT * FROM sn_audit_identity WHERE audit_id IN ("
                    + ",".join(["%s"] * len(state["ids"]))
                    + ") ORDER BY creation_order FOR UPDATE",
                    state["ids"],
                )
                owned_identities = c.fetchall()
                assert (
                    len(owned_identities) == 54
                    and digest(owned_identities) == state["identities"]
                )
                c.execute(
                    "DELETE FROM sn_audit_identity WHERE audit_id IN ("
                    + ",".join(["%s"] * len(state["ids"]))
                    + ")",
                    state["ids"],
                )
                assert c.rowcount == 54
            c.execute(
                f"DELETE FROM sn_audit WHERE {pi_field}=%s AND operator=%s AND id IN ("
                + ",".join(["%s"] * len(state["ids"]))
                + ")",
                [PI, ACTOR, *state["ids"]],
            )
            assert c.rowcount == 54
            connection.commit()
            output = {
                "syntheticRowsRemoved": 54,
                "originalAuditRowsUnchanged": len(state["before"]["audit"]),
                "businessAndAccountSecurityUnchanged": True,
            }
            (RUN / f"audit-view-clean-{args.kind}.json").write_text(
                json.dumps(output, indent=2), encoding="utf8"
            )
            marker.unlink()
            print(json.dumps(output))


if __name__ == "__main__":
    main()
