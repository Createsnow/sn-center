"""MES 接口契约测试：按《MES 对接接口文档》《MES 对接说明》逐项检查 MES 方接收推送接口（①），输出报告。

MES 方接口一提供就能测，不需要本系统：脚本自己造批次（PING、交付、转出），按接口文档的方式发送（默认原始 JSON，
加 --gzip 时压缩；Bearer 或 HMAC 签名），逐项核对应答——整批校验与退回（NG）、按批次号去重、
逐枚按 rev 覆盖或跳过、转出、并发送达、连接测试。只依赖 Python 3.11+ 和 httpx，可以直接交给 MES 方自测。
回执只有整批结果（code = OK / NG），逐枚的处理结果须在 MES 侧按报告末尾的清单人工核对。

    uv run python -m devtools.mes_conformance --url https://mes-test.example.com/sn/receive --org 110 \\
        --pi 000001 --auth TOKEN --secret <令牌> --report mes-110-report.md
    # 加 --perf 测 5000 枚一批的处理时间（建议 10 秒内，超过 60 秒本系统会超时）

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
        """按对接说明 ① 发送：默认原始 JSON（开启 gzip 时压缩），认证按组织配置（Bearer 或 HMAC）。"""
        raw = canonical(obj) if raw is None else raw
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
        else:
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

    def receipt(self, r: Reply, b: dict, code: str, why: str = "") -> dict:
        """HTTP 200 + 回执 {batchNo, code, message, processedAt}，code 为期望值。"""
        pre = f"{why}：" if why else ""
        hint = code == "NG" and r.status is not None and 400 <= r.status < 500
        self.need(
            r.status == 200,
            f"{pre}期望 HTTP 200 + 回执（code = {code}），实际 {r}"
            + ("（4xx 只用于认证、地址、报文过大、无法解析）" if hint else ""),
        )
        body = r.body
        self.need(isinstance(body, dict), f"{pre}应答不是 JSON 对象：{r}")
        self.need(body.get("code") == code, f"{pre}期望 code = {code}，实际 {r}")
        self.need(body.get("batchNo") == b["batchNo"], f"回执 batchNo = {body.get('batchNo')}，应为 {b['batchNo']}")
        at = body.get("processedAt")
        self.need(
            isinstance(at, str) and bool(TS_RE.match(at)),
            f"回执 processedAt 须为 UTC+8 的 yyyy-MM-dd HH:mm:ss.SSS，实际 {at!r}",
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
                "createdAt": now_cst(),
                "itemsDigest": digest([]),
                "items": [],
            }

        def ping_ok(c: Check) -> None:
            b = ping(self.org)
            r = self.send(b)
            self.ok(r, b)
            c.detail = f"耗时 {r.elapsed:.2f} 秒"

        def ping_org(c: Check) -> None:
            b = ping(self.org + "X")
            self.ng(self.send(b), b, "PING 的 orgCode 不是本组织")

        def auth_bad(c: Check) -> None:
            b = self.batch([self.delivery(90, 1)])
            r = self.send(b, secret=self.secret + "-wrong")
            self.need(r.status in (401, 403), f"凭证错误应返回 401 或 403，实际 {r}")

        def hmac_skew(c: Check) -> None:
            if self.auth != "HMAC":
                c.status, c.detail = SKIP, "认证方式为 TOKEN，不适用"
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
            self.need(
                not (isinstance(r.body, dict) and r.body.get("code") == "NG"),
                "修正后用同一批次号重送仍返回 NG：整批退回时不能登记批次号",
            )
            self.ok(r, good)

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
                self.ok(r, b)
            c.detail = "其中一次返回 409 BATCH_IN_PROGRESS" if busy else "两次都返回 OK"

        def performance(c: Check) -> None:
            if not perf:
                c.status, c.detail = SKIP, "未加 --perf"
                return
            b = self.batch([self.delivery(100000 + k, 1) for k in range(perf)])
            r = self.send(b)
            self.ok(r, b)
            c.detail = f"{perf} 枚耗时 {r.elapsed:.1f} 秒"
            if r.elapsed > 10:
                c.status, c.detail = WARN, c.detail + "（建议 10 秒内）"

        cases = [
            ("C01", "连接测试：PING 返回 OK", "接口 3.1", ping_ok),
            ("C02", "连接测试：PING 的 orgCode 不符时 NG", "接口 3.1；说明 3.2 步骤 1", ping_org),
            ("C03", "认证：凭证错误返回 401 / 403", "接口 2.1", auth_bad),
            ("C04", "认证（HMAC）：时间戳偏差超过 5 分钟拒绝", "接口 2.1", hmac_skew),
            ("C05", "交付批次首次送达：OK，逐枚新增", "说明 3.2 逐枚处理；接口 3.1", first),
            ("C06", "同一批原样再次送达：OK", "说明 3.2 步骤 3", replay),
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
            ("C18", "缺必填字段：NG（HTTP 200，不是 4xx），不落任何数据", "说明 3.2 步骤 2；接口 3.1", missing),
            ("C19", "请求体无法解析：400", "接口 3.1", bad_body),
            ("C20", "同一批同时送达两次：只处理一次，两边都 OK", "说明 2、3.2 步骤 5", concurrent),
            ("C21", "5000 枚一批的处理时间", "说明 3.2 步骤 5", performance),
            ("C22", "批次头 pi 不是本组织已有的 PI：NG；修正后同一批次号重送能处理", "说明 3.2 步骤 2、5", pi_bad),
            ("C23", "交付批次缺批次头 pi、明细 pi 与批次头不一致：NG", "说明 3.2 步骤 2", pi_head),
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
    p.add_argument("--auth", choices=("TOKEN", "HMAC"), default="TOKEN")
    p.add_argument("--secret", required=True, help="TOKEN 为 Bearer 令牌，HMAC 为签名密钥")
    p.add_argument("--timeout", type=float, default=60, help="等待应答秒数（与本系统推送超时一致，默认 60）")
    p.add_argument("--perf", type=int, nargs="?", const=5000, default=0, help="测一批的处理时间，默认 5000 枚")
    p.add_argument("--report", help="把报告写成 Markdown 文件")
    p.add_argument("--insecure", action="store_true", help="不校验 HTTPS 证书（测试环境自签证书）")
    p.add_argument("--gzip", action="store_true", help="请求体 gzip 压缩（与「生产组织」页开启「gzip 压缩」时相同）")
    a = p.parse_args(argv)
    r = Runner(a.url, a.org, a.auth, a.secret, a.timeout, a.insecure, gzip=a.gzip, pi=a.pi)
    print(f"运行号 {r.run}，测试 SN 前缀 {r.prefix}；只能对 MES 测试环境运行\n")
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
