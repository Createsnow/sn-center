"""Own two synthetic PIs for acquire/print UI tests; protect all pre-existing business rows."""

import argparse
import importlib.util
import json
import sys
import time
import uuid
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
PIS = ("CMP-UI-ACQ-PI-A", "CMP-UI-ACQ-PI-B")
BILLS = ("CMP-UI-ACQ-MO-A", "CMP-UI-ACQ-MO-B", "CMP-UI-ACQ-UNUSED")
RULES = ("CMP_UI_ACQ_A", "CMP_UI_ACQ_B")
spec = importlib.util.spec_from_file_location(
    "identity", Path(__file__).with_name("parity-identity-fixture.py")
)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
# 仅复用明确排除声明 PI/订单/规则的只读清单；不放宽原生成夹具的清理条件。
base.PIS, base.BILLS, base.RULES = PIS, BILLS, RULES
sys.path.insert(0, str(RUN))
from probe import App


def inventory(c, java):
    result = base.inventory(c, java)
    pi = "pi" if java else "pi_no"
    for table in ("sn_acquire_batch", "sn_print"):
        c.execute(f"SELECT * FROM {table} WHERE {pi} NOT IN (%s,%s)", PIS)
        result[table] = base.common.digest(c.fetchall())
    if java:
        for table, parent, fk in (
            ("sn_acquire_member", "sn_acquire_batch", "batch_id"),
            ("sn_print_member", "sn_print", "print_id"),
        ):
            c.execute(
                f"SELECT m.* FROM {table} m JOIN {parent} p ON p.id=m.{fk} WHERE p.pi NOT IN (%s,%s)",
                PIS,
            )
            result[table] = base.common.digest(c.fetchall())
        c.execute(
            "SELECT id,username,password_hash,security_version,must_change_password FROM sys_user"
        )
    else:
        c.execute(
            "SELECT id,emp_no,password_hash,must_change_pwd,role,factory_code,status FROM sn_user"
        )
    result["accounts"] = base.common.digest(c.fetchall())
    c.execute(
        "SELECT * FROM sn_transfer WHERE from_pi NOT IN (%s,%s) AND to_pi NOT IN (%s,%s)",
        PIS + PIS,
    )
    result["sn_transfer"] = base.common.digest(c.fetchall())
    members = "sn_transfer_member" if java else "sn_transfer_item"
    c.execute(
        f"SELECT m.* FROM {members} m JOIN sn_transfer t ON t.id=m.transfer_id WHERE t.from_pi NOT IN (%s,%s) AND t.to_pi NOT IN (%s,%s)",
        PIS + PIS,
    )
    result[members] = base.common.digest(c.fetchall())
    if java:
        for table, column in (
            ("sn_transfer_work", "from_pi"),
            ("sn_acquire_exception", "pi"),
        ):
            c.execute(f"SELECT * FROM {table} WHERE {column} NOT IN (%s,%s)", PIS)
            result[table] = base.common.digest(c.fetchall())
    return result


