"""集成测试基础设施：真实 MySQL（默认 127.0.0.1:3306/sndb_test_py，可用 SN_TEST_DB_* 覆盖）+ 假金蝶 HTTP 服务。

连不上 MySQL 时集成测试整体跳过。测试库在会话开始时整体重建；各用例用唯一的 PI / 单号互不干扰。
与 Java 的 ITBase 一一对应。
"""

from __future__ import annotations

import gzip
import hashlib
import hmac
import itertools
import json
import os
import re
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlencode

import pymysql
import pytest
from pymysql.cursors import DictCursor

HOST = os.environ.get("SN_TEST_DB_HOST", "127.0.0.1:3306")
DB = os.environ.get("SN_TEST_DB_NAME", "sndb_test_py")
USER = os.environ.get("SN_TEST_DB_USER", "appuser")
PASSWORD = os.environ.get("SN_TEST_DB_PASSWORD", "Kp7#mQ2vL9nR4wX!")
ADMIN_PASSWORD = "Admin@456"


def _host_port() -> tuple[str, int]:
    h, _, p = HOST.partition(":")
    return h, int(p or 3306)


def mysql_available() -> bool:
    h, p = _host_port()
    try:
        pymysql.connect(host=h, port=p, user=USER, password=PASSWORD, connect_timeout=3).close()
        return True
    except pymysql.err.Error:
        return False


def raw_conn(database: str | None = DB) -> pymysql.connections.Connection:
    h, p = _host_port()
    return pymysql.connect(
        host=h,
        port=p,
        user=USER,
        password=PASSWORD,
        database=database,
        autocommit=True,
        cursorclass=DictCursor,
        charset="utf8mb4",
    )


# ---------------------------------------------------------------------- 假金蝶


@dataclass
class Call:
    path: str
    body: dict
    cookie: str | None


class FakeK3:
    """按请求路径 / 请求体返回预置 JSON。"""

    def __init__(self) -> None:
        self.calls: list[Call] = []
        self.handler: Callable[[Call], Any] = lambda call: {}
        fake = self

        class H(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}")
                call = Call(self.path, body, self.headers.get("Cookie"))
                fake.calls.append(call)
                out = json.dumps(fake.handler(call), ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *args):  # noqa: D401
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/k3cloud/"

    def handle(self, h: Callable[[Call], Any]) -> None:
        self.calls.clear()
        self.handler = h

    def logged_in(self, then: Callable[[Call], Any]) -> None:
        """登录成功，其余请求交给 then。"""
        self.handle(
            lambda call: (
                {"LoginResultType": 1, "KDSVCSessionId": "sid"} if "LoginByAppSecret" in call.path else then(call)
            )
        )

    @staticmethod
    def param(call: Call, index: int) -> dict:
        return json.loads(call.body["parameters"][index])


K3 = FakeK3()
#: 假金蝶里的单据：单据编号 → View 返回的单据（含 DocumentStatus）
BILLS: dict[str, dict] = {}
#: 置为 True 时 ExecuteBillQuery 返回失败（模拟金蝶故障）
K3_FAIL = {"query": False, "view": False}


def _zh(v: Any) -> str:
    if isinstance(v, list):
        for e in v:
            if e.get("Key") == 2052:
                return e["Value"]
        return ""
    return "" if v is None else str(v)


def _detail_rows(doc: dict) -> list[list]:
    """与 k3.DETAIL_KEYS 的列顺序一致。"""
    base = doc.get("F_ora_Base") or {}
    org = doc["PrdOrgId"]
    return [
        [
            doc["BillNo"],
            doc["DocumentStatus"],
            base.get("Number", ""),
            doc["F_HX_PI"],
            doc["F_HX_PO"],
            org["Number"],
            org["Name"],
            line.get("FTERM", " "),
            line["MaterialId"]["Number"],
            line["MaterialId"]["Name"],
            line.get("Flength", " "),
            line["Qty"],
            line["Seq"],
            doc["ModifyDate"],
        ]
        for line in doc["TreeEntity"]
    ]


_IN = re.compile(r"FBillNo IN \((.*)\)")


