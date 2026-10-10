"""Create and remove only owned account-directory fixtures in the two loopback comparison databases."""

import argparse
import importlib.util
import json
from pathlib import Path
import secrets
import time

import httpx
import pymysql

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
spec = importlib.util.spec_from_file_location("auth_fixture", Path(__file__).with_name("parity-auth-fixture.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument("action", choices=("prepare", "clean"))
    args = parser.parse_args()
    java = args.kind == "java"
    path = PRIVATE / ("users-directory-" + args.kind + ".json")
    with pymysql.connect(host="127.0.0.1", port=33308, user=args.kind+"_compare",
                         password=(PRIVATE / ("mysql-"+args.kind)).read_text().strip(),
                         database="sn_"+args.kind+"_compare", cursorclass=pymysql.cursors.DictCursor) as con, con.cursor() as c:
        if args.action == "clean":
            state = json.loads(path.read_text("utf8"))
            current = common.accounts(c, java)
            ids = set(state["created"])
            assert common.digest([r for r in current if str(r["id"]) not in ids]) == common.digest(state["before"])
            for row in current:
                if str(row["id"]) not in ids:
                    continue
                assert row["username" if java else "emp_no"] in state["names"]
                if java:
                    c.execute("SELECT COUNT(*) n FROM sys_task WHERE user_id=%s", (row["id"],))
                    assert c.fetchone()["n"] == 0
                    c.execute("DELETE FROM sn_user_factory WHERE user_id=%s", (row["id"],))
                    c.execute("DELETE FROM sys_user_role WHERE user_id=%s", (row["id"],))
                c.execute("DELETE FROM sys_user WHERE id=%s" if java else "DELETE FROM sn_user WHERE id=%s", (row["id"],))
            con.commit()
            assert common.digest(common.accounts(c, java)) == common.digest(state["before"])
            path.rename(path.with_name(path.stem + f".{time.time_ns()}.completed.json"))
            print(args.kind+": removed only owned accounts; retained real audit records")
            return
        assert not path.exists(), "Existing fixture requires inspection/cleanup"
        before = common.accounts(c, java)
        names = [f"cmp_dir39_{n:02d}" for n in range(27)]
        assert not any(r["username" if java else "emp_no"] in names for r in before)
        state = {"before": before, "created": [], "names": names, "rows": [], "password": "Dir9!"+secrets.token_hex(18)}
        path.write_text(json.dumps(state, default=str), "utf8")
        with httpx.Client(base_url="http://127.0.0.1:"+("18080" if java else "8000"), trust_env=False, timeout=30) as client:
            token = None

            def call(method, route, body=None):
                headers = {"Authorization": "Bearer "+token} if token else {}
                if java and method != "GET":
                    csrf = client.get("/api/v1/system/auth/csrf").json()["data"]
                    headers[csrf["headerName"]] = csrf["token"]
                result = client.request(method, ("/api/v1" if java else "/api")+route, json=body, headers=headers)
                if not result.is_success:
                    raise RuntimeError(f"Fixture HTTP {result.status_code}: {method} {route}")
                return result.json()["data"] if java else result.json()

            result = call("POST", "/system/auth/login" if java else "/auth/login", {
                "username" if java else "emp_no": "admin", "password": (PRIVATE / (args.kind+"-admin-password")).read_text().strip()})
            token = result["accessToken" if java else "token"]
            if java:
                c.execute("SELECT id,role_code FROM sys_role")
                role_ids = {r["role_code"]: str(r["id"]) for r in c.fetchall()}
                factory = next(f["id"] for f in call("GET", "/sn/factories")["items"] if f["code"] == "CMP-A")
            for n in reversed(range(27)):
                role = "factory_operator" if n < 24 else "query" if n < 26 else "admin"
                bound = n < 25
                body = {"username": names[n], "displayName": f"目录成员{n:02d}", "deptId": "1", "password": state["password"],
                        "roleIds": [role_ids[code] for code in (["FACTORY_OPERATOR"] if role == "factory_operator" else ["SN_QUERY_FACTORY" if bound else "SN_QUERY_ALL"] if role == "query" else ["PLATFORM_ADMIN", "SN_HQ"])],
                        "factoryIds": [factory] if bound else []} if java else {
                            "emp_no": names[n], "name": f"目录成员{n:02d}", "password": state["password"], "role": role, "factory_code": "CMP-A" if bound else None}
                user = call("POST", "/system/users" if java else "/users", body)
                state["created"].append(str(user["id"]))
                state["rows"].append(user)
                path.write_text(json.dumps(state, default=str), "utf8")
                if n in (5, 25):
                    call("POST", f"/system/users/{user['id']}/status" if java else f"/users/{user['id']}/disable", {"enabled": False, "version": user["version"]} if java else {})
        print(args.kind+": 27 accounts created through real APIs in reverse name order")


if __name__ == "__main__":
    main()
