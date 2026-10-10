"""Supplement UI checks with direct optional-body/interval requests against the a48 services only."""

import json
import subprocess
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "deploy/.secrets/comparison-20261008"
RUN = ROOT / "tools/.runtime/comparison-20261008"
tag = sys.argv[1]
assert tag.isdigit() and 8 <= len(tag) <= 14
username = "audit" + tag
results = {}
for kind in ("python", "java"):
    java = kind == "java"
    subprocess.run([sys.executable, "-X", "utf8", str(Path(__file__).with_name("parity-order-audit-fixture.py")), kind, "prepare", tag], check=True, capture_output=True)
    prefix = "/api/v1" if java else "/api"
    business = "/sn" if java else ""
    with httpx.Client(base_url="http://127.0.0.1:" + ("18081" if java else "8001"), trust_env=False, timeout=30) as client:
        token = None

        def request(method, path, value=None, raw=None):
            headers = {"Authorization": "Bearer " + token} if token else {}
            if java and method != "GET":
                csrf = client.get(prefix + "/system/auth/csrf").json()["data"]
                headers[csrf["headerName"]] = csrf["token"]
            headers["Content-Type"] = "application/json"
            content = raw if raw is not None else json.dumps(value)
            response = client.request(method, prefix + path, content=content if method != "GET" else None, headers=headers)
            parsed = response.json()
            return response.status_code, parsed.get("data") if java and response.is_success else parsed

        status, logged = request("POST", "/system/auth/login" if java else "/auth/login", {"username" if java else "emp_no": username, "password": (PRIVATE / (kind + "-admin-password")).read_text().strip()})
        assert status == 200
        token = logged["accessToken" if java else "token"]
        results[kind] = {"requests": []}
        bill = "billNo" if java else "bill_no"
        interval = "intervalMinutes" if java else "interval_minutes"
        next_run = "nextRunAt" if java else "next_run_at"

        def call(label, path, value=None, raw=None, expected=200, method="POST"):
            status, result = request(method, business + path, value, raw)
            assert status == expected, (kind, label, status)
            results[kind]["requests"].append({"label": label, "status": status, "result": result})
            return result

        for label, body in [("absent-body", ""), ("null-body", "null"), ("empty-object", "{}"), ("null-bill", json.dumps({bill: None}))]:
            result = call(label, "/orders/sync", raw=body)
            assert result["mode"] == "all"
            assert int(result["billsCount" if java else "bills"]) == 3
        for i, value in enumerate(["", " \t\r\n", "\u0085", "\u00a0\u2003\u3000"]):
            result = call("blank-" + str(i), "/orders/sync", {bill: value}, expected=400)
            assert result["code"] == (1004000034 if java else "ORDER_BILL_REQUIRED")
        initial = call("set-hour", "/orders/sync-schedule", {"enabled": True, interval: 60}, method="PUT")
        for label, value in [("omitted-interval", {"enabled": True}), ("null-interval", {"enabled": True, interval: None})]:
            result = call(label, "/orders/sync-schedule", value, method="PUT")
            assert result[interval] == 60 and result[next_run] == initial[next_run]
        for value in [-1, 0, 9, 10081, 2147483647]:
            result = call("interval-" + str(value), "/orders/sync-schedule", {"enabled": False, interval: value}, expected=400, method="PUT")
            assert result["code"] == (1004000035 if java else "ORDER_SYNC_INTERVAL_INVALID")
            _, current = request("GET", business + "/orders/sync-schedule")
            assert current["enabled"] and current[interval] == 60 and current[next_run] == initial[next_run]
        result = call("disable-keep-hour", "/orders/sync-schedule", {"enabled": False}, method="PUT")
        assert result[interval] == 60 and result[next_run] is None
        for value in [10, 10080, 1440]:
            result = call("valid-interval-" + str(value), "/orders/sync-schedule", {"enabled": False, interval: value}, method="PUT")
            assert result[interval] == value and result[next_run] is None
        _, facts = request("GET", business + ("/audits/page?size=100" if java else "/audits?page_size=100") + "&operator=" + username)
        actions = {"sn:order:sync": "ORDER_SYNC", "sn:order:schedule": "ORDER_SYNC_SCHEDULE"}
        results[kind]["audits"] = [
            [actions.get(r["action"], r["action"]), {"SUCCESS": "OK", "FAILED": "FAIL"}.get(r["result"], r["result"]), r["detail"], r["errorMessage" if java else "error_msg"], r["billNo" if java else "bill_no"], None if r["quantity" if java else "qty"] is None else int(r["quantity" if java else "qty"])]
            for r in facts["items"] if r["action"] in ("sn:order:sync", "sn:order:schedule", "ORDER_SYNC", "ORDER_SYNC_SCHEDULE")
        ]
assert results["java"]["audits"] == results["python"]["audits"]
assert len(results["java"]["audits"]) == 20
(RUN / "stage49-api-comparison.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf8")
print("Direct request comparison: 20 operations and resulting order/schedule audit facts match; this supplements the actual UI flow.")
