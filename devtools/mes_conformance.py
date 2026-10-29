"""MES 接口契约测试：按《MES 对接接口文档》《MES 对接说明》逐项检查 MES 方接收推送接口（①）与回执（③），输出报告。

MES 方接口一提供就能测，不需要本系统：脚本自己造批次（PING、交付、转出），按接口文档的方式发送（默认原始 JSON，
加 --gzip 时压缩；Bearer、HMAC 签名或无认证），逐项核对——① 应答已收到（code = OK），MES 落库后调 ③ 提交
回执（整批校验与退回 NG、按批次号去重、逐枚按 rev 覆盖或跳过、转出、并发送达），以及连接测试。
脚本自带一个 ③ 回执接收地址（--listen，默认 0.0.0.0:9100），测试时把 MES 的回执地址（本系统地址）临时指向
http://<运行脚本的机器>:9100，MES 照常提交 /api/mes/v1/receipts 即可。只依赖 Python 3.11+ 和 httpx，可以直接交给
MES 方自测。回执只有整批结果（code = OK / NG），逐枚的处理结果须在 MES 侧按报告末尾的清单人工核对。

    uv run python -m devtools.mes_conformance --url https://mes-test.example.com/sn/receive --org 110 \\
        --pi 000001 --auth TOKEN --secret <令牌> --listen 0.0.0.0:9100 --report mes-110-report.md
    # 加 --perf 测 5000 枚一批的处理时间（建议 10 秒内）

**只能对 MES 测试环境运行**：会写入一批测试 SN（前缀 CT + 本次运行号），其中几枚会被标为「已转走」。
每次运行用新的运行号，SN、单据、批次号都不会与真实数据或上次运行重复。MES 要校验交付批次头的 PI 是本组织已有的，
所以 --pi 须给 MES 测试环境里该组织已有的一张 PI。
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import hmac
import json
import random
import re
import string
import sys
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import httpx

SCHEMA = "1.0"
PASS, FAIL, SKIP, WARN = "PASS", "FAIL", "SKIP", "WARN"


def canonical(obj: Any) -> bytes:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def digest(items: list[dict]) -> str:
    return "sha256:" + hashlib.sha256(canonical(items)).hexdigest()


#: MES 接口时间：UTC+8，格式 2026-10-26 11:47:05.303
CST = timezone(timedelta(hours=8))
TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$")


def now_cst(t: datetime | None = None) -> str:
    return (t or datetime.now(CST)).astimezone(CST).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


@dataclass
class Check:
    id: str
    title: str
    ref: str
    status: str = PASS
    detail: str = ""
    notes: list[str] = field(default_factory=list)


@dataclass
class Reply:
    status: int | None
    body: Any
    error: str = ""
    elapsed: float = 0.0

    def __str__(self) -> str:
        if self.status is None:
            return f"无应答（{self.error}）"
        b = json.dumps(self.body, ensure_ascii=False)
        return f"HTTP {self.status} {b[:300]}{'…' if len(b) > 300 else ''}"


class Failed(Exception):
    pass


class ReceiptInbox:
    """③ 回执接收地址：扮演本系统的 POST /api/mes/v1/receipts，按批次号记下 MES 提交的每一份回执。
    应答与本系统相同的信封（code = OK 已受理）；gzip 请求体、code 不是 OK / NG 时 400，同样记下供核对。
    fail[批次号] = n：该批次的前 n 份回执应答 503（模拟回执没送达），检查 MES 是否重交。"""

    PATH = "/api/mes/v1/receipts"

    def __init__(self) -> None:
        self.received: dict[str, list[dict]] = {}
        self.fail: dict[str, int] = {}
        self.cond = threading.Condition()
        self.server: ThreadingHTTPServer | None = None

    def start(self, host: str = "127.0.0.1", port: int = 0) -> ReceiptInbox:
        inbox = self

        class H(BaseHTTPRequestHandler):
            def _send(self, status: int, body: dict) -> None:
                out = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def do_POST(self):  # noqa: N802
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                headers = {k.lower(): v for k, v in self.headers.items()}
                if not self.path.rstrip("/").endswith("/receipts"):
                    return self._send(404, {"schemaVersion": SCHEMA, "code": "NOT_FOUND", "message": "地址错误"})
                if headers.get("content-encoding", "").lower() == "gzip":
                    body: Any = {"__gzip": True}
                else:
                    try:
                        body = json.loads(raw.decode("utf-8")) if raw else None
                    except (ValueError, UnicodeDecodeError):
                        body = None
                no = body.get("batchNo") if isinstance(body, dict) else None
                with inbox.cond:
                    first = not inbox.received.get(str(no))
                    inbox.received.setdefault(str(no), []).append({"body": body, "headers": headers, "at": now_cst()})
                    inbox.cond.notify_all()
                    down = inbox.fail.get(str(no), 0) > 0
                    if down:
                        inbox.fail[str(no)] -= 1
                if down:
                    return self._send(
                        503,
                        {"schemaVersion": SCHEMA, "code": "SERVICE_UNAVAILABLE", "message": "模拟故障", "batchNo": no},
                    )
                code = body.get("code") if isinstance(body, dict) else None
                if not isinstance(no, str) or code not in ("OK", "NG") or "__gzip" in body:
                    return self._send(
                        400,
                        {"schemaVersion": SCHEMA, "code": "INVALID_REQUEST", "message": "参数不合法", "batchNo": no},
                    )
                env = {"schemaVersion": SCHEMA, "code": "OK", "message": "回执已受理", "batchNo": no}
                env |= {"timestamp": now_cst(), "batchResult": "SUCCESS" if code == "OK" else "FAILED"}
                self._send(200, env | {"firstConfirmation": first})

            def log_message(self, *args):  # noqa: D401
                pass

        self.server = ThreadingHTTPServer((host, port), H)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    @property
    def base_url(self) -> str:
        """本机访问用的地址（MES 的回执地址填运行脚本机器的地址 + 端口）。"""
        assert self.server is not None
        host, port = self.server.server_address[:2]
        return f"http://{'127.0.0.1' if host in ('0.0.0.0', '') else host}:{port}"

    def count(self, batch_no: str) -> int:
        with self.cond:
            return len(self.received.get(batch_no, []))

    def wait(self, batch_no: str, after: int, timeout: float) -> dict | None:
        """等这个批次的第 after + 1 份回执，超时返回 None。"""
        end = time.monotonic() + timeout
        with self.cond:
            while len(self.received.get(batch_no, [])) <= after:
                left = end - time.monotonic()
                if left <= 0:
                    return None
                self.cond.wait(left)
            return self.received[batch_no][after]

    def stop(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()


class Runner:
    def __init__(
        self,
        url: str,
        org: str,
        auth: str,
        secret: str,
        timeout: float = 60,
        insecure: bool = False,
        run_id: str | None = None,
        gzip: bool = False,
        log: Callable[[str], None] = print,
        pi: str | None = None,
        inbox: ReceiptInbox | None = None,
        receipt_wait: float = 30,
    ) -> None:
        self.url, self.org, self.auth, self.secret, self.timeout = url, org, auth.upper(), secret, timeout
        #: 与「生产组织」页的「gzip 压缩」一致：默认发原始 JSON
        self.gzip = gzip
        self.http = httpx.Client(trust_env=False, follow_redirects=False, timeout=timeout, verify=not insecure)
        self.run = run_id or "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
        self.prefix = f"CT{self.run}"
        #: 交付批次的 PI：须是 MES 侧本组织已有的；no_pi 为本组织没有的 PI（每次运行新造）
        self.pi = pi or f"CTPI{self.run}"
        self.no_pi = f"CTNOPI{self.run}"
        self.bill = f"CTMO{self.run}"
        self.seq = 0
        self.log = log
        self.checks: list[Check] = []
        #: ③ 回执接收地址；每次送达前记下该批次已收到几份回执，送达后等下一份
        self.inbox = inbox or ReceiptInbox().start()
        self.receipt_wait = receipt_wait
        self.mark: dict[str, int] = {}

    # ------------------------------------------------------------------ 报文

    def batch_no(self) -> str:
        self.seq += 1
        return f"CT-{self.org[:12]}-{self.run}-{self.seq:03d}"

    def sn(self, i: int) -> str:
        return f"{self.prefix}{i:06d}"

    def delivery(self, i: int, rev: int, **extra: Any) -> dict:
        """交付明细；单据行号随 rev 变（rev < 3 为 1，否则为 2），便于在 MES 侧核对是否覆盖。"""
        return {
            "sn": self.sn(i),
            "rev": rev,
            "serial": f"{i:06d}",
            "serialDec": str(i),
            "serialPi": self.pi,
            "pi": self.pi,
            "billNo": self.bill,
            "billSeq": 1 if rev < 3 else 2,
            "attachNo": "CT-A1",
            "materialCode": "CT-M1",
            "length": "0100",
            "customerCode": "CT-C1",
            **extra,
        }

    def transfer_out(self, i: int, rev: int) -> dict:
        """转移明细：与交付明细同一结构，带转出前的挂靠；转入组织在批次头。"""
        return self.delivery(i, rev)

    def batch(self, items: list[dict], kind: str = "NEW", no: str | None = None, **head: Any) -> dict:
        """批次头；交付批次的 pi 取明细的 pi，转移批次取测试 PI（转出前的 PI）、toOrgCode 为 CT-TO（其他为 null）。"""
        obj: dict = {"schemaVersion": SCHEMA, "batchNo": no or self.batch_no(), "batchType": kind, "orgCode": self.org}
        obj["toOrgCode"] = "CT-TO" if kind == "TRANSFER_OUT" else None
        obj["pi"] = items[0]["pi"] if items and kind != "TRANSFER_OUT" else self.pi
        obj["count"] = len(items)
        obj |= {"createdAt": now_cst(), "itemsDigest": digest(items), "items": items}
        obj.update(head)
        return obj

    # ------------------------------------------------------------------ 发送

    def send(
        self,
        obj: dict | None = None,
        raw: bytes | None = None,
        attempt: int = 1,
        secret: str | None = None,
        ts: str | None = None,
    ) -> Reply:
        """按对接说明 ① 发送：默认原始 JSON（开启 gzip 时压缩），认证按组织配置（Bearer、HMAC 或无认证）。"""
        raw = canonical(obj) if raw is None else raw
        if obj is not None and isinstance(obj.get("batchNo"), str):
            self.mark[obj["batchNo"]] = self.inbox.count(obj["batchNo"])
        h = {
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "Accept-Encoding": "gzip",
            "X-Sn-Attempt": str(attempt),
            "X-Request-Id": str(uuid.uuid4()),
        }
        if self.gzip:
            h["Content-Encoding"] = "gzip"
        key = self.secret if secret is None else secret
        if self.auth == "HMAC":
            t = ts or now_cst()
            msg = f"{t}\n{hashlib.sha256(raw).hexdigest()}"
            h["X-Sn-Timestamp"] = t
            h["X-Sn-Signature"] = hmac.new(key.encode(), msg.encode(), hashlib.sha256).hexdigest()
        elif self.auth == "TOKEN":
            h["Authorization"] = f"Bearer {key}"
        start = time.monotonic()
        try:
            r = self.http.post(self.url, content=gzip.compress(raw, mtime=0) if self.gzip else raw, headers=h)
        except httpx.TimeoutException:
            return Reply(None, None, f"等待应答超过 {self.timeout:g} 秒", time.monotonic() - start)
        except httpx.HTTPError as e:
            return Reply(None, None, f"{type(e).__name__} {e}", time.monotonic() - start)
        try:
            body = r.json() if r.content else None
        except ValueError:
            body = r.text[:500]
        return Reply(r.status_code, body, elapsed=time.monotonic() - start)

    # ------------------------------------------------------------------ 断言

    @staticmethod
    def need(cond: bool, why: str) -> None:
        if not cond:
            raise Failed(why)

    def received(self, r: Reply, b: dict, why: str = "") -> dict:
        """① 应答：HTTP 200 + {batchNo, code = OK, message, receivedAt}（OK 只表示已收到，处理结果另由 ③ 回执）。"""
        pre = f"{why}：" if why else ""
        hint = r.status is not None and 400 <= r.status < 500
        self.need(
            r.status == 200,
            f"{pre}期望 HTTP 200 + 已收到（code = OK），实际 {r}"
            + ("（4xx 只用于认证、地址、报文过大、无法解析；整批退回用 ③ 回执 NG）" if hint else ""),
        )
        body = r.body
        self.need(isinstance(body, dict), f"{pre}应答不是 JSON 对象：{r}")
        hint = "（整批退回改由 ③ 回执 NG，① 只应答已收到）" if body.get("code") == "NG" else ""
        self.need(body.get("code") == "OK", f"{pre}期望 code = OK（已收到），实际 {r}{hint}")
        self.need(body.get("batchNo") == b["batchNo"], f"应答 batchNo = {body.get('batchNo')}，应为 {b['batchNo']}")
        at = body.get("receivedAt")
        self.need(
            isinstance(at, str) and bool(TS_RE.match(at)),
            f"应答 receivedAt 须为 UTC+8 的 yyyy-MM-dd HH:mm:ss.SSS，实际 {at!r}",
        )
        return body

    def callback(self, b: dict, why: str = "", after: int | None = None) -> dict:
        """等 MES 提交这一批的 ③ 回执（本次送达之后的下一份），核对结构，返回回执。"""
        pre = f"{why}：" if why else ""
        no = b["batchNo"]
        got = self.inbox.wait(no, self.mark.get(no, 0) if after is None else after, self.receipt_wait)
        self.need(
            got is not None,
            f"{pre}应答已收到后 {self.receipt_wait:g} 秒内没有收到 ③ 回执（MES 落库后须调 /api/mes/v1/receipts；"
            "同一批再次送达也须重新提交回执）",
        )
        self.need(
            str(got["headers"].get("authorization", "")).startswith("Bearer "),
            "③ 回执须带 Authorization: Bearer <工号>:<密钥>（MES 接口账户）",
        )
        self.need("content-encoding" not in got["headers"], "③ 回执不支持 gzip，须发原始 JSON")
        body = got["body"]
        self.need(isinstance(body, dict), f"③ 回执不是 JSON 对象：{body!r}")
        self.need(body.get("batchNo") == no, f"③ 回执 batchNo = {body.get('batchNo')}，应为 {no}")
        at = body.get("processedAt")
        self.need(
            isinstance(at, str) and bool(TS_RE.match(at)),
            f"③ 回执 processedAt 须为 UTC+8 的 yyyy-MM-dd HH:mm:ss.SSS，实际 {at!r}",
        )
        return body

    def receipt(self, r: Reply, b: dict, code: str, why: str = "") -> dict:
        """① 应答已收到，随后 ③ 回执的 code 为期望值。"""
        pre = f"{why}：" if why else ""
        self.received(r, b, why)
        body = self.callback(b, why)
        self.need(
            body.get("code") == code, f"{pre}期望 ③ 回执 code = {code}，实际 {json.dumps(body, ensure_ascii=False)}"
        )
        if code == "NG":
            self.need(isinstance(body.get("message"), str) and body["message"].strip(), "NG 回执须带 message（原因）")
        return body

    def ok(self, r: Reply, b: dict) -> dict:
        return self.receipt(r, b, "OK")

    def ng(self, r: Reply, b: dict, why: str) -> dict:
        return self.receipt(r, b, "NG", why)

    def case(self, cid: str, title: str, ref: str, fn: Callable[[Check], None]) -> Check:
        c = Check(cid, title, ref)
        try:
            fn(c)
        except Failed as e:
            c.status, c.detail = FAIL, str(e)
        except Exception as e:  # noqa: BLE001 — 报告里写明，继续后面的用例
            c.status, c.detail = FAIL, f"脚本异常：{type(e).__name__} {e}"
        self.checks.append(c)
        self.log(f"[{c.status}] {c.id} {c.title}" + (f" — {c.detail}" if c.detail else ""))
        return c

    # ------------------------------------------------------------------ 用例

    def run_all(self, perf: int = 0) -> list[Check]:
        st: dict[str, Any] = {}

        def ping(org: str) -> dict:
            return {
                "schemaVersion": SCHEMA,
                "batchNo": "PING-" + self.batch_no(),
                "batchType": "PING",
                "orgCode": org,
                "toOrgCode": None,
                "count": 0,
                "createdAt": now_cst(),
                "itemsDigest": digest([]),
                "items": [],
            }

        def ping_ok(c: Check) -> None:
            b = ping(self.org)
            r = self.send(b)
            self.need(r.status == 200 and isinstance(r.body, dict), f"PING 期望 HTTP 200 + 已收到，实际 {r}")
            code = r.body.get("code")
            self.need(code == "OK", f"PING 期望 code = OK，实际 {r}")
            c.detail = f"耗时 {r.elapsed:.2f} 秒"

        def ping_org(c: Check) -> None:
            b = ping(self.org + "X")
            r = self.send(b)
            self.need(
                r.status == 200 and isinstance(r.body, dict),
                f"PING 的 orgCode 不是本组织：期望 HTTP 200 + NG，实际 {r}",
            )
            self.need(
                r.body.get("code") == "NG",
                f"PING 的 orgCode 不是本组织：期望应答 code = NG（PING 没有 ③ 回执），实际 {r}",
            )

        def auth_bad(c: Check) -> None:
            if self.auth == "NONE":
                c.status, c.detail = SKIP, "认证方式为无认证，不适用"
                return
            b = self.batch([self.delivery(90, 1)])
            r = self.send(b, secret=self.secret + "-wrong")
            self.need(r.status in (401, 403), f"凭证错误应返回 401 或 403，实际 {r}")

        def hmac_skew(c: Check) -> None:
            if self.auth != "HMAC":
                c.status, c.detail = SKIP, f"认证方式为 {self.auth}，不适用"
                return
            b = self.batch([self.delivery(91, 1)])
            r = self.send(b, ts=now_cst(datetime.now(CST) - timedelta(minutes=10)))
            self.need(r.status in (401, 403), f"时间戳偏差 10 分钟应拒绝（401 或 403），实际 {r}")

        def first(c: Check) -> None:
            b = self.batch([self.delivery(1, 1), self.delivery(2, 1), self.delivery(3, 1)])
            r = self.send(b)
            self.ok(r, b)
            st["b1"] = b
            c.detail = f"{b['batchNo']}，耗时 {r.elapsed:.2f} 秒"
            c.notes.append(f"请在 MES 侧确认 {self.sn(1)}～{self.sn(3)} 已新增，rev = 1")

        def replay(c: Check) -> None:
            self.need("b1" in st, "依赖 C05 未通过")
            b = st["b1"]
            self.ok(self.send(b, attempt=2), b)

        def mismatch(c: Check) -> None:
            self.need("b1" in st, "依赖 C05 未通过")
            items = [self.delivery(1, 1), self.delivery(2, 1)]
            b = self.batch(items, no=st["b1"]["batchNo"])
            r = self.send(b)
            self.need(r.status == 409, f"同一批次号内容不同应返回 409，实际 {r}")
            code = r.body.get("code") if isinstance(r.body, dict) else None
            self.need(code == "BATCH_CONTENT_MISMATCH", f"code 应为 BATCH_CONTENT_MISMATCH，实际 {code}")

        def overwrite(c: Check) -> None:
            self.need("b1" in st, "依赖 C05 未通过")
            b = self.batch([self.delivery(1, 3)], "SAME_ORG_TRANSFER")
            self.ok(self.send(b), b)
            st["rev3"] = True
            c.notes.append(f"请在 MES 侧确认 {self.sn(1)} 已覆盖为 rev = 3、单据行号 2")

        def same_rev(c: Check) -> None:
            self.need("rev3" in st, "依赖 C08 未通过")
            b = self.batch([self.delivery(1, 3)])
            self.ok(self.send(b), b)
            c.notes.append(f"请在 MES 侧确认 {self.sn(1)} 仍为 rev = 3、单据行号 2（同 rev 重复覆盖，内容不变）")

        def older(c: Check) -> None:
            self.need("rev3" in st, "依赖 C08 未通过")
            b = self.batch([self.delivery(1, 2), self.delivery(4, 1)])
            self.ok(self.send(b), b)
            c.notes.append(f"请在 MES 侧确认 {self.sn(1)} 未被覆盖（仍为 rev = 3），{self.sn(4)} 已新增")

        def transfer_out(c: Check) -> None:
            self.need("b1" in st, "依赖 C05 未通过")
            b = self.batch([self.transfer_out(2, 2)], "TRANSFER_OUT")
            self.ok(self.send(b), b)
            c.notes.append(f"请在 MES 侧确认 {self.sn(2)} 已标「已转走」、停止打标，rev = 2，toOrgCode = CT-TO")

        def transfer_missing(c: Check) -> None:
            b = self.batch([self.transfer_out(5, 2)], "TRANSFER_OUT")
            self.ok(self.send(b), b)
            late = self.batch([self.delivery(5, 1)])
            self.ok(self.send(late), late)
            c.notes.append(
                f"请在 MES 侧确认 {self.sn(5)} 为「已转走」、rev = 2（随后迟到的 rev 1 交付已跳过，不可打标）"
            )

        def transfer_back(c: Check) -> None:
            self.need("b1" in st, "依赖 C05 未通过")
            b = self.batch([self.delivery(2, 3)], "TRANSFER_IN")
            self.ok(self.send(b), b)
            c.notes.append(f"请在 MES 侧确认 {self.sn(2)} 已清除「已转走」，rev = 3、单据行号 2")

        def unknown_fields(c: Check) -> None:
            items = [self.delivery(7, 1, futureField="x"), self.delivery(8, 1)]
            b = self.batch(items, futureHead={"a": 1})
            self.ok(self.send(b), b)
            b2 = self.batch([self.delivery(9, 1)], "REMAP")
            self.ok(self.send(b2), b2)
            b3 = self.batch([self.delivery(9, 1)], "FUTURE_TYPE")
            self.ng(self.send(b3), b3, "不认识的 batchType 应整批退回")

        def reject_then_fix(c: Check, why: str, bad: dict, good: dict) -> None:
            self.ng(self.send(bad), bad, why)
            r = self.send(good)
            self.received(r, good, "修正后用同一批次号重送")
            rb = self.callback(good, "修正后用同一批次号重送")
            self.need(rb.get("code") != "NG", "修正后用同一批次号重送，③ 回执仍为 NG：整批退回时不能登记批次号")
            self.need(rb.get("code") == "OK", f"修正后用同一批次号重送：期望 ③ 回执 code = OK，实际 {rb}")

        def count_bad(c: Check) -> None:
            items = [self.delivery(10, 1)]
            no = self.batch_no()
            reject_then_fix(c, "count 与 items 条数不符", self.batch(items, no=no, count=2), self.batch(items, no=no))

        def org_bad(c: Check) -> None:
            items = [self.delivery(11, 1)]
            no = self.batch_no()
            bad = self.batch(items, no=no, orgCode=self.org + "X")
            reject_then_fix(c, "orgCode 不是本组织", bad, self.batch(items, no=no))

        def dup_sn(c: Check) -> None:
            b = self.batch([self.delivery(12, 1), self.delivery(12, 1)])
            self.ng(self.send(b), b, "批内 SN 重复")

        def out_missing(c: Check) -> None:
            b = self.batch([self.transfer_out(17, 1)], "TRANSFER_OUT", toOrgCode=None)
            self.ng(self.send(b), b, "转出批次 toOrgCode 为空")

        def missing(c: Check) -> None:
            item = self.delivery(19, 1)
            del item["billNo"]
            b = self.batch([item])
            self.ng(self.send(b), b, "交付项缺必填字段 billNo")
            c.notes.append(f"请在 MES 侧确认 {self.sn(19)} 未写入（整批退回不落任何数据）")

        def pi_bad(c: Check) -> None:
            no = self.batch_no()
            bad = self.batch([self.delivery(71, 1, pi=self.no_pi, serialPi=self.no_pi)], no=no)
            good = self.batch([self.delivery(71, 1)], no=no)
            reject_then_fix(c, f"本组织没有 PI {self.no_pi}", bad, good)

        def pi_head(c: Check) -> None:
            b = self.batch([self.delivery(72, 1)])
            del b["pi"]
            self.ng(self.send(b), b, "交付批次缺批次头 pi")
            b = self.batch([self.delivery(73, 1), self.delivery(74, 1, pi=self.no_pi)])
            self.ng(self.send(b), b, "明细 pi 与批次头 pi 不一致")
            c.notes.append(f"请在 MES 侧确认 {self.sn(72)}～{self.sn(74)} 未写入（整批退回不落任何数据）")

        def bad_body(c: Check) -> None:
            r = self.send(raw=b"{not json")
            self.need(r.status == 400, f"请求体无法解析应返回 400，实际 {r}")

        def concurrent(c: Check) -> None:
            b = self.batch([self.delivery(20 + k, 1) for k in range(50)])
            before = self.inbox.count(b["batchNo"])
            out: list[Reply] = [Reply(None, None), Reply(None, None)]

            def go(k: int) -> None:
                out[k] = self.send(b, attempt=k + 1)

            ts = [threading.Thread(target=go, args=(k,)) for k in range(2)]
            for t in ts:
                t.start()
            for t in ts:
                t.join()
            busy = [r for r in out if r.status == 409]
            for r in busy:
                code = r.body.get("code") if isinstance(r.body, dict) else None
                self.need(code == "BATCH_IN_PROGRESS", f"并发送达只能等处理完或返回 409 BATCH_IN_PROGRESS，实际 {r}")
            if busy:
                time.sleep(2)
                out = [r for r in out if r.status != 409] + [self.send(b, attempt=3)]
            for r in out:
                self.received(r, b)
            rb = self.callback(b, after=before)
            self.need(rb.get("code") == "OK", f"期望 ③ 回执 code = OK，实际 {rb}")
            time.sleep(0.5)
            codes = {
                x["body"].get("code") for x in self.inbox.received[b["batchNo"]][before:] if isinstance(x["body"], dict)
            }
            self.need(
                codes == {"OK"}, f"同一批的几份 ③ 回执须相同（都为首次的结果），实际 code {sorted(map(str, codes))}"
            )
            c.detail = "其中一次返回 409 BATCH_IN_PROGRESS" if busy else "两次都应答已收到"

        def receipt_retry(c: Check) -> None:
            b = self.batch([self.delivery(80, 1)])
            self.inbox.fail[b["batchNo"]] = 1
            r = self.send(b)
            self.received(r, b)
            first = self.callback(b)
            again = self.inbox.wait(b["batchNo"], self.mark[b["batchNo"]] + 1, self.receipt_wait)
            self.need(
                again is not None,
                f"③ 回执返回 503 后 {self.receipt_wait:g} 秒内没有重交（回执只有收到 HTTP 200 + code = OK 才算送达，"
                "没送达须用同一份回执重交）",
            )
            body = again["body"]
            same = isinstance(body, dict) and (body.get("batchNo"), body.get("code")) == (
                b["batchNo"],
                first.get("code"),
            )
            self.need(same, f"重交的回执须与第一次相同（batchNo、code），实际 {body!r}")

        def performance(c: Check) -> None:
            if not perf:
                c.status, c.detail = SKIP, "未加 --perf"
                return
            b = self.batch([self.delivery(100000 + k, 1) for k in range(perf)])
            start = time.monotonic()
            r = self.send(b)
            self.ok(r, b)
            took = time.monotonic() - start
            c.detail = f"{perf} 枚：应答已收到 {r.elapsed:.1f} 秒，送达到 ③ 回执共 {took:.1f} 秒"
            if took > 10:
                c.status, c.detail = WARN, c.detail + "（建议 10 秒内）"

        cases = [
            ("C01", "连接测试：PING 应答已收到（OK），不调 ③", "接口 3.1", ping_ok),
            ("C02", "连接测试：PING 的 orgCode 不符时应答 NG", "接口 3.1；说明 3.2 步骤 1", ping_org),
            ("C03", "认证：凭证错误返回 401 / 403", "接口 2.1", auth_bad),
            ("C04", "认证（HMAC）：时间戳偏差超过 5 分钟拒绝", "接口 2.1", hmac_skew),
            ("C05", "交付批次首次送达：应答已收到，③ 回执 OK，逐枚新增", "说明 3.2 逐枚处理；接口 3.1、3.3", first),
            ("C06", "同一批原样再次送达：应答已收到，重新提交 ③ 回执 OK", "说明 3.2 步骤 3", replay),
            ("C07", "同一批次号内容不同：409 BATCH_CONTENT_MISMATCH", "说明 3.2 步骤 3", mismatch),
            ("C08", "rev 更大：覆盖挂靠，OK", "说明 3.2 逐枚处理、1.3", overwrite),
            ("C09", "rev 相同、来自别的批次：覆盖（内容相同），OK", "说明 3.2 逐枚处理", same_rev),
            ("C10", "rev 更小：跳过该枚，整批仍 OK", "说明 3.2 逐枚处理", older),
            ("C11", "转出批次 TRANSFER_OUT：标记已转走，OK", "说明 3.2 逐枚处理；接口 3.1", transfer_out),
            (
                "C12",
                "转出时本地没有该 SN：建已转走记录；迟到的旧交付跳过",
                "说明 3.2 逐枚处理",
                transfer_missing,
            ),
            ("C13", "转回原组织：rev 更大，覆盖并清除已转走", "说明 3.2 逐枚处理、1.3", transfer_back),
            (
                "C14",
                "不认识的字段照常处理；四种交付类 batchType 都按交付处理；不认识的 batchType 整批 NG",
                "说明 3.2 步骤 1、3.3；接口 2.2",
                unknown_fields,
            ),
            ("C15", "orgCode 不是本组织：NG；修正后同一批次号重送能处理", "说明 3.2 步骤 2、5", org_bad),
            ("C16", "批内 SN 重复：NG", "说明 3.2 步骤 2", dup_sn),
            ("C17", "转出批次 toOrgCode 为空：NG", "说明 3.2 步骤 2", out_missing),
            (
                "C18",
                "缺必填字段：应答已收到（不是 4xx），③ 回执 NG，不落任何数据",
                "说明 3.2 步骤 2；接口 3.1",
                missing,
            ),
            ("C19", "请求体无法解析：400", "接口 3.1", bad_body),
            ("C20", "同一批同时送达两次：只处理一次，③ 回执都为 OK", "说明 2、3.2 步骤 5", concurrent),
            ("C21", "5000 枚一批的处理时间", "说明 3.2 步骤 5", performance),
            ("C22", "批次头 pi 不是本组织已有的 PI：NG；修正后同一批次号重送能处理", "说明 3.2 步骤 2、5", pi_bad),
            ("C23", "交付批次缺批次头 pi、明细 pi 与批次头不一致：NG", "说明 3.2 步骤 2", pi_head),
            ("C24", "count 与 items 条数不符：NG；修正后同一批次号重送能处理", "说明 3.2 步骤 2、5", count_bad),
            (
                "C25",
                "③ 回执没送达（返回 503）：MES 用同一份回执重交，直到 HTTP 200 + code = OK",
                "接口 3.3.6",
                receipt_retry,
            ),
        ]
        for cid, title, ref, fn in cases:
            self.case(cid, title, ref, fn)
        return self.checks

    # ------------------------------------------------------------------ 报告

    def report(self) -> str:
        n = {k: sum(c.status == k for c in self.checks) for k in (PASS, FAIL, WARN, SKIP)}
        lines = [
            "# MES 接口契约测试报告",
            "",
            f"- 时间：{now_cst()}（UTC+8）",
            f"- 接口地址：{self.url}",
            f"- ③ 回执接收地址：{self.inbox.base_url}{ReceiptInbox.PATH}（等回执 {self.receipt_wait:g} 秒）",
            f"- 生产组织：{self.org}　认证方式：{self.auth}",
            f"- 运行号：{self.run}（测试 SN 前缀 `{self.prefix}`，PI `{self.pi}`，单据 `{self.bill}`）",
            f"- 结果：通过 {n[PASS]}，失败 {n[FAIL]}，提醒 {n[WARN]}，跳过 {n[SKIP]}",
            "",
            "「依据」列：「说明 x」为《MES 对接说明》章节，「接口 x」为《MES 对接接口文档》章节。",
            "",
            "| # | 检查项 | 依据 | 结果 | 说明 |",
            "| --- | --- | --- | --- | --- |",
        ]
        for c in self.checks:
            detail = c.detail.replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {c.id} | {c.title} | {c.ref} | {c.status} | {detail} |")
        notes = [f"- {c.id}：{x}" for c in self.checks if c.status == PASS for x in c.notes]
        if notes:
            lines += [
                "",
                "## 需在 MES 侧人工核对",
                "",
                "回执只有整批结果，看不到 MES 的本侧数据，以下几项请在 MES 库或页面上核对：",
                "",
            ]
            lines += notes
        return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m devtools.mes_conformance", description="MES 接收推送接口（①）契约测试")
    p.add_argument("--url", required=True, help="MES 接收推送的地址（与「生产组织」页填写的相同）")
    p.add_argument("--org", required=True, help="生产组织编码（批次的 orgCode）")
    p.add_argument("--pi", required=True, help="MES 测试环境中该组织已有的一张 PI（交付批次头的 pi）")
    p.add_argument("--auth", choices=("TOKEN", "HMAC", "NONE"), default="TOKEN", help="NONE 为无认证（不带认证头）")
    p.add_argument("--secret", default="", help="TOKEN 为 Bearer 令牌，HMAC 为签名密钥；NONE 时不需要")
    p.add_argument("--timeout", type=float, default=60, help="等待应答秒数（与本系统推送超时一致，默认 60）")
    p.add_argument(
        "--listen",
        default="0.0.0.0:9100",
        help="③ 回执接收地址（主机:端口）；MES 的回执地址临时指向 http://<本机>:<端口>，默认 0.0.0.0:9100",
    )
    p.add_argument("--receipt-wait", type=float, default=30, help="应答已收到后等 ③ 回执的秒数，默认 30")
    p.add_argument("--perf", type=int, nargs="?", const=5000, default=0, help="测一批的处理时间，默认 5000 枚")
    p.add_argument("--report", help="把报告写成 Markdown 文件")
    p.add_argument("--insecure", action="store_true", help="不校验 HTTPS 证书（测试环境自签证书）")
    p.add_argument("--gzip", action="store_true", help="请求体 gzip 压缩（与「生产组织」页开启「gzip 压缩」时相同）")
    a = p.parse_args(argv)
    if a.auth != "NONE" and not a.secret:
        p.error("--auth 为 TOKEN 或 HMAC 时须给出 --secret")
    host, _, port = a.listen.rpartition(":")
    inbox = ReceiptInbox().start(host or "0.0.0.0", int(port or 9100))
    r = Runner(
        a.url,
        a.org,
        a.auth,
        a.secret,
        a.timeout,
        a.insecure,
        gzip=a.gzip,
        pi=a.pi,
        inbox=inbox,
        receipt_wait=a.receipt_wait,
    )
    print(f"运行号 {r.run}，测试 SN 前缀 {r.prefix}；只能对 MES 测试环境运行")
    print(f"③ 回执接收地址：http://{a.listen}{ReceiptInbox.PATH}（MES 的回执地址须指向这里）\n")
    r.run_all(a.perf)
    text = r.report()
    if a.report:
        with open(a.report, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"\n报告已写入 {a.report}")
    failed = sum(c.status == FAIL for c in r.checks)
    print(f"\n失败 {failed} 项" if failed else "\n全部通过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