def install_k3() -> None:
    def then(call: Call) -> Any:
        fail = {"Result": {"ResponseStatus": {"IsSuccess": False, "Errors": [{"Message": "金蝶故障"}]}}}
        if "View" in call.path:
            if K3_FAIL["view"]:
                return fail
            data = FakeK3.param(call, 1)
            doc = BILLS.get(str(data.get("Number")))
            if doc is None:
                return {
                    "Result": {
                        "ResponseStatus": {"IsSuccess": False, "MsgCode": 10, "Errors": [{"Message": "not found"}]}
                    }
                }
            return {"Result": {"ResponseStatus": {"IsSuccess": True}, "Result": doc}}
        if "ExecuteBillQuery" in call.path:
            data = FakeK3.param(call, 0)
            if data.get("FormId") == "ORG_Organizations":
                return [list(o) for o in ORGS]
            if K3_FAIL["query"]:
                return [[fail]]
            if int(data["StartRow"]) > 0:
                return []
            docs = [d for d in list(BILLS.values()) if d["DocumentStatus"] == "C"]
            if data["FieldKeys"] == "FBillNo,FModifyDate":
                return [[d["BillNo"], d["ModifyDate"]] for d in docs]
            m = _IN.search(data.get("FilterString", ""))
            if m:
                wanted = {x.strip().strip("'") for x in m.group(1).split(",")}
                docs = [d for d in docs if d["BillNo"] in wanted]
            rows: list = []
            for d in docs:
                rows += _detail_rows(d)
            return rows
        return {}

    K3.logged_in(then)


install_k3()

#: 假金蝶的组织机构：(FNumber, FName, FForbidStatus)
ORGS: list[tuple[str, str, str]] = []


# ---------------------------------------------------------------------- 假 MES


