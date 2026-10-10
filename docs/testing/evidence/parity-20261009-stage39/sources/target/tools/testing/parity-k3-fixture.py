"""Synthetic read-only K3 protocol fixture, bound only to the comparison loopback port."""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--port", type=int, default=19090)
parser.add_argument(
    "--extra-orders",
    type=Path,
    default=Path("tools/.runtime/comparison-20261008/extra-orders.json"),
)
config = parser.parse_args()
control_file = config.extra_orders.with_name("k3-control.json")
request_lock = threading.Lock()
query_requests = 0
inflight = 0


def controls():
    """仅测试进程通过同目录文件控制故障；HTTP 不提供写入入口。"""
    return (
        json.loads(control_file.read_text(encoding="utf-8"))
        if control_file.exists()
        else {}
    )


orgs = [
    {
        "FOrgID": "101",
        "FNumber": "CMP-A",
        "FName": "比较测试甲厂",
        "FForbidStatus": "A",
    },
    {
        "FOrgID": "102",
        "FNumber": "CMP-B",
        "FName": "比较测试乙厂",
        "FForbidStatus": "A",
    },
]
rows = []
for bill, pi, org, material, qty, bill_id, entry in [
    ("CMP-MO-001", "CMP-PI-001", 0, "CMP-MAT-A", "8", "9007199254740993", "11"),
    ("CMP-MO-001", "CMP-PI-001", 0, "CMP-MAT-B", "2", "9007199254740993", "12"),
    ("CMP-MO-002", "CMP-PI-002", 1, "CMP-MAT-A", "5", "9007199254740994", "21"),
    (
        "CMP-MO-FRACTION",
        "CMP-PI-FRACTION",
        0,
        "CMP-MAT-F",
        "2.5",
        "9007199254740995",
        "31",
    ),
]:
    o = orgs[org]
    rows.append(
        {
            "F_ora_kh.FNumber": "CMP-CUSTOMER",
            "FBillNo": bill,
            "F_HX_PO": "CMP-PO",
            "F_HX_PI": pi,
            "FMaterialId.FNumber": material,
            "FMaterialId.FName": material,
            "FQty": qty,
            "FStatus": "1",
            "FSaleOrderNo": "CMP-SO",
            "FPrdOrgId.FName": o["FName"],
            "FID": bill_id,
            "FTreeEntity_FEntryId": entry,
            "FPrdOrgId": o["FOrgID"],
            "FPrdOrgId.FNumber": o["FNumber"],
        }
    )


def test_rows():
    """可选大单数据由串行界面测试写入；不接收网络端传入的文件名。"""
    if controls().get("orders") == "empty":
        return []
    extras = (
        json.loads(config.extra_orders.read_text(encoding="utf-8"))
        if config.extra_orders.exists()
        else []
    )
    return rows + extras


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        current = test_rows()
        self.respond(
            {
                "fixture": "synthetic comparison only",
                "extrasSupported": True,
                "controlsSupported": True,
                "queryRequests": query_requests,
                "inflight": inflight,
                "orders": len({r["FBillNo"] for r in current}),
                "entries": len(current),
            }
        )

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        params = data.get("parameters", [])
        if "LoginByAppSecret" in self.path:
            if controls().get("failure") == "login":
                self.respond({"LoginResultType": 0, "Message": "比较登录失败"})
                return
            self.respond(
                {"LoginResultType": 1, "KDSVCSessionId": "comparison-only"}, cookie=True
            )
        elif "ExecuteBillQuery" in self.path:
            query = params[0] if params else data
            if isinstance(query, str):
                query = json.loads(query)
            if query.get("FormId") != "ORG_Organizations" and self.order_fault():
                return
            source = orgs if query.get("FormId") == "ORG_Organizations" else test_rows()
            start, limit = int(query.get("StartRow", 0)), int(query.get("Limit", 2000))
            fields = query["FieldKeys"].split(",")
            self.respond(
                [
                    [row[field] for field in fields]
                    for row in source[start : start + limit]
                ]
            )
        elif ".View." in self.path:
            if self.order_fault():
                return
            query = params[1]
            if isinstance(query, str):
                query = json.loads(query)
            selected = [r for r in test_rows() if r["FBillNo"] == query.get("Number")]
            if not selected:
                self.respond(
                    {"Result": {"ResponseStatus": {"IsSuccess": False, "MsgCode": 10}}}
                )
                return
            first = selected[0]
            doc = {
                "Id": first["FID"],
                "BillNo": first["FBillNo"],
                "F_ora_Base": {"Number": first["F_ora_kh.FNumber"]},
                "F_HX_PO": first["F_HX_PO"],
                "F_HX_PI": first["F_HX_PI"],
                "PrdOrgId": {
                    "Id": first["FPrdOrgId"],
                    "Number": first["FPrdOrgId.FNumber"],
                    "Name": first["FPrdOrgId.FName"],
                },
                "TreeEntity": [
                    {
                        "Id": r["FTreeEntity_FEntryId"],
                        "MaterialId": {
                            "Number": r["FMaterialId.FNumber"],
                            "Name": r["FMaterialId.FName"],
                        },
                        "Qty": r["FQty"],
                        "Status": r["FStatus"],
                        "SaleOrderNo": r["FSaleOrderNo"],
                    }
                    for r in selected
                ],
            }
            self.respond(
                {"Result": {"ResponseStatus": {"IsSuccess": True}, "Result": doc}}
            )
        else:
            self.send_error(404)

    def order_fault(self):
        """短暂挂起可观察真实 worker 执行中状态，60 秒上限避免遗留悬挂。"""
        global query_requests, inflight
        with request_lock:
            query_requests += 1
            inflight += 1
        try:
            deadline = time.monotonic() + 60
            while controls().get("hold") and time.monotonic() < deadline:
                time.sleep(0.1)
            failure = controls().get("failure")
            if failure == "http":
                self.respond({"fixture": "synthetic HTTP failure"}, status=503)
                return True
            if failure == "query":
                self.respond(
                    {
                        "Result": {
                            "ResponseStatus": {
                                "IsSuccess": False,
                                "Errors": [{"Message": "比较查询失败"}],
                            }
                        }
                    }
                )
                return True
            return False
        finally:
            with request_lock:
                inflight -= 1

    def respond(self, value, cookie=False, status=200):
        payload = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        if cookie:
            self.send_header(
                "Set-Cookie", "kdservice-sessionid=comparison-only; Path=/"
            )
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_):
        pass


print(f"Synthetic K3 fixture ready on 127.0.0.1:{config.port}", flush=True)
ThreadingHTTPServer(("127.0.0.1", config.port), Handler).serve_forever()
