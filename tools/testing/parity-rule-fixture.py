"""Snapshot/clean only unused UI-rule fixtures in the dedicated comparison schemas."""

import argparse
import json
from pathlib import Path

import httpx
import pymysql


def seed_catalog(kind, private):
    """批量数据只用于验证连续展示；新增/编辑业务另由浏览器按钮用例验证。"""
    java = kind == "java"
    with httpx.Client(
        base_url="http://127.0.0.1:" + ("18080" if java else "8000"),
        trust_env=False,
        timeout=30,
    ) as client:
        prefix = "/api/v1" if java else "/api"

        def post(path, body, token=None):
            headers = {"Authorization": "Bearer " + token} if token else {}
            if java:
                csrf = client.get(prefix + "/system/auth/csrf").json()["data"]
                headers[csrf["headerName"]] = csrf["token"]
            response = client.post(prefix + path, json=body, headers=headers)
            if not response.is_success:
                raise RuntimeError(
                    f"Comparison catalog request rejected: {response.status_code}"
                )
            data = response.json()
            return data["data"] if java else data

        login = post(
            "/system/auth/login" if java else "/auth/login",
            {
                "username" if java else "emp_no": "admin",
                "password": (private / f"{kind}-admin-password")
                .read_text(encoding="utf-8")
                .strip(),
            },
        )
        token = login["accessToken" if java else "token"]
        for index in range(101):
            code = f"CMP_UI_R_BULK_{index:03}"
            body = (
                {
                    "code": code,
                    "name": code,
                    "bindScope": "PI",
                    "bindValue": code,
                    "prefix": "UI",
                    "suffix": "",
                    "base": 10,
                    "length": 5,
                    "alphabet": "",
                }
                if java
                else {
                    "rule_code": code,
                    "rule_name": code,
                    "bind_scope": "PI",
                    "bind_value": code,
                    "prefix": "UI",
                    "suffix": "",
                    "base": 10,
                    "seq_len": 5,
                    "charset": "",
                }
            )
            post("/sn/rules" if java else "/rules", body, token)