class FakeMes:
    """各组织一套 MES（路径 /mes/<组织>）：按 MES 对接说明第 3 节的 process(batch) 处理推送来的批次，
    也可在测试里对拉取到的批次调用 process 生成回执。behavior[org] 可覆盖应答（模拟故障）。"""

    def __init__(self) -> None:
        self.received: list[dict] = []
        #: org -> callable(batch, headers) -> (status, body | None) ；None = 正常处理
        self.behavior: dict[str, Callable[[dict, dict], tuple[int, Any]] | None] = {}
        #: org -> sn -> {"rev", "lastBatchNo", "transferredOut", "toOrgCode", "pi", "billNo", "billSeq", "suspended"}
        self.store: dict[str, dict[str, dict]] = {}
        #: org -> batchNo -> (itemsDigest, receipt)
        self.batches: dict[str, dict[str, tuple[str, dict]]] = {}
        self.tokens: dict[str, str] = {}
        self.lock = threading.Lock()
        fake = self

        class H(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                org = self.path.rstrip("/").split("/")[-1]
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n)
                if self.headers.get("Content-Encoding") == "gzip":
                    raw = gzip.decompress(raw)
                batch = json.loads(raw or b"{}")
                headers = {k.lower(): v for k, v in self.headers.items()}
                fake.received.append({"org": org, "batch": batch, "headers": headers, "raw": raw})
                if not fake.authorized(org, batch, headers, raw):
                    status, body = 401, {"schemaVersion": "1.0", "code": "UNAUTHORIZED", "message": "bad token"}
                else:
                    beh = fake.behavior.get(org)
                    status, body = (None, None) if beh is None else beh(batch, headers)
                    if status is None:
                        status, body = fake.handle(org, batch)
                if status == "drop":
                    self.connection.close()
                    return
                out = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *args):  # noqa: D401
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def authorized(self, org: str, batch: dict, headers: dict, raw: bytes) -> bool:
        """A：固定 Bearer 令牌；B：HMAC-SHA256 签名（timestamp \\n batchNo \\n hex(sha256(解压后的请求体))）。"""
        secret = self.tokens.get(org)
        if "x-sn-signature" in headers:
            msg = f"{headers.get('x-sn-timestamp')}\n{batch.get('batchNo')}\n{hashlib.sha256(raw).hexdigest()}"
            good = hmac.new((secret or "").encode(), msg.encode(), hashlib.sha256).hexdigest()
            return hmac.compare_digest(good, headers["x-sn-signature"])
        return headers.get("authorization") == f"Bearer {secret}"

    def url(self, org: str) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/mes/{org}"

    def handle(self, org: str, batch: dict) -> tuple[int, dict]:
        if batch.get("batchType") == "PING":
            if batch.get("orgCode") != org:
                return 200, {
                    "schemaVersion": "1.0",
                    "batchNo": batch.get("batchNo"),
                    "outcome": "REJECTED",
                    "rejectCode": "ORG_MISMATCH",
                    "rejectMessage": "orgCode",
                }
            return 200, {
                "schemaVersion": "1.0",
                "batchNo": batch["batchNo"],
                "outcome": "PROCESSED",
                "summary": {"total": 0, "accepted": 0, "duplicate": 0, "conflict": 0, "stale": 0},
                "results": [],
            }
        r = self.process(org, batch)
        if "__status" in r:
            status = r.pop("__status")
            return status, {"schemaVersion": "1.0", **r, "message": "batch content mismatch"}
        return 200, r

    def process(self, org: str, batch: dict) -> dict:
        """MES 处理逻辑（MES 对接说明第 3 节）。"""
        with self.lock:
            no = batch["batchNo"]
            items = batch.get("items") or []
            # 第 1 步：校验（不通过整批拒收，且不登记批次号）
            problem = None
            if batch.get("orgCode") != org:
                problem = ("ORG_MISMATCH", "orgCode 不是本组织")
            elif batch.get("count") != len(items):
                problem = ("COUNT_MISMATCH", "count")
            elif len({x["sn"] for x in items}) != len(items):
                problem = ("DUPLICATE_SN", "dup")
            elif batch["batchType"] == "DELIVERY" and any(
                x["pi"] != batch["pi"] or x["pushType"] == "TRANSFER_OUT" for x in items
            ):
                problem = ("PI_MISMATCH", "pi")
            elif batch["batchType"] == "TRANSFER_OUT" and any(x["pushType"] != "TRANSFER_OUT" for x in items):
                problem = ("TYPE_MISMATCH", "type")
            if problem:
                return {
                    "schemaVersion": "1.0",
                    "batchNo": no,
                    "outcome": "REJECTED",
                    "rejectCode": problem[0],
                    "rejectMessage": problem[1],
                }
            # 第 2 步：查重
            seen = self.batches.setdefault(org, {})
            if no in seen:
                digest, receipt = seen[no]
                if digest != batch["itemsDigest"]:
                    return {"__status": 409, "code": "BATCH_CONTENT_MISMATCH"}
                return {**receipt, "replayed": True}
            # 第 3 步：逐枚比对 rev
            store = self.store.setdefault(org, {})
            results = []
            summary = {"total": len(items), "accepted": 0, "duplicate": 0, "conflict": 0, "stale": 0}
            for x in items:
                cur = store.get(x["sn"])
                out = {"sn": x["sn"], "rev": x["rev"]}
                if cur is None or x["rev"] > cur["rev"]:
                    rec = {"rev": x["rev"], "lastBatchNo": no, "suspended": False}
                    if x["pushType"] == "TRANSFER_OUT":
                        rec.update(
                            transferredOut=True,
                            toOrgCode=x["toOrgCode"],
                            pi=x["from"]["pi"],
                            billNo=x["from"]["billNo"],
                            billSeq=x["from"]["billSeq"],
                        )
                    else:
                        rec.update(
                            transferredOut=False,
                            toOrgCode=None,
                            pi=x["pi"],
                            billNo=x["billNo"],
                            billSeq=x["billSeq"],
                            materialCode=x["materialCode"],
                        )
                    store[x["sn"]] = rec
                    out["result"] = "ACCEPTED"
                elif x["rev"] == cur["rev"] and cur["lastBatchNo"] == no:
                    out["result"] = "DUPLICATE"
                elif x["rev"] == cur["rev"]:
                    cur["suspended"] = True
                    out.update(result="CONFLICT", existingRev=cur["rev"], existingBatchNo=cur["lastBatchNo"])
                else:
                    out.update(result="STALE", existingRev=cur["rev"], existingBatchNo=cur["lastBatchNo"])
                summary[out["result"].lower()] += 1
                results.append(out)
            receipt = {
                "schemaVersion": "1.0",
                "batchNo": no,
                "outcome": "PROCESSED",
                "receivedAt": "2026-10-19T02:37:03Z",
                "summary": summary,
                "results": results,
            }
            seen[no] = (batch["itemsDigest"], receipt)
            return receipt