def observe(c, java, profile="small"):
    result = {}
    c.execute(
        "SELECT sn,pi,seq_dec,status,material FROM sn_item WHERE pi IN (%s,%s) ORDER BY sn"
        if java
        else "SELECT sn,pi_no pi,seq_dec,status,material_code material FROM sn_item WHERE pi_no IN (%s,%s) ORDER BY sn",
        PIS,
    )
    result["items"] = c.fetchall()
    for table in ("sn_acquire_batch", "sn_print"):
        c.execute(
            f"SELECT pi,quantity qty,factory_code FROM {table} WHERE pi IN (%s,%s) ORDER BY pi"
            if java
            else f"SELECT pi_no pi,qty,factory_code FROM {table} WHERE pi_no IN (%s,%s) ORDER BY pi_no",
            PIS,
        )
        result[table] = c.fetchall()
    if profile in ("transfer", "lifecycle", "hint", "duplicates", "query-scope"):
        c.execute(
            "SELECT i.sn,i.pi,f.code factory,i.status,(i.current_batch_id IS NOT NULL) hasBatch,(i.current_print_id IS NOT NULL) hasPrint FROM sn_item i JOIN sn_factory f ON f.id=i.factory_id WHERE i.pi=%s ORDER BY i.sn"
            if java
            else "SELECT sn,pi_no pi,factory_code factory,status,(batch_no IS NOT NULL) hasBatch,(print_no IS NOT NULL) hasPrint FROM sn_item WHERE pi_no=%s ORDER BY sn",
            (PIS[0],),
        )
        result["current"] = c.fetchall()
        c.execute(
            "SELECT mode,status,quantity qty,from_factory_code from_factory,to_factory_code to_factory,from_pi,to_pi,target_source targetSource FROM sn_transfer WHERE from_pi=%s ORDER BY created_at"
            if java
            else "SELECT mode,status,qty,from_factory,to_factory,from_pi,to_pi,target_source targetSource FROM sn_transfer WHERE from_pi=%s ORDER BY applied_at,id",
            (PIS[0],),
        )
        result["transfers"] = c.fetchall()
        if profile in ("hint", "duplicates"):
            c.execute(
                "SELECT i.sn,i.pi,i.seq_pi,i.seq_dec,f.code factory,i.status,i.material FROM sn_item i LEFT JOIN sn_factory f ON f.id=i.factory_id WHERE i.pi IN (%s,%s) ORDER BY i.seq_pi,i.seq_dec"
                if java
                else "SELECT sn,pi_no pi,seq_pi_no seq_pi,seq_dec,factory_code factory,status,material_code material FROM sn_item WHERE pi_no IN (%s,%s) ORDER BY seq_pi_no,seq_dec",
                PIS,
            )
            result["crossPiItems"] = c.fetchall()
            c.execute(
                "SELECT pi,last_seq seq FROM sn_pi_counter WHERE pi IN (%s,%s) ORDER BY pi"
                if java
                else "SELECT pi_no pi,last_seq_dec seq FROM sn_pi_counter WHERE pi_no IN (%s,%s) ORDER BY pi_no",
                PIS,
            )
            result["counters"] = {pi: 0 for pi in PIS}
            result["counters"].update({r["pi"]: int(r["seq"]) for r in c.fetchall()})
        if profile == "lifecycle":
            c.execute(
                "SELECT mode,status,quantity qty,scope,reason,actor_name actor,decided_name decider,COALESCE(note,'') note FROM sn_transfer WHERE from_pi=%s ORDER BY created_at,id"
                if java
                else "SELECT mode,status,qty,scope,reason,applied_by actor,decided_by decider,COALESCE(decide_note,'') note FROM sn_transfer WHERE from_pi=%s ORDER BY applied_at,id",
                (PIS[0],),
            )
            result["decisions"] = c.fetchall()
    return result


def generate(app, bill, quantity):
    """通过公开生成/分配接口准备本夹具数据，不直接写 SN 表。"""
    orders = app.call(
        "GET", "/sn/orders?size=100" if app.java else "/orders?page_size=100"
    )["items"]
    order = next(row for row in orders if row.get("billNo", row.get("bill_no")) == bill)
    before = (
        0
        if app.java
        else int(
            app.call("GET", "/generate/context?bill_no=" + bill).get("generated_qty", 0)
        )
    )
    preview = app.call(
        "POST",
        "/sn/generation/previews" if app.java else "/generate/preview",
        {"billId": order["id"], "quantity": str(quantity)}
        if app.java
        else {"bill_no": bill, "qty": quantity},
    )
    if app.java:
        app.task(
            app.call(
                "POST",
                "/sn/generation",
                {"operationId": str(uuid.uuid4()), "previewId": preview["id"]},
            )
        )
        app.task(
            app.call(
                "POST",
                "/sn/allocations",
                {
                    "operationId": str(uuid.uuid4()),
                    "billId": order["id"],
                    "expectedQuantity": str(quantity),
                },
            )
        )
    else:
        app.call(
            "POST",
            "/generate",
            {"preview_token": preview["token"], "bill_no": bill, "qty": quantity},
        )
        deadline = time.monotonic() + 60
        while True:
            status = app.call("GET", "/generate/context?bill_no=" + bill)
            if int(status.get("generated_qty", 0)) == before + quantity:
                break
            if time.monotonic() > deadline:
                raise RuntimeError("Owned source generation did not finish")
            time.sleep(0.5)
        app.call("POST", "/generate/allocate", {"bill_no": bill})
    return order


