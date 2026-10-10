"""在回环比较环境通过公开 API 建立角色测试账号；不重置任何已有账号。"""

import json
import secrets
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
ROLES = (
    ("cmp_operator", "比较操作员", "factory_operator", "3", True),
    ("cmp_query_all", "比较总部查询员", "query", "4", False),
    ("cmp_query_factory", "比较工厂查询员", "query", "5", True),
)


def main():
    path = PRIVATE / "role-accounts.json"
    accounts = json.loads(path.read_text("utf-8")) if path.exists() else {}
    for kind, port in (("java", 18080), ("python", 8000)):
        entries = accounts.setdefault(kind, {})
        with httpx.Client(
            base_url=f"http://127.0.0.1:{port}", trust_env=False, timeout=20
        ) as client:
            state = {"token": None}

            def call(
                method, route, body=None, *, kind=kind, client=client, state=state
            ):
                token = state["token"]
                headers = {"Authorization": f"Bearer {token}"} if token else {}
                if kind == "java" and method != "GET":
                    csrf = client.get("/api/v1/system/auth/csrf").json()["data"]
                    headers[csrf["headerName"]] = csrf["token"]
                prefix = "/api/v1" if kind == "java" else "/api"
                response = client.request(
                    method, prefix + route, json=body, headers=headers
                )
                if response.is_error:
                    raise RuntimeError(
                        f"{kind} {method} {route}: HTTP {response.status_code}"
                    )
                value = response.json()
                return value["data"] if kind == "java" else value

            password = (PRIVATE / f"{kind}-admin-password").read_text("utf-8").strip()
            logged = call(
                "POST",
                "/system/auth/login" if kind == "java" else "/auth/login",
                {
                    "username" if kind == "java" else "emp_no": "admin",
                    "password": password,
                },
            )
            state["token"] = logged.get("accessToken", logged.get("token"))
            factory = None
            if kind == "java":
                factory = next(
                    f["id"]
                    for f in call("GET", "/sn/factories")["items"]
                    if f["code"] == "CMP-A"
                )
            for username, name, role, role_id, bound in ROLES:
                if username not in entries:
                    entries[username] = {
                        "name": name,
                        "initial": "Cmp9!" + secrets.token_hex(20),
                        "current": "Cmp8!" + secrets.token_hex(20),
                        "changed": False,
                    }
                    path.write_text(
                        json.dumps(accounts, ensure_ascii=False, indent=2), "utf-8"
                    )
                entry = entries[username]
                route = "/system/users?keyword=" if kind == "java" else "/users?q="
                existing = call("GET", route + username)["items"]
                existing = [
                    u
                    for u in existing
                    if u.get("username", u.get("emp_no")) == username
                ]
                if not existing:
                    body = (
                        {
                            "username": username,
                            "displayName": name,
                            "deptId": "1",
                            "roleIds": [role_id],
                            "factoryIds": [factory] if bound else [],
                            "password": entry["initial"],
                        }
                        if kind == "java"
                        else {
                            "emp_no": username,
                            "name": name,
                            "role": role,
                            "factory_code": "CMP-A" if bound else None,
                            "password": entry["initial"],
                        }
                    )
                    call("POST", "/system/users" if kind == "java" else "/users", body)
                print(f"{kind}: {username} ready (no password reset)")
    path.write_text(json.dumps(accounts, ensure_ascii=False, indent=2), "utf-8")


if __name__ == "__main__":
    main()