MES = FakeMes()


# ---------------------------------------------------------------------- HTTP 客户端


@dataclass
class Res:
    status: int
    body: Any
    raw: bytes

    @property
    def code(self) -> str | None:
        return self.body.get("code") if isinstance(self.body, dict) else None

    def __getitem__(self, key: str) -> Any:
        return self.body[key]

    def text(self, key: str) -> str | None:
        v = self.body.get(key)
        return None if v is None else str(v)

    def __str__(self) -> str:
        return f"{self.status} {self.body if self.body is not None else self.raw[:500]!r}"


class Api:
    def __init__(self, client) -> None:
        self.client = client

    def call(
        self, method: str, path: str, body: Any = None, token: str | None = None, extra: dict | None = None
    ) -> Res:
        headers = {"Content-Type": "application/json", **(extra or {})}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        content = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        r = self.client.request(method, path, content=content, headers=headers)
        data = r.json() if "json" in r.headers.get("content-type", "") and r.content else None
        return Res(r.status_code, data, r.content)

    def get(self, path: str, token: str | None) -> Res:
        return self.call("GET", path, None, token)

    def post(self, path: str, body: Any, token: str | None) -> Res:
        return self.call("POST", path, body, token)

    def page_login(self, emp_no: str, password: str) -> Res:
        """页面登录（前端带 X-SN-Client: web）；不带该头的是 MES 等对接方登录。"""
        return self.call("POST", "/api/auth/login", m(emp_no=emp_no, password=password), None, {"X-SN-Client": "web"})

    def put(self, path: str, body: Any, token: str | None) -> Res:
        return self.call("PUT", path, body, token)

    def delete(self, path: str, token: str | None) -> Res:
        return self.call("DELETE", path, None, token)


def q(**kv: Any) -> str:
    items = [(k, "true" if v is True else "false" if v is False else v) for k, v in kv.items() if v is not None]
    return "?" + urlencode(items) if items else ""


def m(**kv: Any) -> dict:
    return dict(kv)


_seq = itertools.count(1)


def uid(prefix: str) -> str:
    return f"{prefix}{int(time.time() * 1000) % 100000}-{next(_seq)}"


def emp(prefix: str = "U") -> str:
    """合法工号：4–32 位字母和数字。"""
    return f"{prefix}{int(time.time() * 1000) % 100000}{next(_seq)}"


def ok(r: Res) -> Res:
    assert r.status == 200, str(r)
    return r


# ---------------------------------------------------------------------- 会话级应用


@pytest.fixture(scope="session")
def app_client():
    if not mysql_available():
        pytest.skip(f"MySQL {HOST} 不可用，跳过集成测试")
    with raw_conn(None) as c, c.cursor() as cur:
        cur.execute(f"DROP DATABASE IF EXISTS {DB}")
        cur.execute(f"CREATE DATABASE {DB} CHARACTER SET utf8mb4")
    env = {
        # 逐项写死并清空 DB_URL：本机 .env 里的任何库配置都不能把测试带到别的库上
        "DB_URL": "",
        "DB_HOST": _host_port()[0],
        "DB_PORT": str(_host_port()[1]),
        "DB_NAME": DB,
        "DB_USER": USER,
        "DB_PASSWORD": PASSWORD,
        "SN_INIT_ADMIN_EMP_NO": "admin",
        "SN_INIT_ADMIN_PASSWORD": ADMIN_PASSWORD,
        "SN_RESET_ADMIN": "false",
        "SN_SECRET": "test-secret-test-secret-test-secret",
        "SN_DEMO_SEED": "false",
        "SN_GEN_SYNC_THRESHOLD": "3000",
        "SN_GEN_MAX_PER_RUN": "20000",
        # 后台推送调度、比对同步定时任务关闭：测试里手动驱动
        "SN_PUSH_ENABLED": "false",
        "SN_SYNC_ENABLED": "false",
        "SN_PUSH_RETRY_INTERVALS_MINUTES": "0,0,0",
        "SN_MES_PULL_RATE_PER_MINUTE": "100000",
        "K3_SERVER_URL": K3.url,
        "K3_ACCT_ID": "acct",
        "K3_APP_ID": "app",
        "K3_APP_SECRET": "secret",
        "K3_USERNAME": "tester",
        "K3_PAGE_RETRY_SECONDS": "0",
    }
    saved = {k: os.environ.get(k) for k in env}
    os.environ.update(env)
    from fastapi.testclient import TestClient

    from app.main import create_app

    try:
        with TestClient(create_app(), raise_server_exceptions=False) as client:
            yield client
    finally:
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


