"""MES 接口契约测试：按《MES 对接说明》逐项检查 MES 方「接收推送」接口（①）的实现，输出通过 / 失败报告。

MES 方接口一提供就能测，不需要本系统：脚本自己造批次（PING、交付、转出），按对接说明的方式发送（gzip、Bearer 或
HMAC 签名、X-Sn-Batch-No 等请求头），逐项核对应答——校验与整批拒收、按批次号去重、rev 比对四种结果、转出、并发送达、
连接测试。只依赖 Python 3.11+ 和 httpx，可以直接交给 MES 方自测。

    uv run python -m devtools.mes_conformance --url https://mes-test.example.com/sn/receive --org 110 \\
        --auth TOKEN --secret <令牌> --report mes-110-report.md
    # 加 --perf 测 5000 枚一批的处理时间（建议 10 秒内，超过 60 秒本系统会超时）

**只能对 MES 测试环境运行**：会写入一批测试 SN（前缀 CT + 本次运行号），其中一枚会因「冲突」被挂起。
每次运行用新的运行号，SN、PI、单据、批次号都不会与真实数据或上次运行重复。
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import hmac
import json
import random
import string
import sys
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx

SCHEMA = "1.0"
RESULTS = ("ACCEPTED", "DUPLICATE", "CONFLICT", "STALE")
PASS, FAIL, SKIP, WARN = "PASS", "FAIL", "SKIP", "WARN"


def canonical(obj: Any) -> bytes:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def digest(items: list[dict]) -> str:
    return "sha256:" + hashlib.sha256(canonical(items)).hexdigest()


def utc_iso(t: datetime | None = None) -> str:
    return (t or datetime.now(UTC)).strftime("%Y-%m-%dT%H:%M:%SZ")


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
        log: Callable[[str], None] = print,
    ) -> None:
        self.url, self.org, self.auth, self.secret, self.timeout = url, org, auth.upper(), secret, timeout
        self.http = httpx.Client(trust_env=False, follow_redirects=False, timeout=timeout, verify=not insecure)
        self.run = run_id or "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
        self.prefix = f"CT{self.run}"
        self.pi = f"CTPI{self.run}"
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

    def delivery(self, i: int, rev: int, push_type: str = "NEW", pi: str | None = None, **extra: Any) -> dict:
        return {
            "pushType": push_type,
            "sn": self.sn(i),
            "rev": rev,
            "serial": f"{i:06d}",
            "serialDec": str(i),
            "serialPi": self.pi,
            "pi": pi or self.pi,
            "billNo": self.bill,
            "billSeq": 1 if rev < 3 else 2,
            "materialCode": "CT-M1",
            "length": "100",
            "customerCode": "CT-C1",
            "transferKind": None,
            "transferDocNo": None,
            "fromOrgCode": None,
            **extra,
        }

    def transfer_out(self, i: int, rev: int, to_org: str = "CT-TO") -> dict:
        return {
            "pushType": "TRANSFER_OUT",
            "sn": self.sn(i),
            "rev": rev,
            "serial": f"{i:06d}",
            "serialDec": str(i),
            "serialPi": self.pi,
            "from": {
                "pi": self.pi,
                "billNo": self.bill,
                "billSeq": 1,
                "materialCode": "CT-M1",
                "customerCode": "CT-C1",
            },
            "toOrgCode": to_org,
            "transferKind": "ORG_TRANSFER",
            "transferDocNo": f"CT-TO-{self.run}",
        }

    def batch(self, items: list[dict], kind: str = "DELIVERY", no: str | None = None, **head: Any) -> dict:
        pi = head.pop("pi", self.pi)
        obj = {
            "schemaVersion": SCHEMA,
            "batchNo": no or self.batch_no(),
            "batchType": kind,
            "orgCode": self.org,
            "pi": pi,
            "count": len(items),
            "createdAt": utc_iso(),
            "itemsDigest": digest(items),
            "items": items,
        }
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
        """按对接说明 ① 发送：请求体 gzip，认证按组织配置（Bearer 或 HMAC）。"""
        raw = canonical(obj) if raw is None else raw
        no = (obj or {}).get("batchNo") or "CT-RAW"
        h = {
            "Content-Type": "application/json; charset=utf-8",
            "Content-Encoding": "gzip",
            "Accept": "application/json",
            "Accept-Encoding": "gzip",
            "X-Sn-Batch-No": no,
            "X-Sn-Attempt": str(attempt),
            "X-Request-Id": str(uuid.uuid4()),
        }
        key = self.secret if secret is None else secret
        if self.auth == "HMAC":
            t = ts or utc_iso()
            msg = f"{t}\n{no}\n{hashlib.sha256(raw).hexdigest()}"
            h["X-Sn-Timestamp"] = t
            h["X-Sn-Signature"] = hmac.new(key.encode(), msg.encode(), hashlib.sha256).hexdigest()
        else:
            h["Authorization"] = f"Bearer {key}"
        start = time.monotonic()
        try:
            r = self.http.post(self.url, content=gzip.compress(raw, mtime=0), headers=h)
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

    def processed(self, r: Reply, b: dict, expect: dict[str, str] | None = None) -> dict:
        """HTTP 200 + PROCESSED，回执与批次逐项一致（与本系统核对回执的规则相同）。expect：sn -> 期望结果。"""
        self.need(r.status == 200, f"期望 HTTP 200 + 回执，实际 {r}")
        body = r.body
        self.need(isinstance(body, dict), f"应答不是 JSON 对象：{r}")
        self.need(body.get("outcome") == "PROCESSED", f"期望 outcome = PROCESSED，实际 {r}")
        self.need(body.get("schemaVersion") == SCHEMA, f"回执缺 schemaVersion 或不是 {SCHEMA}")
        self.need(body.get("batchNo") == b["batchNo"], f"回执 batchNo = {body.get('batchNo')}，应为 {b['batchNo']}")
        res = body.get("results")
        self.need(isinstance(res, list), "回执缺 results")
        self.need(len(res) == b["count"], f"results 有 {len(res)} 条，应等于 count = {b['count']}")
        revs = {x["sn"]: x["rev"] for x in b["items"]}
        seen: set[str] = set()
        counts = dict.fromkeys(RESULTS, 0)
        for x in res:
            sn = x.get("sn")
            self.need(sn in revs, f"回执含不属于本批的 SN {sn}")
            self.need(sn not in seen, f"回执中 SN {sn} 出现不止一次")
            seen.add(sn)
            self.need(x.get("rev") == revs[sn], f"{sn} 回执 rev = {x.get('rev')}，应原样带回 {revs[sn]}")
            self.need(x.get("result") in RESULTS, f"{sn} 的 result = {x.get('result')}，应为 {'/'.join(RESULTS)}")
            counts[x["result"]] += 1
            if x["result"] in ("CONFLICT", "STALE"):
                self.need(
                    isinstance(x.get("existingRev"), int) and bool(x.get("existingBatchNo")),
                    f"{sn} 为 {x['result']}，须带回 existingRev、existingBatchNo",
                )
            if expect and sn in expect:
                self.need(x["result"] == expect[sn], f"{sn} 期望 {expect[sn]}，实际 {x['result']}（{x}）")
        s = body.get("summary") or {}
        want = {"total": b["count"], **{k.lower(): v for k, v in counts.items()}}
        self.need(all(s.get(k) == v for k, v in want.items()), f"summary = {s}，与明细不符（应为 {want}）")
        return body

    def rejected(self, r: Reply, b: dict, why: str) -> dict:
        self.need(
            r.status == 200,
            f"{why}：期望 HTTP 200 + 整批拒收回执（outcome = REJECTED），实际 {r}"
            + ("（4xx 只用于认证、地址、报文过大、无法解析）" if r.status and 400 <= r.status < 500 else ""),
        )
        body = r.body if isinstance(r.body, dict) else {}
        self.need(body.get("outcome") == "REJECTED", f"{why}：期望 outcome = REJECTED，实际 {r}")
        self.need(bool(body.get("rejectCode")), "拒收回执须带 rejectCode")
        self.need(body.get("batchNo") == b["batchNo"], f"拒收回执 batchNo = {body.get('batchNo')}，应为 {b['batchNo']}")
        return body

    def result_of(self, body: dict, i: int) -> dict:
        return next(x for x in body["results"] if x["sn"] == self.sn(i))

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

        def ping_ok(c: Check) -> None:
            no = "PING-" + self.batch_no()
            b = {
                "schemaVersion": SCHEMA,
                "batchNo": no,
                "batchType": "PING",
                "orgCode": self.org,
                "pi": None,
                "count": 0,
                "createdAt": utc_iso(),
                "itemsDigest": digest([]),
                "items": [],
            }
            r = self.send(b)
            body = self.processed(r, b)
            self.need(body["results"] == [], "PING 的 results 应为空")
            c.detail = f"耗时 {r.elapsed:.2f} 秒"

        def ping_org(c: Check) -> None:
            no = "PING-" + self.batch_no()
            b = {
                "schemaVersion": SCHEMA,
                "batchNo": no,
                "batchType": "PING",
                "orgCode": self.org + "X",
                "pi": None,
                "count": 0,
                "createdAt": utc_iso(),
                "itemsDigest": digest([]),
                "items": [],
            }
            self.rejected(self.send(b), b, "PING 的 orgCode 不是本组织")

        def auth_bad(c: Check) -> None:
            b = self.batch([self.delivery(90, 1)])
            r = self.send(b, secret=self.secret + "-wrong")
            self.need(r.status in (401, 403), f"凭证错误应返回 401 或 403，实际 {r}")

        def hmac_skew(c: Check) -> None:
            if self.auth != "HMAC":
                c.status, c.detail = SKIP, "认证方式为 TOKEN，不适用"
                return
            b = self.batch([self.delivery(91, 1)])
            r = self.send(b, ts=utc_iso(datetime.now(UTC) - timedelta(minutes=10)))
            self.need(r.status in (401, 403), f"时间戳偏差 10 分钟应拒绝（401 或 403），实际 {r}")

        def first(c: Check) -> None:
            b = self.batch([self.delivery(1, 1), self.delivery(2, 1), self.delivery(3, 1)])
            r = self.send(b)
            self.processed(r, b, {self.sn(1): "ACCEPTED", self.sn(2): "ACCEPTED", self.sn(3): "ACCEPTED"})
            st["b1"], st["b1_body"] = b, r.body
            c.detail = f"{b['batchNo']}，耗时 {r.elapsed:.2f} 秒"

        def replay(c: Check) -> None:
            self.need("b1" in st, "依赖 C05 未通过")
            b = st["b1"]
            r = self.send(b, attempt=2)
            body = self.processed(r, b)
            self.need(body.get("replayed") is True, "再次送达同一批应返回存下的回执，且 replayed = true")
            self.need(body["results"] == st["b1_body"]["results"], "再次送达返回的 results 与首次不同")
            if body.get("receivedAt") != st["b1_body"].get("receivedAt"):
                c.status, c.detail = WARN, "receivedAt 与首次不同（应为首次处理的时间）"

        def mismatch(c: Check) -> None:
            self.need("b1" in st, "依赖 C05 未通过")
            items = [self.delivery(1, 1), self.delivery(2, 1)]
            b = self.batch(items, no=st["b1"]["batchNo"])
            r = self.send(b)
            self.need(r.status == 409, f"同一批次号内容不同应返回 409，实际 {r}")
            code = r.body.get("code") if isinstance(r.body, dict) else None
            self.need(code == "BATCH_CONTENT_MISMATCH", f"code 应为 BATCH_CONTENT_MISMATCH，实际 {code}")

        def reassign(c: Check) -> None:
            b = self.batch([self.delivery(1, 2, "REASSIGN")])
            self.processed(self.send(b), b, {self.sn(1): "ACCEPTED"})
            st["b_rev2"] = b["batchNo"]

        def conflict(c: Check) -> None:
            self.need("b_rev2" in st, "依赖 C08 未通过")
            b = self.batch([self.delivery(1, 2, "REASSIGN")])
            body = self.processed(self.send(b), b, {self.sn(1): "CONFLICT"})
            x = self.result_of(body, 1)
            self.need(
                x["existingRev"] == 2 and x["existingBatchNo"] == st["b_rev2"],
                f"应带回 existingRev = 2、existingBatchNo = {st['b_rev2']}，实际 {x}",
            )
            c.notes.append(f"请在 MES 侧确认 {self.sn(1)} 已挂起、禁止打标")

        def stale(c: Check) -> None:
            self.need("b_rev2" in st, "依赖 C08 未通过")
            b = self.batch([self.delivery(1, 1, "REASSIGN")])
            body = self.processed(self.send(b), b, {self.sn(1): "STALE"})
            x = self.result_of(body, 1)
            self.need(
                x["existingRev"] == 2 and x["existingBatchNo"] == st["b_rev2"],
                f"应带回 existingRev = 2、existingBatchNo = {st['b_rev2']}，实际 {x}",
            )

        def resolve(c: Check) -> None:
            b = self.batch([self.delivery(1, 3, "REASSIGN")])
            self.processed(self.send(b), b, {self.sn(1): "ACCEPTED"})
            c.notes.append(f"请在 MES 侧确认 {self.sn(1)} 已解除挂起，挂靠为单据序号 2")

        def mixed(c: Check) -> None:
            self.need("b_rev2" in st, "依赖 C08 未通过")
            b = self.batch([self.delivery(4, 1), self.delivery(2, 1, "REASSIGN"), self.delivery(3, 2, "REASSIGN")])
            self.processed(self.send(b), b, {self.sn(4): "ACCEPTED", self.sn(2): "CONFLICT", self.sn(3): "ACCEPTED"})
            c.notes.append(f"{self.sn(2)} 因此挂起，属预期")

        def transfer_out(c: Check) -> None:
            b = self.batch([self.transfer_out(5, 2)], "TRANSFER_OUT")
            # 先按交付入库 rev 1，再转出 rev 2
            pre = self.batch([self.delivery(5, 1)])
            self.processed(self.send(pre), pre, {self.sn(5): "ACCEPTED"})
            self.processed(self.send(b), b, {self.sn(5): "ACCEPTED"})
            c.notes.append(f"请在 MES 侧确认 {self.sn(5)} 已标「已转出」、toOrgCode = CT-TO，挂靠按 from 写入")

        def transfer_multi(c: Check) -> None:
            b = self.batch([self.transfer_out(6, 1)], "TRANSFER_OUT", pi=None)
            self.processed(self.send(b), b, {self.sn(6): "ACCEPTED"})

        def transfer_back(c: Check) -> None:
            b = self.batch(
                [
                    self.delivery(
                        5,
                        3,
                        "REASSIGN",
                        transferKind="ORG_TRANSFER",
                        transferDocNo=f"CT-BACK-{self.run}",
                        fromOrgCode="CT-TO",
                    )
                ]
            )
            self.processed(self.send(b), b, {self.sn(5): "ACCEPTED"})
            c.notes.append(f"请在 MES 侧确认 {self.sn(5)} 已清除「已转出」")

        def unknown_fields(c: Check) -> None:
            b = self.batch([self.delivery(7, 1, futureField="x")], futureHead={"a": 1})
            self.processed(self.send(b), b, {self.sn(7): "ACCEPTED"})

        def reject_then_fix(c: Check, why: str, bad: dict, good: dict) -> None:
            self.rejected(self.send(bad), bad, why)
            r = self.send(good)
            self.need(
                not (isinstance(r.body, dict) and r.body.get("outcome") == "REJECTED"),
                "修正后用同一批次号重送仍返回拒收：整批拒收时不能登记批次号",
            )
            self.processed(r, good)

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
            self.rejected(self.send(b), b, "批内 SN 重复")

        def pi_bad(c: Check) -> None:
            b = self.batch([self.delivery(13, 1), self.delivery(14, 1, pi=self.pi + "X")])
            self.rejected(self.send(b), b, "交付项 pi 不等于批次头 pi")

        def delivery_has_out(c: Check) -> None:
            b = self.batch([self.delivery(15, 1), self.transfer_out(16, 1)])
            self.rejected(self.send(b), b, "交付批次含转出项")

        def out_has_delivery(c: Check) -> None:
            b = self.batch([self.transfer_out(17, 1), self.delivery(18, 1)], "TRANSFER_OUT")
            self.rejected(self.send(b), b, "转出批次含交付项")

        def missing(c: Check) -> None:
            item = self.delivery(19, 1)
            del item["billNo"]
            b = self.batch([item])
            self.rejected(self.send(b), b, "交付项缺必填字段 billNo")

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
            bodies = [self.processed(r, b) for r in out]
            self.need(bodies[0]["results"] == bodies[1]["results"], "两次送达的回执 results 不同：同一批只能处理一次")
            self.need(
                all(x["result"] == "ACCEPTED" for x in bodies[0]["results"]),
                "首次处理应全部 ACCEPTED（同一批被处理了两次？）",
            )
            c.detail = "其中一次返回 409 BATCH_IN_PROGRESS" if busy else "两次都返回同一份回执"

        def performance(c: Check) -> None:
            if not perf:
                c.status, c.detail = SKIP, "未加 --perf"
                return
            b = self.batch([self.delivery(100000 + k, 1) for k in range(perf)])
            r = self.send(b)
            self.processed(r, b)
            c.detail = f"{perf} 枚耗时 {r.elapsed:.1f} 秒"
            if r.elapsed > 10:
                c.status, c.detail = WARN, c.detail + "（建议 10 秒内）"

        cases = [
            ("C01", "连接测试：PING 返回 PROCESSED、summary 全为 0", "5.1 PING；9.2-13", ping_ok),
            ("C02", "连接测试：PING 的 orgCode 不符时整批拒收", "5.1 PING；3 校验", ping_org),
            ("C03", "认证：凭证错误返回 401 / 403", "5.1 请求头", auth_bad),
            ("C04", "认证（HMAC）：时间戳偏差超过 5 分钟拒绝", "5.1 请求头", hmac_skew),
            ("C05", "交付批次首次送达：逐枚 ACCEPTED，回执条数、rev、summary 一致", "3 比 rev；5.1 回执", first),
            ("C06", "同一批原样再次送达：返回存下的回执，replayed = true", "3 去重；9.2-3、10", replay),
            ("C07", "同一批次号内容不同：409 BATCH_CONTENT_MISMATCH", "3 去重", mismatch),
            ("C08", "改挂 REASSIGN：rev 更大时覆盖，ACCEPTED", "3 比 rev；9.2-6", reassign),
            (
                "C09",
                "rev 相同、来自别的批次：CONFLICT，带回 existingRev / existingBatchNo",
                "3 比 rev；9.2-12",
                conflict,
            ),
            ("C10", "rev 更小：STALE，带回 existingRev / existingBatchNo", "3 比 rev；9.2-12", stale),
            ("C11", "以本系统为准重推（rev 更大）：ACCEPTED，解除冲突挂起", "6 异常处理；9.2-16", resolve),
            ("C12", "同一批里收下、冲突混合：逐枚各自回执", "3 比 rev", mixed),
            ("C13", "转出批次 TRANSFER_OUT：rev 更大时 ACCEPTED", "3 比 rev；5.1 转出批次；9.2-6", transfer_out),
            ("C14", "转出批次头 pi = null（涉及多张 PI）照常处理", "5.1 转出批次", transfer_multi),
            ("C15", "转回来源组织：改挂 rev 更大，ACCEPTED（清除已转出）", "3 比 rev；9.2-6", transfer_back),
            ("C16", "不认识的字段忽略", "5.0 通用规则", unknown_fields),
            ("C17", "count 与 items 不符：整批拒收；修正后同一批次号重送能处理", "3 校验；9.2-5", count_bad),
            ("C18", "orgCode 不是本组织：整批拒收；修正后同一批次号重送能处理", "3 校验", org_bad),
            ("C19", "批内 SN 重复：整批拒收", "3 校验", dup_sn),
            ("C20", "交付项 pi 与批次头不同：整批拒收", "3 校验", pi_bad),
            ("C21", "交付批次含转出项：整批拒收", "3 校验", delivery_has_out),
            ("C22", "转出批次含交付项：整批拒收", "3 校验", out_has_delivery),
            ("C23", "缺必填字段：整批拒收（HTTP 200 + REJECTED，不是 4xx）", "3 校验；5.1 回执", missing),
            ("C24", "请求体无法解析：400", "5.1", bad_body),
            ("C25", "同一批同时送达两次：只处理一次，两边回执相同", "2 接口与调用；9.2-3", concurrent),
            ("C26", "5000 枚一批的处理时间", "3 处理要求", performance),
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
            f"- 时间：{utc_iso()}（UTC）",
            f"- 接口地址：{self.url}",
            f"- 生产组织：{self.org}　认证方式：{self.auth}",
            f"- 运行号：{self.run}（测试 SN 前缀 `{self.prefix}`，PI `{self.pi}`，单据 `{self.bill}`）",
            f"- 结果：通过 {n[PASS]}，失败 {n[FAIL]}，提醒 {n[WARN]}，跳过 {n[SKIP]}",
            "",
            "依据《SN 防重管控 · MES 对接说明（精简版）》：「依据」列为该文档的章节号，9.2-x 为 PRD 9.2 场景。",
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
                "接口应答看不到 MES 的本侧数据，以下几项请在 MES 库或页面上核对：",
                "",
            ]
            lines += notes
        return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m devtools.mes_conformance", description="MES 接收推送接口（①）契约测试")
    p.add_argument("--url", required=True, help="MES 接收推送的地址（与「生产组织」页填写的相同）")
    p.add_argument("--org", required=True, help="生产组织编码（批次的 orgCode）")
    p.add_argument("--auth", choices=("TOKEN", "HMAC"), default="TOKEN")
    p.add_argument("--secret", required=True, help="TOKEN 为 Bearer 令牌，HMAC 为签名密钥")
    p.add_argument("--timeout", type=float, default=60, help="等待应答秒数（与本系统推送超时一致，默认 60）")
    p.add_argument("--perf", type=int, nargs="?", const=5000, default=0, help="测一批的处理时间，默认 5000 枚")
    p.add_argument("--report", help="把报告写成 Markdown 文件")
    p.add_argument("--insecure", action="store_true", help="不校验 HTTPS 证书（测试环境自签证书）")
    a = p.parse_args(argv)
    r = Runner(a.url, a.org, a.auth, a.secret, a.timeout, a.insecure)
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
