"""联调工具：模拟 MES（devtools/mes_mock.py）、MES 接口契约测试（devtools/mes_conformance.py）。

契约测试先对模拟 MES 跑一遍（应全部通过），再对故意写错的 MES 跑（应报出失败），证明两边都按对接说明实现：
① 应答已收到（code = OK），处理落库后调 ③ 回执（契约测试自带 ③ 接收地址，模拟 MES 的回执提交器指向它）；
取批模拟器对真实应用跑通 ② → process → ③。
"""

from __future__ import annotations

import threading

import httpx
import pytest
from conftest import MES, emp, m, ok
from test_delivery import setup_pi

from devtools.mes_conformance import FAIL, PASS, SKIP, ReceiptInbox, Runner
from devtools.mes_mock import MesSim, MockMes, Puller, ReceiptSender


def _quiet(_: str) -> None:
    pass


_INBOXES: list[ReceiptInbox] = []


def _runner(mock: MockMes, auth: str = "TOKEN", gz: bool = False, wait: float = 5) -> Runner:
    """契约测试对模拟 MES：模拟 MES 处理完推送来的批次后，用 MES 接口账户把 ③ 回执提交到契约测试的接收地址。"""
    secret = "" if auth == "NONE" else "s3cret"
    mock.secrets["110"] = secret
    mock.pis["110"] = {"CTPI-110"}
    inbox = ReceiptInbox().start()
    _INBOXES.append(inbox)
    http = httpx.Client(base_url=inbox.base_url, trust_env=False, timeout=10)
    mock.sender = ReceiptSender(mock, http, {"110": "MES110:key"}, log=_quiet).start()
    return Runner(
        mock.url("110"),
        "110",
        auth,
        secret,
        timeout=10,
        log=_quiet,
        gzip=gz,
        pi="CTPI-110",
        inbox=inbox,
        receipt_wait=wait,
    )


def _start(cls: type[MockMes]) -> MockMes:
    x = cls().start()
    x.sender = None
    return x


def _stop(x: MockMes) -> None:
    if x.sender is not None:
        x.sender.stop()
    x.stop()
    while _INBOXES:
        _INBOXES.pop().stop()


@pytest.fixture
def mock():
    x = _start(MockMes)
    yield x
    _stop(x)


@pytest.mark.parametrize(("auth", "gz"), [("TOKEN", False), ("HMAC", False), ("HMAC", True), ("NONE", False)])
def test_conformance_all_pass_against_mock(mock, auth, gz):
    r = _runner(mock, auth, gz)
    checks = r.run_all(perf=500)
    bad = [(c.id, c.status, c.detail) for c in checks if c.status not in (PASS, SKIP)]
    assert bad == []
    assert {c.id for c in checks if c.status == SKIP} == {"TOKEN": {"C04"}, "HMAC": set(), "NONE": {"C03", "C04"}}[auth]
    # 用例对 MES 本侧数据的效果
    store = mock.store["110"]
    assert (store[r.sn(1)]["rev"], store[r.sn(1)]["billSeq"]) == (3, 2), "rev 更大覆盖；同 rev 覆盖不变；更小跳过"
    assert r.sn(4) in store, "同批里跳过一枚不影响其余"
    assert store[r.sn(2)]["transferredOut"] is False and store[r.sn(2)]["rev"] == 3, "转回后清除已转走"
    assert store[r.sn(5)] == {
        "rev": 2,
        "lastBatchNo": store[r.sn(5)]["lastBatchNo"],
        "transferredOut": True,
        "toOrgCode": "CT-TO",
    }, "本地没有时建已转走记录，迟到的旧交付跳过"
    assert r.sn(12) not in store and r.sn(19) not in store, "整批退回不写数据"
    assert store[r.sn(71)]["pi"] == "CTPI-110" and not {r.sn(72), r.sn(73), r.sn(74)} & set(store), "PI 不符整批退回"
    report = r.report()
    assert "失败 0" in report and "需在 MES 侧人工核对" in report


class _RegistersRejects(MockMes):
    """错误实现：整批退回也登记批次号，修好后重送仍返回旧的 NG。"""

    def process(self, org, batch, source="PULL"):
        no = batch.get("batchNo")
        old = self.batches.get(org, {}).get(no)
        if old is not None and old["receipt"].get("code") == "NG":
            return old["receipt"]
        r = super().process(org, batch, source)
        if r.get("code") == "NG":
            self.batches.setdefault(org, {})[no] = {"digest": batch.get("itemsDigest"), "receipt": r}
        return r


class _IgnoresDigest(MockMes):
    """错误实现：同一批次号再来时不比对 itemsDigest，一律返回上次回执。"""

    def process(self, org, batch, source="PULL"):
        old = self.batches.get(org, {}).get(batch.get("batchNo"))
        if old is not None:
            return old["receipt"]
        return super().process(org, batch, source)