def history(kind):
    """十条额外历史由真实业务接口准备；界面负责逐页查看与详情验证。"""
    app = App(kind).login()
    try:
        for _ in range(10):
            order = generate(app, BILLS[0], 1)
            for action in ("take", "print"):
                body = (
                    {
                        "operationId": str(uuid.uuid4()),
                        "factoryId": order["factoryId"],
                        "pi": PIS[0],
                    }
                    if app.java
                    else {
                        "request_no": str(uuid.uuid4()),
                        "factory_code": "CMP-A",
                        "pi_no": PIS[0],
                    }
                )
                result = app.call(
                    "POST",
                    ("/sn/acquisitions" if action == "take" else "/sn/prints")
                    if app.java
                    else "/acquire/" + action,
                    body,
                )
                if app.java:
                    app.task(result)
    finally:
        app.c.close()


def seed(kind, profile):
    app = App(kind).login()
    try:
        for index, (pi, bill, rule) in enumerate(zip(PIS, BILLS, RULES)):
            code = "A17A" if index == 0 or profile in ("hint", "duplicates") else "A17B"
            if profile == "query":
                code = "Q28A" if index == 0 else "Q28B"
            elif profile == "query-scope":
                code = "Q29A" if index == 0 else "Q29B"
            app.call(
                "POST",
                "/sn/rules" if app.java else "/rules",
                {
                    "code": rule,
                    "name": rule,
                    "bindScope": "PI",
                    "bindValue": pi,
                    "prefix": code,
                    "suffix": "",
                    "base": 10,
                    "length": 5,
                    "alphabet": "",
                }
                if app.java
                else {
                    "rule_code": rule,
                    "rule_name": rule,
                    "bind_scope": "PI",
                    "bind_value": pi,
                    "prefix": code,
                    "suffix": "",
                    "base": 10,
                    "seq_len": 5,
                    "charset": "",
                },
            )
            if profile == "hint" and index == 1:
                continue
            generate(
                app,
                bill,
                51
                if profile in ("history", "lifecycle", "query") and index == 0
                else 6
                if profile == "duplicates"
                else 3,
            )
        if profile == "query-scope":
            # 仅为查询准备一枚跨厂成员；转厂功能的界面验收独立记录。
            factories = app.call("GET", "/sn/factories")["items"] if app.java else []
            ids = {row["code"]: row["id"] for row in factories}
            result = app.call(
                "POST",
                "/sn/transfers/direct" if app.java else "/transfers/direct",
                {
                    "operationId": str(uuid.uuid4()),
                    "factoryId": ids["CMP-A"],
                    "pi": PIS[0],
                    "scope": "SINGLE",
                    "sn": "Q29A00003",
                    "toFactoryId": ids["CMP-B"],
                    "toPi": PIS[0],
                    "toCustomer": "",
                    "toMaterial": "",
                    "targetSource": "MANUAL",
                    "reason": "CMP_UI_QUERY_SCOPE",
                }
                if app.java
                else {
                    "factory_code": "CMP-A",
                    "pi_no": PIS[0],
                    "scope": "SINGLE",
                    "sn": "Q29A00003",
                    "to_factory": "CMP-B",
                    "to_pi": PIS[0],
                    "to_customer": "",
                    "to_material": "",
                    "target_source": "MANUAL",
                    "reason": "CMP_UI_QUERY_SCOPE",
                },
            )
            if app.java:
                app.task(result)
    finally:
        app.c.close()


