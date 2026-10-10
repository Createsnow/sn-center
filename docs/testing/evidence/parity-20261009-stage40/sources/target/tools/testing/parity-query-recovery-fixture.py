"""Inspect only new query-scope exports and quarantine one owned file for real UI recovery."""

import argparse
import hashlib
import json
import re
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "tools/.runtime/comparison-20261008"
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
FILES = (ROOT / "deploy/data/comparison-20261008/files/objects").resolve()
MARKER = RUN / "query-recovery-fixture.json"
QUARANTINE = (RUN / "query-recovery-quarantine").resolve()
PIS = ("CMP-UI-QUERY-SCOPE-PI-A", "CMP-UI-QUERY-SCOPE-PI-B")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(value):
    MARKER.write_text(json.dumps(value, indent=2), encoding="utf-8")


def ids(c):
    c.execute(
        "SELECT id FROM sys_task WHERE task_type IN ('SN_QUERY_CSV','SN_QUERY_XLSX')"
    )
    return {row["id"] for row in c.fetchall()}


def owned(c, task_id, baseline):
    if not UUID.fullmatch(task_id) or task_id in baseline["taskIds"]:
        raise RuntimeError("Only newly created fixture exports may be inspected")
    c.execute(
        "SELECT e.task_id id,e.format,e.filters,e.quantity,e.format_version,"
        "t.state task_state,t.execution_epoch,o.operation_id,o.state operation_state,"
        "f.state file_state,f.storage_key,f.sha256,f.byte_size FROM sn_query_export e "
        "JOIN sys_task t ON t.id=e.task_id JOIN sys_operation o ON o.task_id=t.id "
        "JOIN sys_file f ON f.task_id=t.id WHERE e.task_id=%s",
        (task_id,),
    )
    row = c.fetchone()
    if not row or row["format_version"] != 2 or row["quantity"] != 6:
        raise RuntimeError("Unexpected query recovery export")
    filters = json.loads(row.pop("filters"))
    if filters.get("customer") != "CMP-QUERY-SCOPE-CUSTOMER":
        raise RuntimeError("Unexpected query recovery filter ownership")
    c.execute(
        "SELECT ordinal,pi,cells FROM sn_query_export_row WHERE task_id=%s ORDER BY ordinal",
        (task_id,),
    )
    rows = c.fetchall()
    expected = [f"Q29{letter}{n:05}" for letter in ("A", "B") for n in range(1, 4)]
    if len(rows) != 6:
        raise RuntimeError("Unexpected query recovery members")
    for index, member in enumerate(rows):
        cells = json.loads(member["cells"])
        if not (
            member["ordinal"] == index + 1
            and len(cells) == 11
            and cells[1] == expected[index]
            and member["pi"] == cells[4] == PIS[index // 3]
            and cells[10] == "CMP-UI-QUERY-SCOPE-MO-" + ("A" if index < 3 else "B")
        ):
            raise RuntimeError("Unexpected fixed export contents")
    row["snapshot_digest"] = hashlib.sha256(
        json.dumps(rows, sort_keys=True).encode()
    ).hexdigest()
    key = row["storage_key"]
    if key != task_id + "." + row["format"].lower():
        raise RuntimeError("Unexpected recovery storage key")
    return row


def object_path(key):
    path = FILES / key
    if path.is_symlink() or path.resolve().parent != FILES or FILES.is_symlink():
        raise RuntimeError("Storage path escaped comparison directory")
    return path


def backup_path(key):
    if QUARANTINE.parent != RUN.resolve() or QUARANTINE.is_symlink():
        raise RuntimeError("Unexpected quarantine directory")
    QUARANTINE.mkdir(exist_ok=True)
    path = QUARANTINE / key
    if path.is_symlink() or path.resolve().parent != QUARANTINE:
        raise RuntimeError("Quarantine path escaped comparison directory")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action", choices=("begin", "inspect", "hide", "restore", "finish")
    )
    parser.add_argument("--task")
    args = parser.parse_args()
    if not (RUN / "acquire-query-scope-fixture-java.json").exists():
        raise RuntimeError("Requires current owned query-scope fixture")
    with (
        pymysql.connect(
            host="127.0.0.1",
            port=33308,
            user="java_compare",
            password=(PRIVATE / "mysql-java").read_text().strip(),
            database="sn_java_compare",
            cursorclass=pymysql.cursors.DictCursor,
        ) as con,
        con.cursor() as c,
    ):
        if args.action == "begin":
            if MARKER.exists():
                raise RuntimeError("Previous recovery fixture remains")
            save(
                {
                    "taskIds": sorted(ids(c)),
                    "originalFiles": {
                        p.name: digest(p) for p in FILES.iterdir() if p.is_file()
                    },
                    "hidden": {},
                }
            )
            return
        baseline = json.loads(MARKER.read_text(encoding="utf-8"))
        if args.action in ("hide", "inspect"):
            if not args.task:
                print(
                    json.dumps(
                        [
                            owned(c, task_id, baseline)
                            for task_id in sorted(ids(c) - set(baseline["taskIds"]))
                        ],
                        default=str,
                    )
                )
                return
            row = owned(c, args.task, baseline)
            if args.action == "hide":
                if (
                    row["task_state"] != "SUCCESS"
                    or row["file_state"] != "READY"
                    or args.task in baseline["hidden"]
                ):
                    raise RuntimeError(
                        "Only a new completed file can be quarantined once"
                    )
                original, backup = (
                    object_path(row["storage_key"]),
                    backup_path(row["storage_key"]),
                )
                if (
                    backup.exists()
                    or digest(original) != row["sha256"]
                    or original.stat().st_size != row["byte_size"]
                ):
                    raise RuntimeError(
                        "Owned file must match committed bytes before quarantine"
                    )
                baseline["hidden"][args.task] = row
                save(baseline)
                # 两个绝对路径均已限定在本比较环境，仅移动一枚新建文件，不递归操作。
                original.rename(backup)
            print(json.dumps(row, default=str))
            return
        if args.action == "restore":
            # 仅失败用例收尾：原文件尚未由产品恢复时归还自有备份，不算产品恢复通过。
            for row in baseline["hidden"].values():
                original, backup = (
                    object_path(row["storage_key"]),
                    backup_path(row["storage_key"]),
                )
                if not original.exists():
                    if digest(backup) != row["sha256"]:
                        raise RuntimeError("Quarantine digest changed")
                    original.write_bytes(backup.read_bytes())
            print("Owned file backups restored for fixture cleanup only")
            return
        current = []
        for task_id in sorted(ids(c) - set(baseline["taskIds"])):
            row = owned(c, task_id, baseline)
            if row["task_state"] != "SUCCESS" or row["file_state"] != "READY":
                raise RuntimeError("Recovery task has not finished")
            if digest(object_path(row["storage_key"])) != row["sha256"]:
                raise RuntimeError("Current recovery file bytes differ")
            if task_id in baseline["hidden"]:
                before = baseline["hidden"][task_id]
                for field in (
                    "operation_id",
                    "sha256",
                    "byte_size",
                    "quantity",
                    "snapshot_digest",
                ):
                    if row[field] != before[field]:
                        raise RuntimeError(
                            "Recovered export identity or fixed bytes changed"
                        )
            current.append(row)
        for key, expected in baseline["originalFiles"].items():
            if digest(object_path(key)) != expected:
                raise RuntimeError("Original comparison file changed")
        result = {
            "newExports": current,
            "originalFilesUnchanged": len(baseline["originalFiles"]),
            "hiddenFiles": len(baseline["hidden"]),
        }
        print(json.dumps(result, default=str))
        (RUN / "query-recovery-finished.json").write_text(
            json.dumps(result, indent=2, default=str), encoding="utf8"
        )
        MARKER.unlink()


if __name__ == "__main__":
    main()