@pytest.fixture(scope="session")
def admin(app_client) -> str:
    api = Api(app_client)
    r = api.page_login("admin", ADMIN_PASSWORD)
    assert r.status == 200, str(r)
    assert r["user"]["must_change_pwd"] is False, "built-in admin from .env works without a password change"
    return r.text("token")


@pytest.fixture
def api(app_client) -> Api:
    return Api(app_client)


@pytest.fixture
def sql():
    """直接查库（断言用）。"""
    conn = raw_conn()

    class Sql:
        def count(self, s: str, *args: Any) -> int:
            with conn.cursor() as c:
                c.execute(s, args or None)
                row = c.fetchone()
                return 0 if row is None else int(next(iter(row.values())) or 0)

        def one(self, s: str, *args: Any) -> dict | None:
            with conn.cursor() as c:
                c.execute(s, args or None)
                return c.fetchone()

        def all(self, s: str, *args: Any) -> list[dict]:
            with conn.cursor() as c:
                c.execute(s, args or None)
                return list(c.fetchall())

        def scalar(self, s: str, *args: Any) -> Any:
            row = self.one(s, *args)
            return None if row is None else next(iter(row.values()))

        def exec(self, s: str, *args: Any) -> int:
            with conn.cursor() as c:
                return c.execute(s, args or None)

    try:
        yield Sql()
    finally:
        conn.close()


# ---------------------------------------------------------------------- 数据构造

_MODIFY = itertools.count(1)