def clean_query_scope_transfer(c, java):
    """仅清理本夹具的一枚同 PI 跨厂成员；未知转厂或非终态均保留并报错。"""
    c.execute(
        "SELECT * FROM sn_transfer WHERE from_pi IN (%s,%s) OR to_pi IN (%s,%s)",
        PIS + PIS,
    )
    transfers = c.fetchall()
    if len(transfers) > 1:
        raise RuntimeError("Unexpected query scope transfer count")
    for row in transfers:
        if not (
            row["from_pi"] == row["to_pi"] == PIS[0]
            and row["from_factory_code" if java else "from_factory"] == "CMP-A"
            and row["to_factory_code" if java else "to_factory"] == "CMP-B"
            and row["reason"] == "CMP_UI_QUERY_SCOPE"
            and row["mode"] == "DIRECT"
            and row["status"] == "APPROVED"
            and row["scope"] == "SINGLE"
            and int(row["quantity" if java else "qty"]) == 1
            and row["target_source"] == "MANUAL"
            and row["to_customer"] == row["to_material"] == ""
        ):
            raise RuntimeError("Unexpected query scope transfer ownership")
        members = "sn_transfer_member" if java else "sn_transfer_item"
        c.execute(
            f"SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.sn')) sn FROM {members} WHERE transfer_id=%s"
            if java
            else f"SELECT sn FROM {members} WHERE transfer_id=%s",
            (row["id"],),
        )
        if [r["sn"] for r in c.fetchall()] != ["Q29A00003"]:
            raise RuntimeError("Unexpected query scope membership")
        if java:
            c.execute(
                "SELECT sn FROM sn_item WHERE acquire_exception_id=%s", (row["id"],)
            )
            if not {r["sn"] for r in c.fetchall()} <= {"Q29A00003"}:
                raise RuntimeError("Unexpected query scope exception")
            c.execute(
                "DELETE FROM sn_acquire_exception WHERE transfer_id=%s AND pi=%s",
                (row["id"], PIS[0]),
            )
            c.execute(
                "DELETE FROM sn_transfer_work WHERE transfer_id=%s AND from_pi=%s AND to_pi=%s",
                (row["id"], PIS[0], PIS[0]),
            )
        c.execute(f"DELETE FROM {members} WHERE transfer_id=%s", (row["id"],))
        c.execute("DELETE FROM sn_transfer WHERE id=%s", (row["id"],))


def clean_transfers(c, java):
    """仅移除本轮声明的三次同 PI 跨厂往返，任意其他目的地/成员均拒绝清理。"""
    c.execute(
        "SELECT * FROM sn_transfer WHERE from_pi IN (%s,%s) OR to_pi IN (%s,%s)",
        PIS + PIS,
    )
    transfers = c.fetchall()
    if len(transfers) > 3:
        raise RuntimeError("Unexpected transfer count")
    for row in transfers:
        source = row["from_factory_code" if java else "from_factory"]
        target = row["to_factory_code" if java else "to_factory"]
        if not (
            row["from_pi"] == row["to_pi"] == PIS[0]
            and {source, target} == {"CMP-A", "CMP-B"}
            and row["reason"] == "CMP_UI_TRANSFER_PRINT"
            and row["status"] == "APPROVED"
            and row["mode"] == "DIRECT"
            and row["scope"] in ("PI", "SINGLE")
        ):
            raise RuntimeError("Unexpected transfer ownership")
        members = "sn_transfer_member" if java else "sn_transfer_item"
        c.execute(
            f"SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.sn')) sn FROM {members} WHERE transfer_id=%s"
            if java
            else f"SELECT sn FROM {members} WHERE transfer_id=%s",
            (row["id"],),
        )
        sns = [m["sn"] for m in c.fetchall()]
        if len(sns) != int(row["quantity" if java else "qty"]) or not set(sns) <= {
            f"A17A{n:05}" for n in range(1, 4)
        }:
            raise RuntimeError("Unexpected transfer members")
        if java:
            c.execute(
                "SELECT sn FROM sn_item WHERE acquire_exception_id=%s", (row["id"],)
            )
            if not {r["sn"] for r in c.fetchall()} <= set(sns):
                raise RuntimeError("Unexpected exception owner")
            c.execute(
                "DELETE FROM sn_acquire_exception WHERE transfer_id=%s AND pi=%s",
                (row["id"], PIS[0]),
            )
            c.execute(
                "DELETE FROM sn_transfer_work WHERE transfer_id=%s AND from_pi=%s AND to_pi=%s",
                (row["id"], PIS[0], PIS[0]),
            )
        c.execute(f"DELETE FROM {members} WHERE transfer_id=%s", (row["id"],))
        c.execute("DELETE FROM sn_transfer WHERE id=%s", (row["id"],))


