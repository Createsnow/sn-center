"""Order audit prerequisites and clock/fault controls in the dedicated a48 databases only."""

import json
import re
import sys
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
kind, action = sys.argv[1:3]
assert kind in ("java", "python")
java = kind == "java"
database = "sn_" + kind + "_a48"
assert database in ("sn_java_a48", "sn_python_a48")
table = "sn_order_bill" if java else "sn_prd_mo"
with pymysql.connect(host="127.0.0.1", port=33308, user="root",
                     password=(PRIVATE / "mysql-root").read_text().strip(), database=database,
                     autocommit=True, cursorclass=pymysql.cursors.DictCursor) as conn, conn.cursor() as c:
    c.execute("SELECT DATABASE() name")
    assert c.fetchone()["name"] == database
    if action == "prepare":
        tag = sys.argv[3]
        assert re.fullmatch(r"[0-9]{8,14}", tag)
        username = "audit" + tag
        user_table = "sys_user" if java else "sn_user"
        user_column = "username" if java else "emp_no"
        c.execute("SELECT COUNT(*) n FROM " + user_table + " WHERE " + user_column + "=%s", (username,))
        assert c.fetchone()["n"] == 0, "Use existing prepared fixture; never overwrite an account"
        c.execute("SELECT * FROM sn_" + kind + "_compare." + user_table + " WHERE " + user_column + "='admin'")
        user = c.fetchone()
        user["id"] = int("900" + tag)
        user[user_column] = username
        user["display_name" if java else "name"] = "同步痕迹操作员"
        user["must_change_password" if java else "must_change_pwd"] = 0
        c.execute("INSERT INTO " + user_table + " (" + ",".join(user) + ") VALUES (" + ",".join(["%s"] * len(user)) + ")", tuple(user.values()))
        if java:
            # 原型admin同时管理SN与账号；复制比较管理员的完整角色，避免制造侧栏权限差异。
            c.execute("INSERT INTO sys_user_role(user_id,role_id) SELECT %s,ur.role_id FROM sn_java_compare.sys_user_role ur JOIN sn_java_compare.sys_user u ON u.id=ur.user_id WHERE u.username='admin'", (user["id"],))
        print(json.dumps({"prepared": True, "username": username}))
    elif action == "due":
        c.execute("SET time_zone=%s", ("+00:00" if java else "+08:00",))
        c.execute("UPDATE sn_order_sync_schedule SET next_run_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1 AND enabled=1")
        assert c.rowcount == 1
        print('{"due":true}')
    elif action == "fault":
        for operation in ("INSERT", "UPDATE"):
            c.execute("CREATE TRIGGER a48_order_" + operation.lower() + " BEFORE " + operation + " ON " + table + " FOR EACH ROW BEGIN IF NEW.bill_no='CMP-MO-001' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic a48 order write failure'; END IF; END")
        print('{"fault":true}')
    elif action == "clear":
        for operation in ("insert", "update"):
            c.execute("DROP TRIGGER IF EXISTS a48_order_" + operation)
        print('{"cleared":true}')
    elif action == "snapshot":
        result = {}
        for key, sql in {
            "audits": "SELECT * FROM sn_audit ORDER BY created_at,id",
            "schedule": "SELECT * FROM sn_order_sync_schedule WHERE id=1",
            "orders": "SELECT * FROM " + table + " ORDER BY bill_no,id",
            "entries": "SELECT * FROM sn_order_entry ORDER BY id" if java else "SELECT * FROM sn_prd_mo ORDER BY id",
        }.items():
            c.execute(sql)
            result[key] = c.fetchall()
        print(json.dumps(result, ensure_ascii=False, default=str))
    else:
        raise ValueError("Unsupported action")