class World:
    """数据构造助手：生产组织、订单、规则、生成、分配、推送、MES 调用。"""

    def __init__(self, api: Api, admin: str) -> None:
        self.api = api
        self.admin = admin

    # ---------------- 账户
    def user(self, emp: str, role: str, factory: str | None, factory_codes: list[str] | None = None) -> str:
        """建页面账户并页面登录（新建账户直接可用），返回令牌。"""
        ok(
            self.api.post(
                "/api/users",
                m(
                    emp_no=emp,
                    name=emp,
                    role=role,
                    factory_code=factory,
                    factory_codes=factory_codes,
                    password="Pass1234",
                ),
                self.admin,
            )
        )
        return ok(self.api.page_login(emp, "Pass1234")).text("token")

    # ---------------- 生产组织与 MES
    def org(self, code: str, name: str | None = None, enabled: bool = True) -> str:
        global ORGS
        ORGS[:] = [o for o in ORGS if o[0] != code] + [(code, name or f"组织{code}", "A" if enabled else "B")]
        ok(self.api.post("/api/factories/sync", {}, self.admin))
        return code

    def enable_mes(self, code: str, batch_size: int = 5000, auth: str = "TOKEN") -> None:
        token = f"tok-{code}"
        MES.tokens[code] = token
        cfg = m(
            mes_url=MES.url(code), mes_auth_type=auth, mes_credential=token, batch_size=batch_size, push_timeout_sec=10
        )
        t = ok(self.api.post(f"/api/factories/{code}/mes-test", cfg, self.admin))
        assert t["ok"], str(t)
        ok(self.api.put(f"/api/factories/{code}/mes-config", cfg, self.admin))
        ok(self.api.post(f"/api/factories/{code}/mes-toggle", m(enabled=True), self.admin))

    def mes_account(self, code: str) -> tuple[str, str]:
        """建 MES 接口账户并换令牌，返回（令牌，工号）。"""
        e = emp("M")
        r = ok(self.api.post("/api/users", m(emp_no=e, name="MES", role="mes", factory_code=code), self.admin))
        t = ok(self.api.post("/api/mes/v1/auth/token", m(empNo=e, secret=r["secret"]), None))
        return t.text("accessToken"), e

    # ---------------- 订单
    def order(self, bill_no: str, org: str, customer: str, pi: str, *lines: tuple, status: str = "C") -> Res:
        """在假金蝶登记一张生产订单并按单据编号同步进快照。lines = (物料, 数量[, 长度[, 附件项次]])。"""
        BILLS[bill_no] = self.doc(bill_no, org, customer, pi, *lines, status=status)
        return ok(self.api.post("/api/orders/sync", m(bill_no=bill_no), self.admin))

    @staticmethod
    def doc(bill_no: str, org: str, customer: str, pi: str, *lines: tuple, status: str = "C") -> dict:
        entries = []
        for i, line in enumerate(lines, start=1):
            material, qty = line[0], line[1]
            length = line[2] if len(line) > 2 else "100"
            attach = line[3] if len(line) > 3 else "1"
            entries.append(
                {
                    "Seq": i,
                    "MaterialId": {"Number": material, "Name": [{"Key": 2052, "Value": "物料" + material}]},
                    "Qty": float(qty),
                    "Flength": length,
                    "FTERM": attach,
                }
            )
        return {
            "BillNo": bill_no,
            "DocumentStatus": status,
            "F_ora_Base": {"Number": customer},
            "F_HX_PO": "PO-" + bill_no,
            "F_HX_PI": pi,
            "PrdOrgId": {"Number": org, "Name": [{"Key": 2052, "Value": f"组织{org}"}]},
            "ModifyDate": f"2026-10-01T00:00:{next(_MODIFY) % 60:02d}",
            "TreeEntity": entries,
        }

    def touch(self, bill_no: str, **changes: Any) -> None:
        """改假金蝶里的单据（表头字段或 DocumentStatus），并刷新修改时间。"""
        d = BILLS[bill_no]
        for k, v in changes.items():
            if k == "org":
                d["PrdOrgId"] = {"Number": v, "Name": [{"Key": 2052, "Value": f"组织{v}"}]}
            elif k == "customer":
                d["F_ora_Base"] = {"Number": v}
            elif k == "pi":
                d["F_HX_PI"] = v
            elif k == "status":
                d["DocumentStatus"] = v
            else:
                d[k] = v
        d["ModifyDate"] = f"2026-10-02T{next(_MODIFY) % 24:02d}:{next(_MODIFY) % 60:02d}:00"

    def compare(self) -> dict:
        return ok(self.api.post("/api/orders/compare", {}, self.admin)).body

    # ---------------- 规则
    def rule(self, prefix: str, base: int = 10, seq_len: int = 6, with_length: bool = True, suffix: str = "") -> int:
        r = ok(
            self.api.post(
                "/api/rules",
                m(
                    rule_code=uid("R"),
                    rule_name="rule " + prefix,
                    prefix=prefix,
                    suffix=suffix,
                    base=base,
                    seq_len=seq_len,
                    with_length=with_length,
                ),
                self.admin,
            )
        )
        return r["rule"]["id"]

    def bind_pi(self, pi: str, rule_id: int, new_prefix: str | None = None) -> Res:
        return self.api.put("/api/rules/pi-binding", m(pi_no=pi, rule_id=rule_id, new_prefix=new_prefix), self.admin)

    def select(
        self,
        pi: str,
        lines: list[tuple[str, int]],
        rule_id: int | None = None,
        custom: dict | None = None,
        new_prefix: str | None = None,
    ) -> Res:
        return self.api.post(
            "/api/rules/line-select",
            m(
                pi_no=pi,
                lines=[m(bill_no=b, seq=s) for b, s in lines],
                rule_id=rule_id,
                custom=custom,
                new_prefix=new_prefix,
            ),
            self.admin,
        )

    # ---------------- 生成 / 分配 / 推送
    def preview(
        self,
        pi: str,
        bills: list[str] | None = None,
        lines: list[tuple[str, int]] | None = None,
    ) -> Res:
        """预演：范围内每个还有额度的物料行整行生成；不给范围 = 这张 PI 在快照中的全部单据。"""
        scope = "LINES" if lines else "BILLS"
        if not lines and not bills:
            bills = sorted({d["BillNo"] for d in BILLS.values() if d["F_HX_PI"] == pi and d["DocumentStatus"] == "C"})
        return self.api.post(
            "/api/generate/preview",
            m(pi_no=pi, scope=scope, bills=bills, lines=[m(bill_no=b, seq=s) for b, s in lines or []]),
            self.admin,
        )

    def generate(
        self,
        pi: str,
        bills: list[str] | None = None,
        lines: list[tuple[str, int]] | None = None,
    ) -> dict:
        p = ok(self.preview(pi, bills, lines))
        assert p["generatable"], str(p)
        j = ok(self.api.post("/api/generate/run", m(token=p["token"]), self.admin))
        assert j["status"] == "SUCCESS", str(j)
        return j.body

    def allocate(self, pi: str, bills: list[str] | None = None, lines: list[tuple[str, int]] | None = None) -> dict:
        body = m(pi_no=pi, bills=bills, lines=[m(bill_no=b, seq=s) for b, s in lines] if lines else None)
        return ok(self.api.post("/api/allocation/allocate", body, self.admin)).body

    def revoke(self, batch: str, reason: str | None = "x", line: tuple[str, int] | None = None) -> Res:
        body = m(reason=reason) if line is None else m(reason=reason, bill_no=line[0], seq=line[1])
        return self.api.post(f"/api/generate/batches/{batch}/revoke", body, self.admin)

    @staticmethod
    def push() -> int:
        from app.services import delivery

        return delivery.run_pending()

    def acquire(self, token: str, factory: str | None, pi: str, req: str | None = None) -> Res:
        return self.api.post("/api/acquire/take", m(factory_code=factory, pi_no=pi, request_no=req or uid("Q")), token)

    # ---------------- MES 调用
    def mes_next(self, token: str, **kw: Any) -> Res:
        return self.api.post("/api/mes/v1/batches/next", m(schemaVersion="1.0", **kw), token)

    def mes_receipt(self, token: str, batch_no: str, receipt: dict) -> Res:
        return self.api.post(f"/api/mes/v1/batches/{batch_no}/receipt", receipt, token)