def clean_lifecycle_transfers(c, java):
    """只清理三张实名申请的终态；不接受待审批、未知方向或超出固定成员的记录。"""
    expected = {
        "CMP_UI_TRANSFER_WITHDRAW": ("WITHDRAWN", "PI", 51),
        "CMP_UI_TRANSFER_REJECT": ("REJECTED", "SINGLE", 1),
        "CMP_UI_TRANSFER_APPROVE": ("APPROVED", "PI", 51),
    }
    c.execute(
        "SELECT * FROM sn_transfer WHERE from_pi IN (%s,%s) OR to_pi IN (%s,%s)",
        PIS + PIS,
    )
    rows = c.fetchall()
    if len(rows) > 3 or len({r["reason"] for r in rows}) != len(rows):
        raise RuntimeError("Unexpected lifecycle transfer count")
    members = "sn_transfer_member" if java else "sn_transfer_item"
    for row in rows:
        if not (
            row["mode"] == "APPLY"
            and row["from_pi"] == row["to_pi"] == PIS[0]
            and row["from_factory_code" if java else "from_factory"] == "CMP-A"
            and row["to_factory_code" if java else "to_factory"] == "CMP-B"
            and (row["status"], row["scope"], int(row["quantity" if java else "qty"]))
            == expected.get(row["reason"])
        ):
            raise RuntimeError("Unexpected lifecycle owner or unfinished decision")
        c.execute(
            f"SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.sn')) sn FROM {members} WHERE transfer_id=%s"
            if java
            else f"SELECT sn FROM {members} WHERE transfer_id=%s",
            (row["id"],),
        )
        sns = [m["sn"] for m in c.fetchall()]
        required = (
            {f"A17A{n:05}" for n in range(1, 52)}
            if row["scope"] == "PI"
            else {"A17A00001"}
        )
        if set(sns) != required or len(sns) != len(required):
            raise RuntimeError("Unexpected lifecycle fixed membership")
        if java:
            c.execute(
                "SELECT sn FROM sn_item WHERE acquire_exception_id=%s", (row["id"],)
            )
            if not {r["sn"] for r in c.fetchall()} <= required:
                raise RuntimeError("Unexpected lifecycle exception reference")
    for row in rows:
        if java:
            c.execute(
                "DELETE FROM sn_acquire_exception WHERE transfer_id=%s AND pi=%s",
                (row["id"], PIS[0]),
            )
            c.execute(
                "DELETE FROM sn_transfer_work WHERE transfer_id=%s AND from_pi=%s AND to_pi=%s",
                (row["id"], PIS[0], PIS[0]),
            )
        c.execute(f"DELETE FROM {members} WHERE transfer_id=%s", (row["id"],))
        c.execute("DELETE FROM sn_transfer WHERE id=%s", (row["id"],))