def clean_used(cursor, current, baseline, code, database, root, kind):
    """恢复本次已使用版本界面测试的规则头；原已发 SN 和 v1 必须逐字段未变。"""
    main_rule = next(r for r in baseline["rules"] if r[code] == "CMP_MAIN")
    current_rule = next(r for r in current["rules"] if r["id"] == main_rule["id"])
    if [r for r in current["rules"] if r["id"] != main_rule["id"]] != [
        r for r in baseline["rules"] if r["id"] != main_rule["id"]
    ]:
        raise SystemExit("Another rule changed during used-version test")
    name = "name" if kind == "java" else "rule_name"
    allowed = {name, "current_version", "revision", "updated_at", "updated_by"}
    if (
        any(current_rule[k] != v for k, v in main_rule.items() if k not in allowed)
        or current_rule[name]
        not in (main_rule[name], baseline.get("used_name", "CMP_UI_R_RENAMED"))
        or current_rule["current_version"] not in (1, 2)
    ):
        raise SystemExit(
            "Unexpected rule-header mutation; refusing fixture restoration"
        )
    old_ids = {v["id"] for v in baseline["versions"]}
    if [v for v in current["versions"] if v["id"] in old_ids] != baseline[
        "versions"
    ] or current["items"] != baseline["items"]:
        raise SystemExit(
            "Original versions or issued SNs changed; refusing restoration"
        )
    additions = [v for v in current["versions"] if v["id"] not in old_ids]
    if len(additions) > 1 or any(
        v["rule_id"] != main_rule["id"]
        or v["version"] != 2
        or v["prefix"] != baseline.get("used_prefix", "CMP_UI_R_USED")
        or v["used"]
        for v in additions
    ):
        raise SystemExit("Unexpected or used new version; refusing restoration")
    for version in additions:
        cursor.execute(
            "SELECT TABLE_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s AND COLUMN_NAME='rule_version_id'",
            (database,),
        )
        for ref in cursor.fetchall():
            cursor.execute(
                f"SELECT COUNT(*) n FROM `{ref['TABLE_NAME']}` WHERE rule_version_id=%s",
                (version["id"],),
            )
            if cursor.fetchone()["n"]:
                raise SystemExit(
                    "New version has business references; refusing restoration"
                )
    cursor.execute(
        "UPDATE sn_rule SET "
        + ",".join(f"`{key}`=%s" for key in main_rule if key != "id")
        + " WHERE id=%s",
        tuple(value for key, value in main_rule.items() if key != "id")
        + (main_rule["id"],),
    )
    for version in additions:
        cursor.execute(
            "DELETE FROM sn_rule_version WHERE id=%s AND used=0", (version["id"],)
        )
    proof = {
        "kind": kind,
        "baselineVersion": 1,
        "observedVersion": current_rule["current_version"],
        "newVersionCount": len(additions),
        "originalVersionsUnchanged": True,
        "issuedSnCount": len(current["items"]),
        "issuedSnRowsUnchanged": True,
    }
    (
        root / f"tools/.runtime/comparison-20261008/stage05-used-restore-{kind}.json"
    ).write_text(json.dumps(proof, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument("--used-prefix", default="CMP_UI_R_USED")
    parser.add_argument("--used-name", default="CMP_UI_R_RENAMED")
    parser.add_argument(
        "action", choices=("begin", "clean", "seed-catalog", "begin-used", "clean-used")
    )
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    private = root / "deploy/.secrets/comparison-20261008"
    snapshot = (
        root / f"tools/.runtime/comparison-20261008/rule-fixture-{args.kind}.json"
    )
    if args.action == "seed-catalog":
        if not snapshot.exists():
            raise SystemExit("Create a comparison-only cleanup snapshot first")
        seed_catalog(args.kind, private)
        print(args.kind + ": created 101 unused comparison rules through public APIs")
        return
    database = f"sn_{args.kind}_compare"
    code = "code" if args.kind == "java" else "rule_code"
    with pymysql.connect(
        host="127.0.0.1",
        port=33308,
        database=database,
        user=f"{args.kind}_compare",
        password=(private / f"mysql-{args.kind}").read_text(encoding="utf-8").strip(),
        cursorclass=pymysql.cursors.DictCursor,
    ) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT * FROM sn_rule ORDER BY id FOR UPDATE")
            rules = cursor.fetchall()
            cursor.execute("SELECT * FROM sn_rule_version ORDER BY id FOR UPDATE")
            versions = cursor.fetchall()
            cursor.execute("SELECT * FROM sn_item ORDER BY id")
            items = cursor.fetchall()
            current = json.loads(
                json.dumps(
                    {"rules": rules, "versions": versions, "items": items}, default=str
                )
            )
            if args.action in ("begin", "begin-used"):
                if snapshot.exists():
                    raise SystemExit(
                        "An earlier rule fixture snapshot needs cleanup first"
                    )
                if any(r[code].startswith("CMP_UI_R_") for r in rules):
                    raise SystemExit(
                        "Unowned comparison rules exist; refusing fixture setup"
                    )
                current["mode"] = args.action
                assert (
                    args.used_prefix.startswith("CMP_UI_R_USED")
                    and len(args.used_prefix) <= 40
                )
                assert (
                    args.used_name.startswith("CMP_UI_R_RENAMED")
                    and len(args.used_name) <= 64
                )
                current["used_prefix"] = args.used_prefix
                current["used_name"] = args.used_name
                if args.action == "begin-used":
                    target = [r for r in rules if r[code] == "CMP_MAIN"]
                    if (
                        len(target) != 1
                        or target[0]["current_version"] != 1
                        or not any(
                            v["rule_id"] == target[0]["id"]
                            and v["version"] == 1
                            and v["used"]
                            for v in versions
                        )
                    ):
                        raise SystemExit(
                            "Expected already-used comparison baseline version is absent"
                        )
                snapshot.write_text(
                    json.dumps(current, ensure_ascii=False, indent=2), encoding="utf-8"
                )
            elif args.action == "clean-used":
                baseline = json.loads(snapshot.read_text(encoding="utf-8"))
                if baseline.get("mode") != "begin-used":
                    raise SystemExit("Snapshot belongs to another rule fixture")
                clean_used(cursor, current, baseline, code, database, root, args.kind)
            else:
                baseline = json.loads(snapshot.read_text(encoding="utf-8"))
                if baseline.get("mode") != "begin":
                    raise SystemExit("Snapshot belongs to another rule fixture")
                baseline_ids = {r["id"] for r in baseline["rules"]}
                if [r for r in current["rules"] if r["id"] in baseline_ids] != baseline[
                    "rules"
                ]:
                    raise SystemExit("Existing rules changed; refusing cleanup")
                if [
                    v for v in current["versions"] if v["rule_id"] in baseline_ids
                ] != baseline["versions"] or current["items"] != baseline["items"]:
                    raise SystemExit(
                        "Existing versions or SNs changed; refusing cleanup"
                    )
                extras = [r for r in rules if r["id"] not in baseline_ids]
                if any(not r[code].startswith("CMP_UI_R_") for r in extras):
                    raise SystemExit("Unowned new rule detected; refusing cleanup")
                for rule in extras:
                    owned = [v for v in versions if v["rule_id"] == rule["id"]]
                    if any(v["used"] for v in owned):
                        raise SystemExit("Test rule was used; refusing cleanup")
                    # 每个显式引用列都检查；不删除任务、审计或任何生成/领取数据。
                    cursor.execute(
                        "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s AND COLUMN_NAME IN ('rule_id','rule_version_id') AND TABLE_NAME<>'sn_rule_version'",
                        (database,),
                    )
                    for ref in cursor.fetchall():
                        ref_ids = (
                            [rule["id"]]
                            if ref["COLUMN_NAME"] == "rule_id"
                            else [v["id"] for v in owned]
                        )
                        for ref_id in ref_ids:
                            cursor.execute(
                                f"SELECT COUNT(*) n FROM `{ref['TABLE_NAME']}` WHERE `{ref['COLUMN_NAME']}`=%s",
                                (ref_id,),
                            )
                            if cursor.fetchone()["n"]:
                                raise SystemExit(
                                    "Test rule has business references; refusing cleanup"
                                )
                    cursor.execute(
                        "DELETE FROM sn_rule_version WHERE rule_id=%s AND used=0",
                        (rule["id"],),
                    )
                    cursor.execute(
                        f"DELETE FROM sn_rule WHERE id=%s AND `{code}`=%s",
                        (rule["id"], rule[code]),
                    )
        connection.commit()
    if args.action in ("clean", "clean-used"):
        snapshot.unlink()
    print(
        f"{args.kind}: {args.action} unused comparison rule fixture; original rules and SNs preserved"
    )


if __name__ == "__main__":
    main()