@pytest.fixture
def w(api, admin) -> World:
    return World(api, admin)


#: 号、规则、批次相关的表：每个用例开始前清空（SN 全局唯一、前缀不能互为开头，跨用例会冲突）；
#: 生产组织、账户、订单快照、留痕不清。
_SN_TABLES = (
    "sn_transfer_item",
    "sn_transfer",
    "sn_bill_transfer",
    "sn_acquire_batch",
    "sn_delivery_alert",
    "sn_delivery_receipt",
    "sn_delivery_item",
    "sn_delivery_batch",
    "sn_push_task",
    "sn_item",
    "sn_gen_job",
    "sn_gen_preview",
    "sn_line_count",
    "sn_line_rule",
    "sn_line_attach",
    "sn_pi_counter",
    "sn_pi_rule",
    "sn_customer_rule",
    "sn_rule_version",
    "sn_rule",
    "sn_bill_ledger",
    "sn_bill_ledger_line",
    "sn_order_alert",
    "sn_prd_mo",
)


@pytest.fixture(autouse=True)
def _fresh_sn(request):
    if "app_client" in request.fixturenames:
        request.getfixturevalue("app_client")
        with raw_conn() as c, c.cursor() as cur:
            for t in _SN_TABLES:
                cur.execute(f"DELETE FROM {t}")
        BILLS.clear()
        MES.behavior.clear()
        MES.store.clear()
        MES.batches.clear()
        MES.received.clear()
        K3_FAIL.update(query=False, view=False)
    yield


_once: dict[str, Any] = {}


@pytest.fixture
def once():
    """每个测试模块只做一次的准备（数据跨用例保留）。"""
    return _once