def clean_cross_pi_transfers(c, java, profile):
    """跨 PI 夹具仅允许三枚自有号分两次直接转入 B；重复号检查不能产生转厂。"""
    c.execute(
        "SELECT * FROM sn_transfer WHERE from_pi IN (%s,%s) OR to_pi IN (%s,%s)",
        PIS + PIS,
    )
    transfers = c.fetchall()
    if len(transfers) > (2 if profile == "hint" else 0):
        raise RuntimeError("Unexpected cross PI transfer count")
    expected = {
        "CMP_UI_XPI_SINGLE": {"A17A00003"},
        "CMP_UI_XPI_REST": {"A17A00001", "A17A00002"},
    }
    reasons = set()
    for row in transfers:
        if row["reason"] not in expected or row["reason"] in reasons:
            raise RuntimeError("Unexpected cross PI reason")
        reasons.add(row["reason"])
        if not (
            row["from_pi"] == PIS[0]
            and row["to_pi"] == PIS[1]
            and row["from_factory_code" if java else "from_factory"] == "CMP-A"
            and row["to_factory_code" if java else "to_factory"] == "CMP-B"
            and row["mode"] == "DIRECT"
            and row["status"] == "APPROVED"
            and row["scope"]
            == ("SINGLE" if row["reason"] == "CMP_UI_XPI_SINGLE" else "PI")
            and row["target_source"] == "MANUAL"
            and row["to_customer"] == row["to_material"] == ""
        ):
            raise RuntimeError("Unexpected cross PI ownership")
        members = "sn_transfer_member" if java else "sn_transfer_item"
        c.execute(
            f"SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.sn')) sn FROM {members} WHERE transfer_id=%s"
            if java
            else f"SELECT sn FROM {members} WHERE transfer_id=%s",
            (row["id"],),
        )
        sns = [r["sn"] for r in c.fetchall()]
        if set(sns) != expected[row["reason"]] or len(sns) != int(
            row["quantity" if java else "qty"]
        ):
            raise RuntimeError("Unexpected cross PI members")
        if java:
            c.execute(
                "SELECT sn FROM sn_item WHERE acquire_exception_id=%s", (row["id"],)
            )
            if not {r["sn"] for r in c.fetchall()} <= set(sns):
                raise RuntimeError("Unexpected cross PI exception")
            c.execute(
                "DELETE FROM sn_acquire_exception WHERE transfer_id=%s AND pi=%s",
                (row["id"], PIS[1]),
            )
            c.execute(
                "DELETE FROM sn_transfer_work WHERE transfer_id=%s AND from_pi=%s AND to_pi=%s",
                (row["id"], *PIS),
            )
        c.execute(f"DELETE FROM {members} WHERE transfer_id=%s", (row["id"],))
        c.execute("DELETE FROM sn_transfer WHERE id=%s", (row["id"],))