class _RejectsWith400(MockMes):
    """错误实现：校验不通过直接返回 400，而不是应答已收到、再用 ③ 回执 NG。"""

    def handle(self, org, batch, source="PUSH", emit=True):
        why = self._problem(org, batch) if batch.get("batchType") != "PING" else None
        st, body = (None, {"code": "NG", "message": why}) if why else super().handle(org, batch, source, emit)
        if body.get("code") == "NG":
            return 400, {"schemaVersion": "1.0", "code": "BAD_REQUEST", "message": body.get("message")}
        return st, body


class _NoReceipt(MockMes):
    """错误实现：处理落库、应答已收到，但从不调 ③ 回执（联调时的现象）。"""

    def emit(self, org, receipt):
        pass


class _ReceiptInResponse(MockMes):
    """错误实现（旧约定）：在 ① 应答里直接给处理结果（OK / NG），不调 ③。"""

    def handle(self, org, batch, source="PUSH", emit=True):
        if batch.get("batchType") == "PING":
            return super().handle(org, batch, source, emit)
        r = self.process(org, batch, source)
        return (r.pop("__status"), r) if "__status" in r else (200, r)


#: 需要 ③ 回执的检查项（C01～C04、C19 只看 ① 应答，C07 依赖 C05；C21 未加 --perf 跳过）
_NEED_RECEIPT = {f"C{i:02d}" for i in range(5, 26)} - {"C19", "C21"}


@pytest.mark.parametrize(
    "cls, failed",
    [
        (_RegistersRejects, {"C15", "C22", "C24"}),
        (_IgnoresDigest, {"C07"}),
        (_RejectsWith400, {"C02", "C14", "C15", "C16", "C17", "C18", "C22", "C23", "C24"}),
        (_NoReceipt, _NEED_RECEIPT),
        (_ReceiptInResponse, _NEED_RECEIPT),
    ],
)
def test_conformance_catches_broken_mes(cls, failed):
    broken = _start(cls)
    try:
        checks = _runner(broken, wait=0.5).run_all()
    finally:
        _stop(broken)
    assert {c.id for c in checks if c.status == FAIL} == failed


def test_fault_injection_via_control_api(mock):
    r = _runner(mock)
    ctl = mock.url("110").rsplit("/mes/", 1)[0] + "/_mock"
    with httpx.Client(trust_env=False) as http:
        assert (
            http.post(ctl + "/fault", json={"org": "110", "mode": "http", "status": 503, "times": 1}).status_code == 200
        )
        b = r.batch([r.delivery(1, 1)])
        first = r.send(b)
        assert first.status == 503 and first.body["code"] == "HTTP_503"
        assert r.ok(r.send(b, attempt=2), b)["code"] == "OK", "times = 1：只生效一次"
        assert http.get(ctl + "/state").json()["faults"] == {}
        # 先处理、提交 ③ 回执再断开：应答丢失，回执照样到；重送应答已收到，并重新提交同一份回执
        ok_fault = http.post(ctl + "/fault", json={"org": "110", "mode": "drop_after"})
        assert ok_fault.status_code == 200
        b2 = r.batch([r.delivery(2, 1)])
        assert r.send(b2).status is None
        first = r.callback(b2)
        assert first["code"] == "OK", "MES 已处理并回执"
        assert http.delete(ctl + "/fault", params={"org": "110"}).json()["faults"] == {}
        assert r.ok(r.send(b2, attempt=2), b2) == first, "重送拿到首次的回执"
        assert len(mock.batches["110"]) == 2, "不重复处理"
        sent = http.get(ctl + "/state").json()["receiptsSent"]
        assert [(x["batchNo"], x["status"]) for x in sent][-2:] == [(b2["batchNo"], 200)] * 2
        got = http.get(ctl + "/received", params={"limit": 10}).json()
        assert [x["attempt"] for x in got] == ["1", "2", "1", "2"] and got[0]["firstItem"]["sn"] == r.sn(1)
        assert http.post(ctl + "/fault", json={"org": "110", "mode": "nope"}).status_code == 400
        # 预置本地更新的版本 → 送达的旧版本跳过，整批仍 OK
        assert http.post(ctl + "/seed", json={"org": "110", "sn": r.sn(3), "rev": 4}).json()["lastBatchNo"] == "SEED"
        b3 = r.batch([r.delivery(3, 1)])
        r.ok(r.send(b3), b3)
        assert mock.store["110"][r.sn(3)] == {"rev": 4, "lastBatchNo": "SEED", "transferredOut": False}
        assert http.post(ctl + "/seed", json={"org": "110"}).status_code == 400
        assert http.post(ctl + "/reset").json() == {"ok": True}
    assert mock.store == {} and mock.received == []


def test_mock_checks_batch_header_and_auth(mock):
    r = _runner(mock)
    b = r.batch([r.delivery(1, 1)])
    assert r.send(b, secret="wrong").status == 401
    mock.secrets.pop("110")
    assert r.send(b).status == 401, "未配置凭证的组织一律 401"
    mock.default_secret = "s3cret"
    assert r.send(b).status == 200, "default_secret 适用于所有组织"
    dup = MesSim().process("110", {**b, "items": b["items"] * 2, "count": 2})
    assert dup["code"] == "NG" and "重复" in dup["message"]


