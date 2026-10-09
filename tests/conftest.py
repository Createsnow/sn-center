"""集成测试基础设施：真实 MySQL（默认 127.0.0.1:3306/sndb_test_py，可用 SN_TEST_DB_* 覆盖）+ 假金蝶 HTTP 服务。

连不上 MySQL 时集成测试整体跳过。测试库在会话开始时整体重建；各用例用唯一的 PI / 单号互不干扰。
与 Java 的 ITBase 一一对应。
"""

from __future__ import annotations

import itertools
import json
import os
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
#: 假金蝶里的单据：单据编号 → View 返回的单据
BILLS: dict[str, dict] = {}


def _zh(v: Any) -> str:
    if isinstance(v, list):
        for e in v:
            if e.get("Key") == 2052:
                return e["Value"]
        return ""
    return "" if v is None else str(v)


def _query_rows(doc: dict) -> list[list]:
    customer = doc["F_ora_Base"]["Number"]
    org = _zh(doc["PrdOrgId"]["Name"])
    return [
        [
            customer,
            doc["BillNo"],
            doc["F_HX_PO"],
            doc["F_HX_PI"],
            line["MaterialId"]["Number"],
            _zh(line["MaterialId"]["Name"]),
            line["Qty"],
            line["Status"],
            "",
            org,
        ]
        for line in doc["TreeEntity"]
    ]


def install_k3() -> None:
    """View 按单据编号返回 BILLS；ExecuteBillQuery（全量）返回 BILLS 全部明细。"""

    def then(call: Call) -> Any:
        if "View" in call.path:
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
                return []
            if int(data["StartRow"]) > 0:
                return []
            rows: list = []
            for doc in list(BILLS.values()):
                rows += _query_rows(doc)
            return rows
        return {}

    K3.logged_in(then)


install_k3()


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

    def call(self, method: str, path: str, body: Any = None, token: str | None = None) -> Res:
        headers = {"Content-Type": "application/json"}
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
        "SN_INIT_ADMIN_PASSWORD": "Admin@123",
        "SN_SECRET": "test-secret",
        "SN_DEMO_SEED": "false",
        "SN_GEN_SYNC_THRESHOLD": "3000",
        "K3_SERVER_URL": K3.url,
        "K3_ACCT_ID": "acct",
        "K3_APP_ID": "app",
        "K3_APP_SECRET": "secret",
        "K3_USERNAME": "tester",
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
    r = api.post("/api/auth/login", m(emp_no="admin", password="Admin@123"), None)
    assert r.status == 200, str(r)
    c = ok(api.post("/api/auth/password", m(old_password="Admin@123", new_password=ADMIN_PASSWORD), r.text("token")))
    return c.text("token")


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

        def exec(self, s: str, *args: Any) -> int:
            with conn.cursor() as c:
                return c.execute(s, args or None)

    try:
        yield Sql()
    finally:
        conn.close()


# ---------------------------------------------------------------------- 数据构造


class World:
    """ITBase 的数据构造助手。"""

    def __init__(self, api: Api, admin: str) -> None:
        self.api = api
        self.admin = admin

    def factory(self, code: str, name: str) -> None:
        r = self.api.post("/api/factories", m(factory_code=code, factory_name=name), self.admin)
        assert r.status == 200 or r.code == "FACTORY_EXISTS", str(r)

    def user(self, emp: str, role: str, factory: str | None) -> str:
        """建账户并完成首次改密，返回令牌。"""
        ok(
            self.api.post(
                "/api/users", m(emp_no=emp, name=emp, role=role, factory_code=factory, password="Init@1234"), self.admin
            )
        )
        t = ok(self.api.post("/api/auth/login", m(emp_no=emp, password="Init@1234"), None)).text("token")
        return ok(self.api.post("/api/auth/password", m(old_password="Init@1234", new_password="Pass@1234"), t)).text(
            "token"
        )

    def order(self, bill_no: str, org: str, customer: str, pi: str, *lines: tuple) -> Res:
        """在假金蝶登记一张生产订单并按单据编号同步进快照。lines = (物料, 数量[, 状态])。"""
        entries = []
        for line in lines:
            material, qty = line[0], line[1]
            status = line[2] if len(line) > 2 else "2"
            entries.append(
                {
                    "MaterialId": {"Number": material, "Name": [{"Key": 2052, "Value": "物料" + material}]},
                    "Qty": float(qty),
                    "Status": status,
                    "SaleOrderNo": "SO",
                }
            )
        BILLS[bill_no] = {
            "BillNo": bill_no,
            "F_ora_Base": {"Number": customer},
            "F_HX_PO": "PO-" + bill_no,
            "F_HX_PI": pi,
            "PrdOrgId": {"Name": [{"Key": 2052, "Value": org}]},
            "TreeEntity": entries,
        }
        return ok(self.api.post("/api/orders/sync", m(bill_no=bill_no), self.admin))

    def pi_rule(self, pi: str, prefix: str, base: int, length: int) -> None:
        ok(
            self.api.post(
                "/api/rules",
                m(
                    rule_code=uid("R"),
                    rule_name="rule " + pi,
                    bind_scope="PI",
                    bind_value=pi,
                    prefix=prefix,
                    suffix="",
                    base=base,
                    seq_len=length,
                    charset="",
                ),
                self.admin,
            )
        )

    def preview(self, bill: str, qty: int, rule_id: int | None = None) -> Res:
        return self.api.post("/api/generate/preview", m(bill_no=bill, qty=qty, rule_id=rule_id), self.admin)

    def generate(self, bill: str, qty: int, rule_id: int | None = None) -> dict:
        """预演 + 生成（同步完成），返回任务。"""
        p = ok(self.preview(bill, qty, rule_id))
        j = ok(self.api.post("/api/generate", m(preview_token=p.text("token"), bill_no=bill, qty=qty), self.admin))
        assert j.text("status") == "SUCCESS", str(j)
        return j.body

    def allocate(self, bill: str) -> dict:
        return ok(self.api.post("/api/generate/allocate", m(bill_no=bill), self.admin)).body

    def acquire(self, token: str, factory: str | None, pi: str, req: str) -> Res:
        return self.api.post("/api/acquire/take", m(factory_code=factory, pi_no=pi, request_no=req), token)


@pytest.fixture
def w(api, admin) -> World:
    return World(api, admin)


_once: dict[str, Any] = {}


@pytest.fixture
def once():
    """每个测试模块只做一次的准备（数据跨用例保留）。"""
    return _once
