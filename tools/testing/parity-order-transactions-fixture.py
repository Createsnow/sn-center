"""Real SQL faults in dedicated tx47 databases; never changes existing comparison databases."""

import json
import sys
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008/order-transactions"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008/stage47"
kind, action = sys.argv[1:3]
assert kind in ("java", "python")
java = kind == "java"
db = "sn_" + kind + "_tx47"
assert db in ("sn_java_tx47", "sn_python_tx47")


def org(number, code, name):
    return {"FOrgID": str(number), "FNumber": code, "FName": name, "FForbidStatus": "A"}


base = [org(101, "CMP-A", "比较测试甲厂"), org(102, "CMP-B", "比较测试乙厂")]
extra_orgs = [org(947100 + i, "CMP-TX-F" + str(i), "事务测试工厂" + str(i)) for i in range(1, 5)]


def configure(stage):
    count = {"baseline": 0, "factory-write": 1, "factory-audit": 2}.get(stage, 3)
    extras = []
    for i in range(1, count + 1):
        organization = extra_orgs[min(i, 3) - 1]
        extras.append({
            "F_ora_kh.FNumber": "CMP-CUSTOMER", "FBillNo": "CMP-TX-00" + str(i),
            "F_HX_PO": "CMP-TX-PO", "F_HX_PI": "CMP-TX-PI" + str(i),
            "FMaterialId.FNumber": "CMP-TX-MAT", "FMaterialId.FName": "事务测试物料",
            "FQty": "7", "FStatus": "1", "FSaleOrderNo": "CMP-TX-SALE",
            "FPrdOrgId.FName": organization["FName"], "FID": str(9007199254794700 + i),
            "FTreeEntity_FEntryId": str(94700 + i), "FPrdOrgId": organization["FOrgID"],
            "FPrdOrgId.FNumber": organization["FNumber"],
        })
    directory = {"baseline": base, "factory-write": extra_orgs[:2],
                 "factory-audit": extra_orgs[:2], "order-write": extra_orgs[2:3],
                 "order-audit": extra_orgs[3:4], "recovery": base + extra_orgs,
                 "bill-only": [org(947999, "CMP-TX-UNEXPECTED", "单据同步禁止更新工厂")]}
    (RUN / "extra-orders.json").write_text(json.dumps(extras, ensure_ascii=False), encoding="utf8")
    (RUN / "k3-control.json").write_text(json.dumps({"organizations": directory[stage]}, ensure_ascii=False), encoding="utf8")


with pymysql.connect(host="127.0.0.1", port=33308, user="root",
                     password=(PRIVATE.parent / "mysql-root").read_text().strip(), database=db,
                     autocommit=True, cursorclass=pymysql.cursors.DictCursor) as conn, conn.cursor() as c:
    c.execute("SELECT DATABASE() db")
    assert c.fetchone()["db"] == db
    if action == "snapshot":
        queries = {
            "factories": "SELECT " + ("code,name" if java else "factory_code code,factory_name name") + ",enabled,source FROM sn_factory ORDER BY 1",
            "orders": "SELECT * FROM " + ("sn_order_bill ORDER BY bill_no" if java else "sn_prd_mo ORDER BY bill_no,id"),
            "entries": "SELECT * FROM sn_order_entry ORDER BY bill_id,ordinal" if java else "SELECT * FROM sn_prd_mo ORDER BY bill_no,id",
            "audits": "SELECT * FROM sn_audit ORDER BY created_at,id",
        }
        if java:
            queries["attempts"] = "SELECT * FROM sn_order_sync ORDER BY started_at,id"
            queries["revision"] = "SELECT * FROM sn_snapshot_control"
        result = {}
        for key, sql in queries.items():
            c.execute(sql)
            result[key] = c.fetchall()
        print(json.dumps(result, ensure_ascii=False, default=str))
    else:
        for suffix in ("insert", "update"):
            c.execute("DROP TRIGGER IF EXISTS tx47_fault_" + suffix)
        if action == "baseline":
            # 仅重置本套新建空库的合成业务，不清除审计、同步历史或重置发布版本。
            c.execute("SELECT COUNT(*) n FROM sn_item")
            assert c.fetchone()["n"] == 0
            tables = ["sn_source_entry_quota", "sn_order_entry", "sn_order_bill", "sn_org_mapping", "sn_factory"] if java else ["sn_prd_mo", "sn_factory"]
            for table in tables:
                c.execute("DELETE FROM " + table)
        if action != "clear":
            configure(action)
            if action in ("factory-write", "factory-audit", "order-write", "order-audit"):
                if action == "factory-write":
                    table = "sn_factory"
                    condition = "NEW." + ("code" if java else "factory_code") + " = 'CMP-TX-F2'"
                elif action == "order-write":
                    table = "sn_order_bill" if java else "sn_prd_mo"
                    condition = "NEW.bill_no = 'CMP-TX-003'"
                else:
                    table = "sn_audit"
                    audit_action = ("sn:factory:sync" if java else "FACTORY_SYNC") if action == "factory-audit" else ("sn:order:sync" if java else "ORDER_SYNC")
                    condition = "NEW.action = '" + audit_action + "'"
                for event in ("INSERT", "UPDATE"):
                    c.execute("CREATE TRIGGER tx47_fault_" + event.lower() + " BEFORE " + event + " ON " + table
                              + " FOR EACH ROW BEGIN IF " + condition + " THEN SIGNAL SQLSTATE '45000'"
                              + " SET MESSAGE_TEXT='tx47 synthetic transaction fault'; END IF; END")
        print(json.dumps({"database": db, "action": action}))
