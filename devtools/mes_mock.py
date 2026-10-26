"""模拟 MES：MES 方还没接入时扮演 MES 与本系统联调，也是《MES 对接说明》3.2 节 process(batch) 的参考实现。

推送和取批共用同一套本侧数据（按组织分开的 SN 表、批次表）和同一个 process，与对接说明对真实 MES 的要求一致。

    # 接收推送（接口 ①）：地址 http://<host>:<port>/mes/<组织>，填到「生产组织」页的 MES 接口地址
    uv run python -m devtools.mes_mock serve --port 9001 --secret 110=SN110:key-110
    # 限定组织 110 已有的 PI：交付批次头的 pi 不在其中时整批退回（不给时不校验 PI 归属）
    uv run python -m devtools.mes_mock serve --port 9001 --secret 110=SN110:key-110 --pi 110=000001,000002
    # 同时每 5 分钟取一次批（② → process → ③），与推送共用本侧数据
    uv run python -m devtools.mes_mock serve --port 9001 --secret 110=SN110:key-110 \\
        --pull-base http://127.0.0.1:8000 --pull-org 110 --pull-emp MES110 --pull-key <密钥>
    # 只取一轮批（取到 batch = null 或 remaining = 0 为止），用于手工验证 ②③
    uv run python -m devtools.mes_mock pull --base http://127.0.0.1:8000 --org 110 --emp MES110 --key <密钥>

控制接口（注入故障、查看收到的报文和本侧数据）：

    GET    /_mock/state                 各组织的 SN 表、批次表、当前故障、已有的 PI
    GET    /_mock/received?limit=20     最近收到的推送（不含号明细）
    POST   /_mock/fault                 设置故障，见 FAULT_MODES
    DELETE /_mock/fault?org=110         清除故障（不带 org 清除全部）
    POST   /_mock/seed                  {"org", "sn", "rev", "lastBatchNo"} 预置一枚已有的号（造本地更新的版本）
    POST   /_mock/reset                 清空本侧数据、批次表与收到的记录（保留认证配置）

只用于测试环境：数据只在内存里，重启即清空。
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import hmac
import json
import ssl
import sys
import threading
import time
from collections.abc import Callable
from datetime import UTC, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlsplit

import httpx

SCHEMA = "1.0"
DELIVERY_FIELDS = (
    "sn",
    "rev",
    "serial",
    "serialDec",
    "serialPi",
    "pi",
    "billNo",
    "billSeq",
    "customerCode",
)
TRANSFER_OUT_FIELDS = ("sn", "rev", "toOrgCode")
#: 交付类批次类型：MES 处理都一样（逐枚按 rev 新增或覆盖），类型只说明这批号从哪来
DELIVERY_TYPES = ("NEW", "SAME_ORG_TRANSFER", "TRANSFER_IN", "REMAP")

#: 故障模式（POST /_mock/fault 的 mode）
FAULT_MODES = {
    "http": "直接返回 status（默认 503）+ 错误结构 {code, message}，不处理；429 / 503 带 Retry-After",
    "drop": "不处理，直接断开连接（本系统记为断连，按间隔重试）",
    "drop_after": "先处理并落库，再断开连接（MES 已收下但应答丢失，重试时应拿到同一份回执）",
    "slow": "先处理并落库，延迟 delay 秒（默认 70）再应答（超过推送超时，本系统重试或取批时应拿到同一份回执）",
    "in_progress": "返回 409 BATCH_IN_PROGRESS（同一批正在处理）",
    "reject": "整批退回：HTTP 200 + code = NG（message 默认「模拟整批退回」），不登记批次号",
    "bad_receipt": "正常处理，但回执的 batchNo 写错（回执不符，本系统应记为 RECEIPT_MISMATCH）",
}

#: behavior(batch, headers) -> (status, body)；(None, None) = 正常处理；("drop", None) = 断开连接
Behavior = Callable[[dict, dict], tuple[Any, Any]]


#: MES 接口时间：UTC+8，格式 2026-10-26 11:47:05.303
CST = timezone(timedelta(hours=8))
TS = "%Y-%m-%d %H:%M:%S.%f"


def now_cst() -> str:
    return datetime.now(CST).strftime(TS)[:-3]


def _receipt(batch_no: str | None, code: str = "OK", message: str = "已处理") -> dict:
    """MES 应答（回执与出错同一结构）：code = OK 整批已处理 / NG 整批退回（message 为原因）/ 错误码；
    processedAt 为 MES 处理时间。"""
    return {"batchNo": batch_no, "code": code, "message": message, "processedAt": now_cst()}


def _err(code: str, message: str, batch_no: str | None = None) -> dict:
    return _receipt(batch_no, code, message)


def _reject(batch_no: str | None, message: str) -> dict:
    return _receipt(batch_no, "NG", message)


class MesSim:
    """各组织一套 MES 本侧数据与处理逻辑（对接说明 3.2 节）。"""

    def __init__(self, max_batch: int = 5000, max_skew: int = 300) -> None:
        #: 每批最多枚数（第 1 步校验）；HMAC 时间戳允许偏差秒数（0 = 不校验）
        self.max_batch = max_batch
        self.max_skew = max_skew
        #: org -> 凭证（TOKEN 为 Bearer 令牌，HMAC 为签名密钥）；default_secret 用于未单独配置的组织
        self.secrets: dict[str, str] = {}
        self.default_secret: str | None = None
        #: org -> 本组织已有的 PI；配置了的组织校验交付批次头的 pi 在其中，未配置的不校验归属
        self.pis: dict[str, set[str]] = {}
        #: org -> behavior（覆盖应答，模拟故障）
        self.behavior: dict[str, Behavior | None] = {}
        self.faults: dict[str, dict] = {}
        #: org -> sn -> {"rev", "lastBatchNo", "transferredOut", "toOrgCode", "pi", "billNo", ...}
        self.store: dict[str, dict[str, dict]] = {}
        #: org -> batchNo -> {"digest", "receipt", "source", "at"}
        self.batches: dict[str, dict[str, dict]] = {}
        self.received: list[dict] = []
        self.lock = threading.Lock()

    # ------------------------------------------------------------------ 认证

    def secret(self, org: str) -> str | None:
        return self.secrets.get(org, self.default_secret)

    def authorized(self, org: str, headers: dict, raw: bytes) -> str | None:
        """A：固定 Bearer 令牌；B：HMAC-SHA256（timestamp \\n hex(sha256(解压后的请求体))）。
        通过返回 None，否则返回原因。"""
        secret = self.secret(org)
        if secret is None:
            return f"组织 {org} 未配置凭证"
        if "x-sn-signature" in headers:
            ts = headers.get("x-sn-timestamp") or ""
            if self.max_skew:
                try:
                    t = datetime.strptime(ts, TS).replace(tzinfo=CST)
                except ValueError:
                    return "X-Sn-Timestamp 须为 UTC+8 的 yyyy-MM-dd HH:mm:ss.SSS"
                if abs((datetime.now(UTC) - t).total_seconds()) > self.max_skew:
                    return f"时间戳与本地相差超过 {self.max_skew} 秒"
            msg = f"{ts}\n{hashlib.sha256(raw).hexdigest()}"
            good = hmac.new(secret.encode(), msg.encode(), hashlib.sha256).hexdigest()
            return None if hmac.compare_digest(good, headers["x-sn-signature"]) else "签名不符"
        return None if headers.get("authorization") == f"Bearer {secret}" else "令牌不符"

    # ------------------------------------------------------------------ 处理

    def handle(self, org: str, batch: dict, source: str = "PUSH") -> tuple[int, dict]:
        """一次送达的应答：PING 只校验 orgCode；其余走 process。"""
        if batch.get("batchType") == "PING":
            if batch.get("orgCode") != org:
                return 200, _reject(batch.get("batchNo"), f"orgCode 不是本组织 {org}")
            return 200, _receipt(batch.get("batchNo"))
        r = self.process(org, batch, source)
        if "__status" in r:
            status = r.pop("__status")
            return status, r
        return 200, r

    def _problem(self, org: str, batch: dict) -> str | None:
        """第 2 步：整批校验，不通过整批退回（NG）。"""
        items = batch.get("items")
        kind = batch.get("batchType")
        if not isinstance(items, list) or not batch.get("batchNo"):
            return "batchNo、items 必填"
        if batch.get("orgCode") != org:
            return f"orgCode {batch.get('orgCode')} 不是本组织 {org}"
        if kind not in (*DELIVERY_TYPES, "TRANSFER_OUT"):
            return f"不认识的 batchType {kind}"
        delivery = kind in DELIVERY_TYPES
        if len(items) > self.max_batch:
            return f"超过单批上限 {self.max_batch}"
        if not isinstance(batch.get("itemsDigest"), str) or not batch["itemsDigest"]:
            return "itemsDigest 必填"
        pi = batch.get("pi")
        if not isinstance(pi, str) or not pi:
            return "pi 必填"
        if delivery and org in self.pis and pi not in self.pis[org]:
            return f"本组织 {org} 没有 PI {pi}"
        need = DELIVERY_FIELDS if delivery else TRANSFER_OUT_FIELDS
        for x in items:
            if not isinstance(x, dict):
                return "items 每条须为对象"
            lack = [k for k in need if x.get(k) in (None, "")]
            if lack:
                return f"{x.get('sn')} 缺 {', '.join(lack)}"
            if not isinstance(x["rev"], int) or isinstance(x["rev"], bool) or x["rev"] < 1:
                return f"{x['sn']} 的 rev 须为正整数"
            if delivery and x["pi"] != batch["pi"]:
                return f"{x['sn']} 的 pi {x['pi']} 与批次头 {batch['pi']} 不一致"
        if len({x["sn"] for x in items}) != len(items):
            return "批内 SN 重复"
        return None

    def process(self, org: str, batch: dict, source: str = "PULL") -> dict:
        """推送和取批共用；整批在一把锁里处理（对接说明 3.2 节），逐枚按 rev 新增或覆盖。"""
        with self.lock:
            no = batch.get("batchNo")
            problem = self._problem(org, batch)
            if problem:
                # 整批退回不登记批次号：修好后再送达同一批会重新处理
                return _reject(no, problem)
            # 第 3 步：幂等
            seen = self.batches.setdefault(org, {})
            if no in seen:
                if seen[no]["digest"] != batch["itemsDigest"]:
                    return {"__status": 409, **_err("BATCH_CONTENT_MISMATCH", "同一批次号内容不同", no)}
                return seen[no]["receipt"]
            # 第 4 步：逐枚处理；rev 小于本地的跳过
            store = self.store.setdefault(org, {})
            out = batch["batchType"] == "TRANSFER_OUT"
            for x in batch["items"]:
                cur = store.get(x["sn"])
                if cur is not None and x["rev"] < cur["rev"]:
                    continue
                if out:
                    # 本地没有时也建一条只有 SN、rev 的已转走记录，挡住迟到的旧交付
                    rec = dict(cur or {})
                    rec.update(rev=x["rev"], lastBatchNo=no, transferredOut=True, toOrgCode=x["toOrgCode"])
                else:
                    rec = {
                        "rev": x["rev"],
                        "lastBatchNo": no,
                        "transferredOut": False,
                        "toOrgCode": None,
                        "serialPi": x["serialPi"],
                        "pi": x["pi"],
                        "billNo": x["billNo"],
                        "billSeq": x["billSeq"],
                        "attachNo": x.get("attachNo"),
                        "materialCode": x.get("materialCode"),
                        "customerCode": x["customerCode"],
                        "length": x.get("length"),
                        # 记下最近一次的批次类型备查；处理只看 rev
                        "batchType": batch["batchType"],
                    }
                store[x["sn"]] = rec
            receipt = _receipt(no)
            # 第 5 步：先落库，再回执
            seen[no] = {"digest": batch["itemsDigest"], "receipt": receipt, "source": source, "at": now_cst()}
            return receipt

    # ------------------------------------------------------------------ 故障

    def set_fault(self, org: str, spec: dict) -> dict:
        """按 FAULT_MODES 设置故障；times > 0 时生效该次数后自动清除。"""
        mode = spec.get("mode")
        if mode not in FAULT_MODES:
            raise ValueError(f"mode 只能是 {', '.join(FAULT_MODES)}")
        spec = {"mode": mode, "times": int(spec.get("times") or 0), **{k: v for k, v in spec.items() if k != "mode"}}
        left = {"n": spec["times"]}
        sim = self

        def fault(batch: dict, headers: dict) -> tuple[Any, Any]:
            if batch.get("batchType") == "PING" and not spec.get("ping"):
                return None, None
            if spec["times"]:
                left["n"] -= 1
                if left["n"] <= 0:
                    sim.clear_fault(org)
            no = batch.get("batchNo")
            if mode == "http":
                st = int(spec.get("status") or 503)
                body = _err(spec.get("code") or f"HTTP_{st}", spec.get("message") or "模拟故障", no)
                return (
                    (st, body) if st not in (429, 503) else (st, {**body, "__retry_after": spec.get("retryAfter", 1)})
                )
            if mode == "drop":
                return "drop", None
            if mode == "in_progress":
                return 409, {**_err("BATCH_IN_PROGRESS", "同一批正在处理", no), "__retry_after": 1}
            if mode == "reject":
                return 200, _reject(no, spec.get("message") or "模拟整批退回")
            st, body = sim.handle(org, batch)
            if mode == "drop_after":
                return "drop", None
            if mode == "slow":
                time.sleep(float(spec.get("delay") or 70))
            if mode == "bad_receipt" and isinstance(body, dict) and "batchNo" in body:
                body = {**body, "batchNo": f"{no}-X"}
            return st, body

        self.behavior[org] = fault
        self.faults[org] = spec
        return spec

    def clear_fault(self, org: str | None = None) -> None:
        for o in [org] if org else list(self.faults):
            self.behavior.pop(o, None)
            self.faults.pop(o, None)

    def seed(self, org: str, sn: str, rev: int, last_batch_no: str) -> dict:
        """预置 MES 本侧已有的一枚号，用来造本地版本更新（更大的 rev，送达的旧版本应被跳过）。"""
        with self.lock:
            rec = {"rev": rev, "lastBatchNo": last_batch_no, "transferredOut": False}
            self.store.setdefault(org, {})[sn] = rec
            return {"org": org, "sn": sn, **rec}

    def reset(self) -> None:
        with self.lock:
            self.store.clear()
            self.batches.clear()
            self.received.clear()
        self.clear_fault()
        self.behavior.clear()


class MockMes(MesSim):
    """MesSim + HTTP 服务：POST /mes/<组织> 接收推送，/_mock/* 为控制接口。"""

    server: ThreadingHTTPServer | None = None
    scheme = "http"

    def url(self, org: str) -> str:
        assert self.server is not None
        host, port = self.server.server_address[:2]
        return f"{self.scheme}://{'127.0.0.1' if host in ('0.0.0.0', '') else host}:{port}/mes/{org}"

    def start(
        self,
        host: str = "127.0.0.1",
        port: int = 0,
        tls: ssl.SSLContext | None = None,
        log: Callable[[str], None] | None = None,
    ) -> MockMes:
        mock = self

        class H(BaseHTTPRequestHandler):
            def _send(self, status: int, body: Any, extra: dict | None = None) -> None:
                out = json.dumps(body, ensure_ascii=False, indent=None).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(out)))
                for k, v in (extra or {}).items():
                    self.send_header(k, str(v))
                self.end_headers()
                self.wfile.write(out)

            def _body(self) -> bytes:
                return self.rfile.read(int(self.headers.get("Content-Length") or 0))

            def do_GET(self):  # noqa: N802
                u = urlsplit(self.path)
                qs = {k: v[-1] for k, v in parse_qs(u.query).items()}
                if u.path == "/_mock/state":
                    with mock.lock:
                        body = {
                            "store": mock.store,
                            "batches": mock.batches,
                            "faults": mock.faults,
                            "pis": {k: sorted(v) for k, v in mock.pis.items()},
                        }
                        body = json.loads(json.dumps(body, ensure_ascii=False))
                    return self._send(200, body)
                if u.path == "/_mock/received":
                    n = max(1, int(qs.get("limit") or 20))
                    return self._send(200, [_brief(x) for x in mock.received[-n:]])
                if u.path in ("/", "/_mock"):
                    return self._send(200, {"faultModes": FAULT_MODES, "pushUrl": "/mes/<组织>"})
                return self._send(404, _err("NOT_FOUND", "地址错误"))

            def do_DELETE(self):  # noqa: N802
                u = urlsplit(self.path)
                if u.path == "/_mock/fault":
                    org = (parse_qs(u.query).get("org") or [None])[-1]
                    mock.clear_fault(org)
                    return self._send(200, {"faults": mock.faults})
                return self._send(404, _err("NOT_FOUND", "地址错误"))

            def do_POST(self):  # noqa: N802
                u = urlsplit(self.path)
                raw = self._body()
                if u.path == "/_mock/fault":
                    try:
                        spec = json.loads(raw or b"{}")
                        org = spec.pop("org")
                        return self._send(200, {"org": org, **mock.set_fault(org, spec)})
                    except (ValueError, KeyError, TypeError) as e:
                        return self._send(400, _err("BAD_REQUEST", f"故障设置有误：{e}"))
                if u.path == "/_mock/seed":
                    try:
                        spec = json.loads(raw or b"{}")
                        rec = mock.seed(spec["org"], spec["sn"], int(spec["rev"]), spec.get("lastBatchNo") or "SEED")
                        return self._send(200, rec)
                    except (ValueError, KeyError, TypeError) as e:
                        return self._send(400, _err("BAD_REQUEST", f"须给出 org、sn、rev：{e}"))
                if u.path == "/_mock/reset":
                    mock.reset()
                    return self._send(200, {"ok": True})
                if u.path.startswith("/_mock"):
                    return self._send(404, _err("NOT_FOUND", "地址错误"))
                self._push(u.path.rstrip("/").split("/")[-1], raw)

            def _push(self, org: str, raw: bytes) -> None:
                headers = {k.lower(): v for k, v in self.headers.items()}
                try:
                    if headers.get("content-encoding", "").lower() == "gzip":
                        raw = gzip.decompress(raw)
                    batch = json.loads(raw.decode("utf-8")) if raw else {}
                    if not isinstance(batch, dict):
                        raise ValueError("not an object")
                except (OSError, ValueError, UnicodeDecodeError):
                    return self._send(400, _err("BAD_REQUEST", "请求体无法解析"))
                no = batch.get("batchNo")
                mock.received.append({"org": org, "batch": batch, "headers": headers, "raw": raw, "at": now_cst()})
                why = mock.authorized(org, headers, raw)
                if why:
                    status, body = 401, _err("UNAUTHORIZED", why, no)
                else:
                    beh = mock.behavior.get(org)
                    status, body = (None, None) if beh is None else beh(batch, headers)
                    if status is None:
                        status, body = mock.handle(org, batch, "PUSH")
                if log:
                    log(_line(org, batch, headers, status, body))
                if status == "drop":
                    self.close_connection = True
                    self.connection.close()
                    return
                extra = {}
                if isinstance(body, dict) and "__retry_after" in body:
                    body = dict(body)
                    extra["Retry-After"] = body.pop("__retry_after")
                self._send(status, body, extra)

            def log_message(self, *args):  # noqa: D401
                pass

        self.server = ThreadingHTTPServer((host, port), H)
        if tls is not None:
            self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
            self.scheme = "https"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def stop(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()


def _brief(x: dict) -> dict:
    b = x["batch"]
    return {
        "at": x.get("at"),
        "org": x["org"],
        "batchNo": b.get("batchNo"),
        "batchType": b.get("batchType"),
        "orgCode": b.get("orgCode"),
        "count": len(b.get("items") or []),
        "attempt": x["headers"].get("x-sn-attempt"),
        "requestId": x["headers"].get("x-request-id"),
        "auth": "HMAC" if "x-sn-signature" in x["headers"] else "TOKEN",
        "firstItem": (b.get("items") or [None])[0],
    }


def _line(org: str, batch: dict, headers: dict, status: Any, body: Any) -> str:
    n = len(batch.get("items") or [])
    head = (
        f"{now_cst()} 推送 org={org} {batch.get('batchType')} {batch.get('batchNo')} count={n} "
        f"attempt={headers.get('x-sn-attempt')}"
    )
    if status == "drop":
        return head + " → 断开连接"
    if status == 200 and isinstance(body, dict) and body.get("code") == "NG":
        return head + f" → {status} NG {body.get('message')}"
    return head + f" → {status} {body.get('code') if isinstance(body, dict) else ''}"


# ---------------------------------------------------------------------- 取批（接口 ②③）


class Puller:
    """扮演 MES 取批：② 取批 → process → ③ 提交回执，remaining > 0 时继续取（对接说明第 2 章、接口文档 3.2）。
    每次请求带 Authorization: Bearer <工号>:<密钥>，不换令牌。"""

    PREFIX = "/api/mes/v1"

    def __init__(
        self, sim: MesSim, org: str, http: httpx.Client, emp_no: str, key: str, log: Callable[[str], None] = print
    ) -> None:
        self.sim, self.org, self.http, self.emp_no, self.key, self.log = sim, org, http, emp_no, key, log

    def call(self, path: str, body: dict) -> tuple[int, Any, httpx.Response]:
        auth = {"Authorization": f"Bearer {self.emp_no}:{self.key}"}
        r = self.http.post(self.PREFIX + path, json=body, headers=auth)
        return r.status_code, _json(r), r

    def run_once(
        self, pi: str | None = None, bill: str | None = None, batch_no: str | None = None, max_batches: int = 1000
    ) -> list[dict]:
        """取到没有可给的批次（或 remaining = 0）为止；返回每批的处理记录。带 batchNo 时只取回这一批。"""
        done: list[dict] = []
        for _ in range(max_batches):
            st, data, resp = self.call("/batches/next", {"pi": pi, "billNo": bill, "batchNo": batch_no})
            if st == 429:
                wait = int(resp.headers.get("Retry-After") or 1)
                self.log(f"② 限流 429，{wait} 秒后再取")
                time.sleep(min(wait, 60))
                continue
            if st != 200:
                self.log(f"② 取批失败：{st} {data}")
                done.append({"step": "next", "status": st, "body": data})
                break
            b = data.get("batch")
            if b is None:
                self.log(f"② 没有可给的批次：remaining={data.get('remaining')} paused={data.get('paused')}")
                break
            self.log(
                f"② 取到 {b['batchType']} {b['batchNo']} count={len(b['items'])} remaining={data.get('remaining')}"
            )
            st, receipt = self.sim.handle(self.org, b, "PULL")
            if st != 200:
                self.log(f"   process 返回 {st} {receipt}，停止")
                done.append({"step": "process", "batchNo": b["batchNo"], "status": st, "body": receipt})
                break
            rs, rd, _ = self.call(f"/batches/{b['batchNo']}/receipt", receipt)
            rec = {
                "step": "receipt",
                "batchNo": b["batchNo"],
                "status": rs,
                "body": rd,
                "code": receipt.get("code"),
            }
            done.append(rec)
            if rs == 200:
                self.log(f"③ 回执 {b['batchNo']} {receipt.get('code')} → 已受理")
            elif rs == 409 and isinstance(rd, dict) and rd.get("code") == "BATCH_VOIDED":
                self.log(f"③ {b['batchNo']} 已作废，丢弃")
            else:
                self.log(f"③ 回执被拒 {b['batchNo']} → {rs} {rd}")
            if batch_no or not data.get("remaining"):
                break
        return done


def _json(r: httpx.Response) -> Any:
    try:
        return r.json() if r.content else None
    except ValueError:
        return r.text[:500]


# ---------------------------------------------------------------------- 命令行


def _pairs(values: list[str] | None, usage: str = "--secret 须为 组织=凭证") -> dict[str, str]:
    out = {}
    for v in values or []:
        org, sep, secret = v.partition("=")
        if not sep or not org:
            raise SystemExit(f"{usage}：{v}")
        out[org] = secret
    return out


def _client(base: str, insecure: bool, timeout: float = 60) -> httpx.Client:
    return httpx.Client(
        base_url=base.rstrip("/"), trust_env=False, follow_redirects=False, timeout=timeout, verify=not insecure
    )


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m devtools.mes_mock", description="模拟 MES（只用于测试环境）")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve", help="接收推送（接口 ①），可同时周期取批")
    s.add_argument("--host", default="127.0.0.1", help="监听地址；本系统在别的机器上时用 0.0.0.0")
    s.add_argument("--port", type=int, default=9001)
    s.add_argument(
        "--secret", action="append", metavar="组织=凭证", help="各组织的令牌（TOKEN）或签名密钥（HMAC），可重复"
    )
    s.add_argument("--default-secret", help="未单独配置的组织都用这个凭证")
    s.add_argument(
        "--pi", action="append", metavar="组织=PI,PI", help="组织已有的 PI，交付批次头的 pi 不在其中时整批退回；可重复"
    )
    s.add_argument("--max-batch", type=int, default=5000, help="单批上限，超过整批拒收")
    s.add_argument("--max-skew", type=int, default=300, help="HMAC 时间戳允许偏差秒数，0 不校验")
    s.add_argument("--tls-cert", help="HTTPS 证书（PEM）；与 --tls-key 一起给出时以 HTTPS 监听")
    s.add_argument("--tls-key")
    s.add_argument("--pull-base", help="本系统地址，如 http://127.0.0.1:8000；给出时周期取批")
    s.add_argument("--pull-org")
    s.add_argument("--pull-emp")
    s.add_argument("--pull-key", help="MES 接口账户密钥")
    s.add_argument("--pull-every", type=int, default=300, help="取批间隔秒数（对接说明：每 5 分钟不带范围取一次）")
    s.add_argument("--insecure", action="store_true", help="取批时不校验本系统的 HTTPS 证书（自签证书）")

    q = sub.add_parser("pull", help="取一轮批：② → process → ③，直到没有可给的批次")
    q.add_argument("--base", required=True, help="本系统地址，如 http://127.0.0.1:8000")
    q.add_argument("--org", required=True)
    q.add_argument("--emp", required=True, help="MES 接口账户工号")
    q.add_argument("--key", required=True, help="MES 接口账户密钥")
    q.add_argument("--pi")
    q.add_argument("--bill")
    q.add_argument("--batch-no", help="只取回这一批（不建批、不改状态）")
    q.add_argument("--insecure", action="store_true")
    a = p.parse_args(argv)

    if a.cmd == "pull":
        sim = MesSim()
        try:
            with _client(a.base, a.insecure) as http:
                done = Puller(sim, a.org, http, a.emp, a.key).run_once(a.pi, a.bill, a.batch_no)
        except (RuntimeError, httpx.HTTPError) as e:
            print(f"取批失败：{e}", file=sys.stderr)
            return 1
        bad = [d for d in done if d.get("status") != 200]
        print(f"共处理 {len(done)} 批，失败 {len(bad)} 批")
        return 1 if bad else 0

    mock = MockMes(max_batch=a.max_batch, max_skew=a.max_skew)
    mock.secrets.update(_pairs(a.secret))
    mock.default_secret = a.default_secret
    for org, pis in _pairs(a.pi, "--pi 须为 组织=PI,PI").items():
        mock.pis.setdefault(org, set()).update(x for x in pis.split(",") if x)
    if not mock.secrets and mock.default_secret is None:
        print("提示：未配置 --secret / --default-secret，所有推送都会 401", file=sys.stderr)
    tls = None
    if a.tls_cert and a.tls_key:
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(a.tls_cert, a.tls_key)
    mock.start(a.host, a.port, tls, log=lambda s: print(s, flush=True))
    orgs = sorted(mock.secrets) or ["<组织>"]
    print(f"模拟 MES 已启动：{mock.scheme}://{a.host}:{a.port}/_mock（控制接口）", flush=True)
    for org in orgs:
        print(f"  组织 {org} 接收推送地址：{mock.url(org)}", flush=True)
    stop = threading.Event()
    if a.pull_base:
        if not (a.pull_org and a.pull_emp and a.pull_key):
            raise SystemExit("周期取批须同时给出 --pull-org、--pull-emp、--pull-key")

        def loop() -> None:
            with _client(a.pull_base, a.insecure) as http:
                puller = Puller(mock, a.pull_org, http, a.pull_emp, a.pull_key, log=lambda s: print(s, flush=True))
                while not stop.is_set():
                    try:
                        puller.run_once()
                    except Exception as e:  # noqa: BLE001 — 联调工具：记下继续
                        print(f"取批出错：{type(e).__name__} {e}", flush=True)
                    stop.wait(max(60, a.pull_every))

        threading.Thread(target=loop, daemon=True).start()
        print(f"  周期取批：组织 {a.pull_org}，每 {max(60, a.pull_every)} 秒一次", flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        stop.set()
        mock.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
