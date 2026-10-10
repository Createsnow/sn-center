"""Create disposable authentication accounts through loopback APIs; retain every real audit."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import secrets

import httpx
import pymysql

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
RUN = ROOT / "tools/.runtime/comparison-20261008"


def digest(rows):
    return hashlib.sha256("\n".join(sorted(json.dumps(r, sort_keys=True, default=str) for r in rows)).encode()).hexdigest()


def accounts(c, java):
    c.execute("SELECT id,username,password_hash,enabled,display_name,dept_id,security_version,must_change_password,language FROM sys_user"
              if java else "SELECT id,emp_no,password_hash,status,name,role,factory_code,must_change_pwd,pwd_changed_at,lang FROM sn_user")
    return c.fetchall()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "python"))
    parser.add_argument("action", choices=("prepare", "clean"))
    parser.add_argument("tag")
    parser.add_argument("--language", choices=("zh-CN", "en", "vi"))
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{8,14}", args.tag):
        raise SystemExit("Invalid fixture tag")
    java = args.kind == "java"
    path = PRIVATE / f"auth-fixture-{args.kind}-{args.tag}.json"
    with pymysql.connect(host="127.0.0.1", port=33308, user=args.kind+"_compare",
                         password=(PRIVATE / ("mysql-"+args.kind)).read_text().strip(),
                         database="sn_"+args.kind+"_compare", cursorclass=pymysql.cursors.DictCursor) as con, con.cursor() as c:
        if args.action == "prepare":
            if path.exists():
                raise SystemExit("Fixture already exists; inspect and clean before another run")
            before = accounts(c, java)
            c.execute("SELECT * FROM sn_audit")
            audit = c.fetchall()
            entries = {suffix: {"username": f"cmp_auth_{args.tag}{suffix}", "initial": "Auth9!"+secrets.token_hex(18),
                                "changed": "Next8!"+secrets.token_hex(18)} for suffix in ("A", "D")}
            if any(r["username" if java else "emp_no"] in [e["username"] for e in entries.values()] for r in before):
                raise SystemExit("Existing account collision")
            state = {"before": before, "audit": audit, "entries": entries, "created": []}
            path.write_text(json.dumps(state, default=str), "utf8")
            with httpx.Client(base_url="http://127.0.0.1:"+("18080" if java else "8000"), trust_env=False, timeout=30) as client:
                token = None

                def call(method, route, body=None):
                    headers = {"Authorization": "Bearer "+token} if token else {}
                    if java and method != "GET":
                        csrf = client.get("/api/v1/system/auth/csrf").json()["data"]
                        headers[csrf["headerName"]] = csrf["token"]
                    response = client.request(method, ("/api/v1" if java else "/api")+route, json=body, headers=headers)
                    if not response.is_success:
                        raise RuntimeError(f"Fixture API HTTP {response.status_code}: {method} {route}")
                    value = response.json()
                    return value["data"] if java else value

                result = call("POST", "/system/auth/login" if java else "/auth/login",
                              {"username" if java else "emp_no": "admin", "password": (PRIVATE / (args.kind+"-admin-password")).read_text().strip()})
                token = result["accessToken" if java else "token"]
                factory = next(f["id"] for f in call("GET", "/sn/factories")["items"] if f["code"] == "CMP-A") if java else None
                for suffix, entry in entries.items():
                    body = {"username": entry["username"], "displayName": "认证痕迹验证员", "deptId": "1", "roleIds": ["3"], "factoryIds": [factory], "password": entry["initial"]} if java else {
                        "emp_no": entry["username"], "name": "认证痕迹验证员", "role": "factory_operator", "factory_code": "CMP-A", "password": entry["initial"]}
                    user = call("POST", "/system/users" if java else "/users", body)
                    entry["id"] = str(user["id"])
                    state["created"].append(entry["id"])
                    path.write_text(json.dumps(state, default=str), "utf8")
                    if suffix == "D":
                        call("POST", f"/system/users/{user['id']}/status" if java else f"/users/{user['id']}/disable",
                             {"enabled": False, "version": user["version"]} if java else {})
            if args.language:
                # 仅给刚创建的夹具设置偏好，以真实登录进入首次改密的三语页面。
                for entry in entries.values():
                    c.execute("UPDATE sys_user SET language=%s WHERE id=%s AND username=%s AND must_change_password=1"
                              if java else "UPDATE sn_user SET lang=%s WHERE id=%s AND emp_no=%s AND must_change_pwd=1",
                              (args.language, entry["id"], entry["username"]))
                con.commit()
                state["language"] = args.language
                path.write_text(json.dumps(state, default=str), "utf8")
            print(args.kind+": two owned accounts created; existing accounts unchanged")
            return
        state = json.loads(path.read_text("utf8"))
        current = accounts(c, java)
        owned = set(state["created"])
        assert digest([r for r in current if str(r["id"]) not in owned]) == digest(state["before"]), "Existing account changed; cleanup refused"
        for row in current:
            if str(row["id"]) in owned:
                assert row["username" if java else "emp_no"] in [e["username"] for e in state["entries"].values()], "Ownership changed"
        c.execute("SELECT * FROM sn_audit")
        now = {str(r["id"]): r for r in c.fetchall()}
        for row in state["audit"]:
            assert digest([row]) == digest([{k: now[str(row["id"])][k] for k in row}]), "Old audit changed"
        for uid in owned:
            if java:
                c.execute("SELECT COUNT(*) n FROM sys_task WHERE user_id=%s", (uid,))
                assert c.fetchone()["n"] == 0, "Owned account has business tasks; cleanup refused"
                c.execute("DELETE FROM sn_user_factory WHERE user_id=%s", (uid,))
                c.execute("DELETE FROM sys_user_role WHERE user_id=%s", (uid,))
            c.execute("DELETE FROM sys_user WHERE id=%s" if java else "DELETE FROM sn_user WHERE id=%s", (uid,))
        assert digest(accounts(c, java)) == digest(state["before"])
        con.commit()
        proof = {"kind": args.kind, "accountsRemoved": len(owned), "oldAccountsUnchanged": len(state["before"]),
                 "oldAuditPreserved": len(state["audit"]), "realAuditRetained": True}
        (RUN / f"stage35-clean-{args.kind}-{args.tag}.json").write_text(json.dumps(proof, indent=2), "utf8")
        path.rename(path.with_suffix(".completed.json"))
        print(args.kind+": only owned accounts removed; all real audits retained")


if __name__ == "__main__":
    main()