# ---------------------------------------------------------------------- 取批模拟器对真实应用


def _account(w, org: str) -> tuple[str, str]:
    e = emp("M")
    r = ok(w.api.post("/api/users", m(emp_no=e, name="MES", role="mes", factory_code=org), w.admin))
    return e, r["secret"]


def test_puller_pulls_until_empty(w, sql, app_client):
    org, pi, bill = setup_pi(w, qty=12, batch_size=5)
    w.allocate(pi)
    e, key = _account(w, org)
    done = Puller(MES, org, app_client, e, key, log=_quiet).run_once()
    assert [d["status"] for d in done] == [200] * 3
    assert [d["code"] for d in done] == ["OK"] * 3
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_batch WHERE pi_no = %s AND accepted_qty = qty", pi) == 3
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED' AND delivery_mode = 'PULL'", pi)
        == 12
    )
    assert len(MES.store[org]) == 12 and {b["source"] for b in MES.batches[org].values()} == {"PULL"}
    assert Puller(MES, org, app_client, e, key, log=_quiet).run_once() == [], "没有可给的批次"


def test_push_response_lost_but_receipt_confirms(w, sql):
    """9.2-1：MES 收下并处理了、推送应答丢失（本系统按断连重试），MES 的 ③ 回执照样确认批次，重试停止。"""
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    MES.set_fault(org, {"mode": "drop_after", "times": 1})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["confirm_method"]) == ("SUCCESS", "RECEIPT")
    assert len(MES.batches[org]) == 1, "重试送达同一批，MES 只处理一次"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 3


def test_push_response_lost_then_pull_gets_same_receipt(w, sql, app_client):
    """9.2-1、3：MES 收下了但推送应答丢失、③ 回执也没交上来，本系统重试用尽；
    MES 取到同一批，返回存下的回执，批次成功。"""
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    MES.set_fault(org, {"mode": "drop_after"})
    w.allocate(pi)
    w.push(receipts=False)
    MES.take_outbox()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["push_state"]) == ("UNCONFIRMED", "EXHAUSTED")
    assert MES.batches[org][b["batch_no"]]["source"] == "PUSH", "MES 首次送达时已处理"
    MES.clear_fault(org)
    e, key = _account(w, org)
    done = Puller(MES, org, app_client, e, key, log=_quiet).run_once()
    assert [d["batchNo"] for d in done] == [b["batch_no"]]
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE id = %s", b["id"]) == "SUCCESS"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 3


def test_push_and_pull_concurrently_never_overlap(w, sql, app_client):
    """9.2-2：推送建批、MES 拉取建批并发取号，同一枚号不会进入两个交付批次。"""
    org, pi, bill = setup_pi(w, qty=3000, batch_size=300)
    w.allocate(pi)
    e, key = _account(w, org)
    puller = Puller(MES, org, app_client, e, key, log=_quiet)
    t = threading.Thread(target=puller.run_once)
    t.start()
    w.push()
    t.join()
    w.push()
    puller.run_once()
    twice = sql.count(
        "SELECT COUNT(*) FROM (SELECT d.sn FROM sn_delivery_item d JOIN sn_delivery_batch b ON b.id = d.batch_id "
        "WHERE b.pi_no = %s GROUP BY d.sn HAVING COUNT(*) > 1) t",
        pi,
    )
    assert twice == 0, "同一枚号不会进入两个交付批次"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 3000
    sources = {r["source"] for r in sql.all("SELECT DISTINCT source FROM sn_delivery_batch WHERE pi_no = %s", pi)}
    assert sources <= {"PUSH", "PULL"} and sources


@pytest.mark.parametrize("gz", [False, True])
def test_push_body_plain_json_by_default_gzip_when_enabled(mock, gz):
    """① 推送：默认发原始 JSON、不带 Content-Encoding；组织开启 gzip 压缩时发 gzip 并带 Content-Encoding: gzip。"""
    from app.services import mes_client

    mock.secrets["110"] = "s3cret"
    t = mes_client.Target(mock.url("110"), mes_client.TOKEN, "s3cret", 10, gz)
    out = mes_client.send(t, "PING-GZ", mes_client.ping_body("110", "PING-GZ"), 1, ping=True)
    assert out.kind == mes_client.RECEIVED
    assert {k: out.body[k] for k in ("batchNo", "code", "message")} == {
        "batchNo": "PING-GZ",
        "code": "OK",
        "message": "已收到",
    }
    assert out.body["receivedAt"][10] == " "
    h = mock.received[-1]["headers"]
    assert h.get("content-encoding") == ("gzip" if gz else None)
    assert mock.received[-1]["batch"]["batchNo"] == "PING-GZ"