def clean(c, java, profile):
    pi = "pi" if java else "pi_no"
    c.execute(f"SELECT * FROM sn_item WHERE {pi} IN (%s,%s)", PIS)
    items = c.fetchall()
    for row in items:
        index = PIS.index(
            row["seq_pi" if java else "seq_pi_no"]
            if profile in ("hint", "duplicates")
            else row[pi]
        )
        prefix = "A" if index == 0 or profile in ("hint", "duplicates") else "B"
        if (
            row["sn"]
            != f"{'Q28' if profile == 'query' else 'Q29' if profile == 'query-scope' else 'A17'}{prefix}{row['seq_dec']:05}"
            or row["bill_no"] != BILLS[index]
            or row["source"] != "GEN"
            or row["status"]
            not in ("PENDING_ALLOC", "TO_ACQUIRE", "TO_PRINT", "PRINTED")
        ):
            raise RuntimeError("Unexpected owned SN; cleanup refused")
    caps = (
        (6, 6)
        if profile == "duplicates"
        else (3, 0)
        if profile == "hint"
        else (61, 3)
        if profile in ("history", "query")
        else (51, 3)
        if profile == "lifecycle"
        else (3, 3)
    )
    for row in items:
        origin = (
            row["seq_pi" if java else "seq_pi_no"]
            if profile in ("hint", "duplicates")
            else row[pi]
        )
        if not 1 <= int(row["seq_dec"]) <= caps[PIS.index(origin)]:
            raise RuntimeError("Unexpected fixture sequence; cleanup refused")
    if len(items) > sum(caps):
        raise RuntimeError("Unexpected fixture quantity")
    if java:
        c.execute(
            "SELECT COUNT(*) n FROM sys_task WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')"
        )
        if c.fetchone()["n"]:
            raise RuntimeError("Tasks still active; preserve fixture")
    c.execute(
        "SELECT COUNT(*) n FROM sn_order_bill WHERE bill_no IN (%s,%s,%s) AND active=1"
        if java
        else "SELECT COUNT(*) n FROM sn_prd_mo WHERE bill_no IN (%s,%s,%s)",
        BILLS,
    )
    if c.fetchone()["n"]:
        raise RuntimeError("Restore synthetic order snapshot through UI before cleanup")
    if profile == "transfer":
        clean_transfers(c, java)
    elif profile == "lifecycle":
        clean_lifecycle_transfers(c, java)
    elif profile in ("hint", "duplicates"):
        clean_cross_pi_transfers(c, java, profile)
    elif profile == "query-scope":
        clean_query_scope_transfer(c, java)
    # 拒绝未知下游引用；真实任务与审计保留，仅删除明确拥有的业务夹具。
    allowed = {
        "sn_item",
        "sn_key",
        "sn_pi_counter",
        "sn_work",
        "sn_preview",
        "sn_pi_rule_choice",
        "sn_order_bill",
        "sn_bill_generation",
        "sn_gen_preview",
        "sn_gen_job",
        "sn_allocation",
        "sn_bill_gen",
        "sn_prd_mo",
        "sn_acquire_batch",
        "sn_acquire_work",
        "sn_acquire_member",
        "sn_print",
        "sn_audit",
        "sn_audit_archive",
    }
    if java and profile in ("query", "query-scope"):
        # 查询导出为不可变历史快照，无业务外键；核对本夹具身份后保留文件与任务。
        c.execute(
            "SELECT r.pi,r.cells,e.format_version FROM sn_query_export_row r "
            "JOIN sn_query_export e ON e.task_id=r.task_id WHERE r.pi IN (%s,%s)",
            PIS,
        )
        for export in c.fetchall():
            cells = json.loads(export["cells"])
            index = PIS.index(export["pi"])
            if (
                export["format_version"] != 2
                or len(cells) != 11
                or cells[1]
                != f"{'Q28' if profile == 'query' else 'Q29'}{'A' if index == 0 else 'B'}{int(cells[3]):05}"
                or cells[4] != export["pi"]
                or cells[10] != BILLS[index]
                or not 1
                <= int(cells[3])
                <= (51 if profile == "query" and index == 0 else 3)
            ):
                raise RuntimeError("Unexpected query snapshot; cleanup refused")
        allowed.add("sn_query_export_row")
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
                raise RuntimeError("Unknown downstream fixture references")
    if java:
        for table, parent, fk, pk in (
            ("sn_print_member", "sn_print", "print_id", "id"),
            ("sn_acquire_member", "sn_acquire_batch", "batch_id", "id"),
            ("sn_acquire_result", "sn_acquire_work", "task_id", "task_id"),
        ):
            c.execute(
                f"DELETE m FROM {table} m JOIN {parent} p ON m.{fk}=p.{pk} WHERE p.pi IN (%s,%s)",
                PIS,
            )
        c.execute(
            "DELETE j FROM sn_generation_job j JOIN sn_work w ON w.task_id=j.task_id WHERE w.pi IN (%s,%s)",
            PIS,
        )
    for table in ("sn_key", "sn_item", "sn_acquire_batch", "sn_print", "sn_pi_counter"):
        c.execute(f"DELETE FROM {table} WHERE {pi} IN (%s,%s)", PIS)
    if java:
        for table in ("sn_acquire_work", "sn_preview", "sn_work", "sn_pi_rule_choice"):
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
        for table in ("sn_gen_preview", "sn_gen_job", "sn_allocation", "sn_bill_gen"):
            c.execute(f"DELETE FROM {table} WHERE pi_no IN (%s,%s)", PIS)
    code = "code" if java else "rule_code"
    c.execute(f"SELECT id,bind_value FROM sn_rule WHERE {code} IN (%s,%s)", RULES)
    for row in c.fetchall():
        if row["bind_value"] not in PIS:
            raise RuntimeError("Unexpected rule owner")
        c.execute("DELETE FROM sn_rule_version WHERE rule_id=%s", (row["id"],))
        c.execute("DELETE FROM sn_rule WHERE id=%s", (row["id"],))


def main():
    global PIS, BILLS, RULES
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument(
        "action", choices=("begin", "seed", "history", "observe", "active", "clean")
    )
    parser.add_argument(
        "--profile",
        choices=(
            "small",
            "history",
            "transfer",
            "lifecycle",
            "hint",
            "duplicates",
            "query",
            "query-scope",
        ),
        default="small",
    )
    args = parser.parse_args()
    if args.profile == "query":
        PIS = ("CMP-UI-QUERY-PI-A", "CMP-UI-QUERY-PI-B")
        BILLS = ("CMP-UI-QUERY-MO-A", "CMP-UI-QUERY-MO-B", "CMP-UI-QUERY-UNUSED")
        RULES = ("CMP_UI_QUERY_A", "CMP_UI_QUERY_B")
        base.PIS, base.BILLS, base.RULES = PIS, BILLS, RULES
    elif args.profile == "query-scope":
        PIS = ("CMP-UI-QUERY-SCOPE-PI-A", "CMP-UI-QUERY-SCOPE-PI-B")
        BILLS = (
            "CMP-UI-QUERY-SCOPE-MO-A",
            "CMP-UI-QUERY-SCOPE-MO-B",
            "CMP-UI-QUERY-SCOPE-UNUSED",
        )
        RULES = ("CMP_UI_QUERY_SCOPE_A", "CMP_UI_QUERY_SCOPE_B")
        base.PIS, base.BILLS, base.RULES = PIS, BILLS, RULES
    java = args.kind == "java"
    snapshot = RUN / (
        f"acquire-query-scope-fixture-{args.kind}.json"
        if args.profile == "query-scope"
        else f"acquire-query-fixture-{args.kind}.json"
        if args.profile == "query"
        else f"acquire-impact-{args.profile}-fixture-{args.kind}.json"
        if args.profile in ("hint", "duplicates")
        else f"acquire-lifecycle-fixture-{args.kind}.json"
        if args.profile == "lifecycle"
        else f"acquire-history-fixture-{args.kind}.json"
        if args.profile == "history"
        else f"acquire-transfer-fixture-{args.kind}.json"
        if args.profile == "transfer"
        else f"acquire-fixture-{args.kind}.json"
    )
    if args.action in ("seed", "history"):
        if not snapshot.exists():
            raise RuntimeError("Begin owned fixture first")
        if args.action == "history":
            if args.profile != "history":
                raise RuntimeError("History preparation requires its declared profile")
            history(args.kind)
        else:
            seed(args.kind, args.profile)
        return
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
            if list(RUN.glob("acquire*fixture-*.json")):
                raise RuntimeError("Previous fixture remains")
            pi = "pi" if java else "pi_no"
            c.execute(
                "SELECT COUNT(*) n FROM sn_transfer WHERE from_pi IN (%s,%s) OR to_pi IN (%s,%s)",
                PIS + PIS,
            )
            if c.fetchone()["n"]:
                raise RuntimeError("Unowned transfer exists")
            for table in (
                "sn_item",
                "sn_key",
                "sn_pi_counter",
                "sn_acquire_batch",
                "sn_print",
            ):
                c.execute(f"SELECT COUNT(*) n FROM {table} WHERE {pi} IN (%s,%s)", PIS)
                if c.fetchone()["n"]:
                    raise RuntimeError("Unowned PI exists")
            snapshot.write_text(json.dumps(inventory(c, java)), encoding="utf-8")
        elif args.action == "active":
            # 只读等待真实工作器终态；不清任务、不缩短租期，也不降低 clean 的保护条件。
            if java:
                c.execute(
                    "SELECT COUNT(*) n FROM sys_task WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')"
                )
                print(c.fetchone()["n"])
            else:
                print(0)
        elif args.action == "observe":
            print(json.dumps(observe(c, java, args.profile), default=str))
        else:
            expected = json.loads(snapshot.read_text())
            if inventory(c, java) != expected:
                raise RuntimeError("Original business rows changed; cleanup refused")
            clean(c, java, args.profile)
            if inventory(c, java) != expected:
                raise RuntimeError("Original state changed; transaction rolled back")
            con.commit()
            (RUN / f"acquire-clean-{args.kind}.json").write_text(
                json.dumps(
                    {"originalRowsUnchanged": expected, "ownedFixtureRemoved": True},
                    indent=2,
                )
            )
            snapshot.unlink()


if __name__ == "__main__":
    main()
