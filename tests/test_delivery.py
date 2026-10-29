"""交付：分配后分批推送、自动重试与续推、MES 拉取与回执、人工确认与作废、交付告警（F-11、F-12、5.2.5）。"""

from __future__ import annotations

import gzip
import json
import re
from datetime import UTC, datetime, timedelta

import pytest
from conftest import MES, RECEIPT_REPLIES, Res, emp, m, ok, uid, xlsx_rows


def setup_pi(w, qty: int = 12, batch_size: int = 5, mes: bool = True, prefix: str | None = None):
    """一张 PI、一张单据、一个物料行，已生成并分配到对接 MES 的组织。返回 (org, pi, bill)。"""
    org = w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org, batch_size=batch_size)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", qty, "0100"))
    rid = w.rule(prefix or ("P" + uid("").replace("-", "")), seq_len=6)
    ok(w.bind_pi(pi, rid))
    w.generate(pi)
    return org, pi, bill


def test_allocate_pushes_in_batches_and_receipts_confirm(w, sql):
    org, pi, bill = setup_pi(w, qty=12, batch_size=5)
    pre = ok(w.api.post("/api/allocation/preview", m(pi_no=pi), w.admin))
    assert (pre["qty"], pre["pushes"]) == (12, 3), "分配 12 枚（推送 3 次）"
    w.allocate(pi)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'TO_PUSH'", pi) == 12
    # MES 应答已收到：第 1 批等 ③ 回执，第 2 批不建、不推
    w.push(receipts=False)
    first = sql.all("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert [(b["result"], b["push_state"], b["push_count"]) for b in first] == [("UNCONFIRMED", "WAIT_RECEIPT", 1)]
    assert first[0]["received_at"] is not None and first[0]["next_retry_at"] > first[0]["received_at"]
    task = sql.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, pi)
    assert (task["state"], task["current_batch"]) == ("WAITING", first[0]["batch_no"])
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 0
    st = ok(w.api.get(f"/api/allocation/context?pi={pi}", w.admin))["push"][0]
    assert (st["current_push"], st["total_batches"], st["waiting_receipt"]) == (1, 3, True), "第 1 / 3 次，等回执"
    assert MES.received[-1]["batch"]["batchNo"] == first[0]["batch_no"]
    # MES 落库后逐批调 ③ 回执：回执到了才推下一批
    w.push()
    batches = sql.all("SELECT * FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert [b["qty"] for b in batches] == [5, 5, 2]
    assert len({b["batch_no"] for b in batches}) == 3
    assert {b["result"] for b in batches} == {"SUCCESS"}
    assert {b["confirm_method"] for b in batches} == {"RECEIPT"}
    pushed = [x["batch"]["batchNo"] for x in MES.received if x["batch"]["batchType"] != "PING"]
    assert pushed == [b["batch_no"] for b in batches], "串行：一批回执确认后才发下一批"
    assert [r[2].status for r in RECEIPT_REPLIES] == [200] * 3
    st = ok(w.api.get(f"/api/allocation/context?pi={pi}", w.admin))["push"][0]
    assert (st["current_push"], st["total_batches"], st["waiting_receipt"], st["task"]["state"]) == (
        3,
        3,
        False,
        "DONE",
    )
    r = w.api.client.get(
        f"/api/delivery/batches/{batches[0]['batch_no']}/export", headers={"Authorization": f"Bearer {w.admin}"}
    )
    rows = xlsx_rows(r.content)
    got = dict(zip(rows[0], rows[1], strict=False))  # 行尾的空单元格不写出
    assert (got["组织编号"], got["生产组织名称"]) == (org, f"组织{org}"), "导出带组织编号与中文名称"
    assert (got["物料编码"], got["附件项次"], got["米数"]) == ("M1", "1", "0100"), "物料编码后带附件项次、米数"
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED' AND mes_holder = %s "
            "AND delivery_mode = 'PUSH'",
            pi,
            org,
        )
        == 12
    )
    # 回执 OK 即整批已处理：逐枚记为接收
    assert {b["accepted_qty"] for b in batches} == {5, 2}
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_delivery_item d JOIN sn_delivery_batch b ON b.id = d.batch_id "
            "WHERE b.pi_no = %s AND d.receipt_result = 'ACCEPTED'",
            pi,
        )
        == 12
    )
    # 报文：逐枚带单据编号、序号、当前 PI、流水归属 PI；十进制流水、米数按字符串
    body = MES.received[-1]["batch"]
    assert set(body) == {
        "schemaVersion",
        "batchNo",
        "batchType",
        "orgCode",
        "toOrgCode",
        "pi",
        "count",
        "createdAt",
        "itemsDigest",
        "items",
    }
    assert body["pi"] == pi, "交付批次头带 PI"
    assert body["count"] == len(body["items"]) == batches[-1]["qty"], "count 为明细条数"
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}", body["createdAt"]), "UTC+8，精确到毫秒"
    day = (datetime.now(UTC) + timedelta(hours=8)).strftime("%Y%m%d")
    assert body["batchNo"].split("-")[-2] == day and body["createdAt"][:10].replace("-", "") == day
    assert body["itemsDigest"].startswith("sha256:")
    item = body["items"][0]
    assert set(item) == {
        "sn",
        "rev",
        "serial",
        "serialDec",
        "serialPi",
        "pi",
        "billNo",
        "billSeq",
        "attachNo",
        "materialCode",
        "length",
        "customerCode",
    }
    assert body["batchType"] == "NEW" and body["toOrgCode"] is None, "明细与转移明细同一结构，交付批次头不带转入组织"
    assert item["billNo"] == bill and item["billSeq"] == 1 and item["pi"] == pi and item["serialPi"] == pi
    assert isinstance(item["serialDec"], str) and item["length"] == "0100"
    assert item["attachNo"] == "1", "附件项次随交付明细下发"
    h = MES.received[-1]["headers"]
    # 默认发原始 JSON，不带 Content-Encoding
    assert "content-encoding" not in h
    assert json.loads(MES.received[-1]["raw"])["batchNo"] == batches[-1]["batch_no"]


def test_manual_attach_goes_to_mes(w, sql):
    """金蝶附件项次为空、生成前页面填写的值写到号上并随交付明细发给 MES，④ 查询同样返回；
    金蝶之后有值不影响已生成的号。"""
    org = w.org(uid("O")[:10])
    w.enable_mes(org)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 2, "0100", ""))
    ok(w.api.put("/api/orders/attach", m(bill_no=bill, seq=1, value="A-07"), w.admin))
    ok(w.bind_pi(pi, w.rule("P" + uid("").replace("-", ""), seq_len=6)))
    w.generate(pi)
    w.order(bill, org, "C1", pi, ("M1", 2, "0100", "K-01"))
    assert sql.scalar("SELECT attach_no FROM sn_prd_mo WHERE bill_no = %s AND seq = 1", bill) == "K-01"
    w.allocate(pi)
    w.push()
    body = MES.received[-1]["batch"]
    assert body["pi"] == pi and {x["attachNo"] for x in body["items"]} == {"A-07"}
    ta, _ = w.mes_account(org)
    got = ok(w.mes_delivered(ta, pi=pi))["items"]
    assert [x["attachNo"] for x in got] == ["A-07", "A-07"]


def test_batch_detail_segments_split_at_gaps(w, sql):
    """批次号段：不连续的流水分成多段，每段给出首尾号。"""
    org = w.org(uid("O")[:10])
    w.enable_mes(org, batch_size=50)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 3, "0100"), ("M2", 4, "0100"), ("M3", 2, "0100"))
    ok(w.bind_pi(pi, w.rule("P" + uid("").replace("-", ""), seq_len=6)))
    w.generate(pi)
    w.allocate(pi, lines=[(bill, 1), (bill, 3)])
    w.push()
    b = sql.one("SELECT batch_no FROM sn_delivery_batch WHERE pi_no = %s", pi)
    d = ok(w.api.get(f"/api/delivery/batches/{b['batch_no']}", w.admin))

    def ends(seq: int) -> tuple[str, str]:
        r = sql.one("SELECT MIN(sn) a, MAX(sn) z FROM sn_item WHERE bill_no = %s AND bill_seq = %s", bill, seq)
        return r["a"], r["z"]

    assert [(s["start_seq"], s["end_seq"], s["qty"]) for s in d["segments"]] == [(1, 3, 3), (8, 9, 2)]
    assert [(s["start_sn"], s["end_sn"]) for s in d["segments"]] == [ends(1), ends(3)]
    assert {s["seq_pi_no"] for s in d["segments"]} == {pi}
    assert d["bills"] == [bill] and d["materials"] == ["M1", "M3"]


def test_retry_then_exhaust_then_resume_with_same_batch(w, sql):
    org, pi, bill = setup_pi(w, qty=12, batch_size=5)
    calls = {"n": 0}

    def flaky(batch, headers):
        calls["n"] += 1
        # 第 2 批一直 503
        if batch["items"][0]["serialDec"] == "6":
            return 503, {"schemaVersion": "1.0", "code": "UNAVAILABLE", "message": "busy"}
        return None, None

    MES.behavior[org] = flaky
    w.allocate(pi)
    w.push()
    b = sql.all("SELECT * FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert len(b) == 2, "第 2 批重试用尽即停，第 3 批不建"
    assert b[0]["result"] == "SUCCESS"
    assert (b[1]["result"], b[1]["push_state"], b[1]["retry_count"]) == ("UNCONFIRMED", "EXHAUSTED", 3)
    assert b[1]["push_count"] == 4, "首次 + 自动重试 3 次"
    bodies = [x["raw"] for x in MES.received if x["batch"].get("batchNo") == b[1]["batch_no"]]
    assert len(set(bodies)) == 1, "同一批次每次送达请求体逐字节相同"
    attempts = [x["headers"]["x-sn-attempt"] for x in MES.received if x["batch"].get("batchNo") == b[1]["batch_no"]]
    assert attempts == ["1", "2", "3", "4"]
    # 号保持冻结：仍在该批次中，不回号池
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE delivery_batch_no = %s AND status = 'TO_PUSH'", b[1]["batch_no"])
        == 5
    )
    rem = ok(w.api.get(f"/api/delivery/remaining?factory_code={org}&key={pi}", w.admin))
    assert rem["pushes"] == 2, "剩余 = 1 个未确认批次 + 2 枚未入批折 1 次"
    MES.behavior.pop(org)
    ok(w.api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin))
    w.push()
    b = sql.all("SELECT * FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert [x["result"] for x in b] == ["SUCCESS", "SUCCESS", "SUCCESS"], "先用原批次号重推第 2 批，再推第 3 批"
    assert b[1]["repush_count"] == 1


def test_reject_marks_failed_and_resume_after_fix(w, sql):
    """MES 应答已收到，处理后 ③ 回执整批退回（NG）：批次失败、可作废，推送任务马上停止并提示原因，不自动重试。"""
    org, pi, bill = setup_pi(w, qty=4, batch_size=5)
    MES.set_fault(org, {"mode": "reject", "message": "materialCode 超长"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["voidable"], b["push_count"]) == ("FAILED", 1, 1), "整批拒收不自动重试"
    assert b["fail_code"] == "REJECTED" and b["fail_reason"] == "materialCode 超长"
    task = sql.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, pi)
    assert task["state"] == "STOPPED" and "materialCode 超长" in task["message"], "回执 NG 唤醒任务，停止并提示"
    MES.clear_fault(org)
    ok(w.api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin))
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE id = %s", b["id"])
    assert (b["result"], b["confirm_method"], b["voidable"]) == ("SUCCESS", "RECEIPT", 0)


def test_response_batch_no_mismatch_fails_batch(w, sql):
    """应答已收到但 batchNo 不是这一批：记为应答不符、不重试；不能确定 MES 有没有处理，不能作废。"""
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    MES.set_fault(org, {"mode": "bad_response"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["fail_code"], b["voidable"], b["push_count"]) == ("FAILED", "RESPONSE_MISMATCH", 0, 1)
    r = w.api.post(f"/api/delivery/batches/{b['batch_no']}/void", m(reason="x", basis="y"), w.admin)
    assert r.code == "BATCH_NOT_VOIDABLE"


def test_receipt_with_wrong_batch_no_refused_batch_keeps_waiting(w, sql):
    """③ 回执的 batchNo 写错：本系统拒收（404），原批次仍等回执。"""
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    MES.set_fault(org, {"mode": "bad_receipt"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["push_state"]) == ("UNCONFIRMED", "WAIT_RECEIPT")
    assert [(r[1]["batchNo"], r[2].status, r[2].code) for r in RECEIPT_REPLIES] == [
        (b["batch_no"] + "-X", 404, "BATCH_NOT_FOUND")
    ]


@pytest.mark.parametrize("code", ["RECEIVED", "NG", "DONE"])
def test_response_not_received_is_invalid(w, sql, code):
    """HTTP 200 但 code 不是 OK（如 RECEIVED、在应答里直接回 NG）：不当已收到，记为应答无效、不重试，任务停止。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    MES.behavior[org] = lambda batch, h: (200, {"batchNo": batch["batchNo"], "code": code, "message": "已处理"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["fail_code"], b["push_count"]) == ("FAILED", "RESPONSE_INVALID", 1)
    assert f"收到 {code}" in b["fail_reason"]
    assert sql.scalar("SELECT state FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, pi) == "STOPPED"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 0


def _expire_wait(sql, batch_no: str) -> None:
    """把等回执截止时间拨到过去（模拟等了这么久）。"""
    sql.exec("UPDATE sn_delivery_batch SET next_retry_at = NOW() - INTERVAL 1 SECOND WHERE batch_no = %s", batch_no)
    sql.exec("UPDATE sn_push_task SET run_after = NOW() - INTERVAL 1 SECOND WHERE current_batch = %s", batch_no)


def test_no_receipt_resends_same_batch_then_exhausts(w, sql):
    """MES 应答已收到但一直不调 ③：等 1、5、10 分钟，每次超时按原批次号、原内容重推（最多 3 次），
    最后一次重推后再等 10 分钟仍没有即停止；后续批次一直不推。MES 修好后继续推送即成功。"""
    org, pi, bill = setup_pi(w, qty=7, batch_size=5)
    MES.set_fault(org, {"mode": "no_receipt"})
    w.allocate(pi)
    w.push()
    waits = []
    for _ in range(4):
        b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
        assert (b["result"], b["push_state"]) == ("UNCONFIRMED", "WAIT_RECEIPT")
        waits.append(round((b["next_retry_at"] - b["received_at"]).total_seconds() / 60))
        assert sql.scalar("SELECT state FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, pi) == (
            "WAITING"
        )
        _expire_wait(sql, b["batch_no"])
        w.push()
    assert waits == [1, 5, 10, 10]
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["push_state"], b["receipt_timeouts"], b["push_count"]) == (
        "UNCONFIRMED",
        "EXHAUSTED",
        3,
        4,
    ), "首次 + 超时重推 3 次"
    assert b["fail_code"] == "RECEIPT_TIMEOUT" and "仍未收到" in b["fail_reason"]
    sent = [x for x in MES.received if x["batch"].get("batchNo") == b["batch_no"]]
    assert [x["headers"]["x-sn-attempt"] for x in sent] == ["1", "2", "3", "4"] and len({x["raw"] for x in sent}) == 1
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_batch WHERE pi_no = %s", pi) == 1, "后续批次不建、不推"
    task = sql.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, pi)
    assert task["state"] == "STOPPED" and "仍未收到" in task["message"]
    assert ok(w.api.get("/api/dashboard", w.admin))["push_interrupted"] >= 1, "首页列为推送中断"
    # MES 修好：续推用原批次号重推，MES 按批次号去重并重新回执；再推第 2 批
    MES.clear_fault(org)
    ok(w.api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin))
    w.push()
    rows = sql.all("SELECT * FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert [(x["result"], x["confirm_method"]) for x in rows] == [("SUCCESS", "RECEIPT")] * 2
    assert len(MES.batches[org]) == 2, "重推同一批 MES 只处理一次"


def test_late_receipt_after_resend_confirms_and_continues(w, sql):
    """等回执超时重推后，MES 的回执才到：照常确认，任务接着推下一批；之后再到的同一回执只存档。"""
    org, pi, bill = setup_pi(w, qty=7, batch_size=5)
    w.allocate(pi)
    w.push(receipts=False)
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    _expire_wait(sql, b["batch_no"])
    w.push(receipts=False)
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE id = %s", b["id"])
    assert (b["push_state"], b["receipt_timeouts"], b["push_count"]) == ("WAIT_RECEIPT", 1, 2)
    assert len(MES.outbox) == 2, "重推时 MES 重新提交首次的回执"
    w.push()
    rows = sql.all("SELECT * FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert [x["result"] for x in rows] == ["SUCCESS", "SUCCESS"]
    verdicts = sql.all("SELECT verdict FROM sn_delivery_receipt WHERE batch_id = %s ORDER BY id", b["id"])
    assert [v["verdict"] for v in verdicts] == ["CONFIRMED", "ARCHIVED"]


def test_manual_confirm_while_waiting_receipt_continues(w, sql):
    """等回执期间人工确认：批次成功，任务不等到截止时间，马上推下一批。"""
    org, pi, bill = setup_pi(w, qty=7, batch_size=5)
    MES.set_fault(org, {"mode": "no_receipt", "times": 1})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert b["push_state"] == "WAIT_RECEIPT"
    ok(w.api.post(f"/api/delivery/batches/{b['batch_no']}/confirm", m(reason="MES 已入库", basis="邮件"), w.admin))
    w.push()
    rows = sql.all("SELECT result, confirm_method FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert rows == [
        {"result": "SUCCESS", "confirm_method": "MANUAL"},
        {"result": "SUCCESS", "confirm_method": "RECEIPT"},
    ]


def test_same_rev_from_other_batch_overwrites_without_alert(w, sql):
    """MES 已有同 rev、来自别的批次的号（如作废后重新组批）：同 rev 挂靠相同，逐枚覆盖，不再告警。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    sn = sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi)
    MES.store.setdefault(org, {})[sn] = {"rev": 1, "lastBatchNo": "PS-OTHER", "transferredOut": False}
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["accepted_qty"]) == ("SUCCESS", 2)
    assert MES.store[org][sn]["lastBatchNo"] == b["batch_no"]
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_alert WHERE sn = %s", sn) == 0


def test_pull_returns_same_unconfirmed_batch_until_receipt(w, sql):
    org, pi, bill = setup_pi(w, qty=7, batch_size=5)
    MES.behavior[org] = lambda batch, h: ("drop", None)
    w.allocate(pi)
    token, _ = w.mes_account(org)
    # 推送一直断连：批次未确认（重试用尽）；MES 来取拿到同一批
    w.push()
    pushed = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert pushed["push_state"] == "EXHAUSTED"
    r1 = ok(w.mes_next(token))
    assert set(r1.body) == {"schemaVersion", "code", "message", "batchNo", "timestamp", "batch", "remaining", "paused"}
    assert r1["batchNo"] == pushed["batch_no"] and r1["timestamp"][10] == " "
    assert r1["batch"]["batchNo"] == pushed["batch_no"] and r1["remaining"] == 2
    r2 = ok(w.mes_next(token))
    assert r2["batch"]["batchNo"] == pushed["batch_no"], "没回执前再取仍是同一批"
    receipt = MES.process(org, r1["batch"])
    rc = ok(w.mes_receipt(token, receipt))
    assert (rc["batchNo"], rc["batchResult"], rc["firstConfirmation"]) == (pushed["batch_no"], "SUCCESS", True)
    assert rc["message"] and rc["timestamp"]
    assert ok(w.mes_receipt(token, receipt))["firstConfirmation"] is False
    assert sql.all("SELECT verdict FROM sn_delivery_receipt WHERE batch_id = %s ORDER BY id", pushed["id"]) == [
        {"verdict": "CONFIRMED"},
        {"verdict": "ARCHIVED"},
    ], "已确认的只存档"
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE delivery_batch_no = %s AND delivery_mode = 'PULL'", pushed["batch_no"]
        )
        == 5
    )
    # 再取：建一个拉取批次
    r3 = ok(w.mes_next(token))
    assert r3["batch"]["batchNo"].startswith("PL-") and len(r3["batch"]["items"]) == 2
    assert r3["remaining"] == 0
    ok(w.mes_receipt(token, MES.process(org, r3["batch"])))
    r4 = ok(w.mes_next(token))
    assert r4["batch"] is None and r4["remaining"] == 0


def test_pull_receipt_invalid_rejected_batch_unchanged_then_fixed(w, sql):
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    # 不跑推送任务：MES 直接拉取
    w.allocate(pi)
    token, _ = w.mes_account(org)
    r = ok(w.mes_next(token, pi=pi))
    batch = r["batch"]
    good = MES.process(org, batch)
    x = w.mes_receipt(token, {**good, "code": "PARTIAL"})
    assert (x.status, x.code, x["batchNo"]) == (400, "INVALID_REQUEST", batch["batchNo"])
    assert x["details"] == [{"field": "code", "reason": "只能是 OK / NG"}]
    # 参数不合法只拒收：批次仍未确认，不存档，再取仍是同一批
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE batch_no = %s", batch["batchNo"])
    assert (b["result"], b["fail_code"]) == ("UNCONFIRMED", None)
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_receipt WHERE batch_id = %s", b["id"]) == 0
    assert ok(w.mes_next(token))["batch"]["batchNo"] == batch["batchNo"]
    # 按批次号取回：内容相同，不改状态
    back = ok(w.mes_next(token, batchNo=batch["batchNo"]))
    assert back["batch"]["itemsDigest"] == batch["itemsDigest"]
    ok(w.mes_receipt(token, good))
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE batch_no = %s", batch["batchNo"]) == "SUCCESS"
    assert w.mes_next(token, batchNo=batch["batchNo"]).code == "BATCH_CONFIRMED"


def test_receipt_batch_no_in_body_and_no_gzip(w, sql):
    """③ 批次号只在请求体：缺失 400，别的组织的 404；③ 不收 gzip，④⑤ 照收；旧地址已删除。"""
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    other, opi, _ = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    w.allocate(opi)
    token, _ = w.mes_account(org)
    otoken, _ = w.mes_account(other)
    batch = ok(w.mes_next(token, pi=pi))["batch"]
    good = MES.process(org, batch)
    for body in ({"code": "OK"}, {"batchNo": " ", "code": "OK"}, {"batchNo": 1, "code": "OK"}):
        x = w.mes_receipt(token, body)
        assert (x.status, x.code, x["details"][0]["field"]) == (400, "INVALID_REQUEST", "batchNo"), str(x)
    assert w.mes_receipt(otoken, good).code == "BATCH_NOT_FOUND", "别的组织的批次"
    assert w.mes_receipt(token, {**good, "batchNo": batch["batchNo"].lower()}).code == "BATCH_NOT_FOUND"

    def gz(path: str, body: dict) -> Res:
        raw = gzip.compress(json.dumps(body).encode("utf-8"))
        h = {"Content-Type": "application/json", "Content-Encoding": "gzip", "Authorization": f"Bearer {token}"}
        r = w.api.client.post(path, content=raw, headers=h)
        return Res(r.status_code, r.json(), r.content)

    x = gz("/api/mes/v1/receipts", good)
    assert (x.status, x.code) == (400, "INVALID_REQUEST") and "gzip" in x["message"]
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE batch_no = %s", batch["batchNo"]) == "UNCONFIRMED"
    assert ok(gz("/api/mes/v1/batches/items", {"batchNo": batch["batchNo"]}))["batchNo"] == batch["batchNo"]
    assert ok(gz("/api/mes/v1/sn/delivered", {"pi": pi}))["items"] == []
    ok(w.mes_receipt(token, good))
    assert len(ok(w.mes_items(token, batch["batchNo"]))["items"]) == 3
    assert len(ok(w.mes_delivered(token, pi=pi, pageSize=2))["items"]) == 2
    # ④⑤ 参数类型
    for x, field in (
        (w.mes_items(token, batch["batchNo"], pageSize="2"), "pageSize"),
        (w.mes_delivered(token, pageSize=True), "pageSize"),
        (w.mes_delivered(token, cursor=10), "cursor"),
        (w.mes_delivered(token, cursor="abc"), "cursor"),
        (w.mes_items(token, batch["batchNo"], cursor="1.5"), "cursor"),
        (w.api.post("/api/mes/v1/batches/items", {}, token), "batchNo"),
    ):
        assert (x.status, x.code, x["details"][0]["field"]) == (400, "INVALID_REQUEST", field), str(x)
    # 旧地址
    assert w.api.post(f"/api/mes/v1/batches/{batch['batchNo']}/receipt", good, token).status == 404
    assert w.api.get(f"/api/mes/v1/batches/{batch['batchNo']}/items", token).status == 404
    assert w.api.get(f"/api/mes/v1/sn/delivered?pi={pi}", token).status == 405


def test_void_returns_items_to_pool_and_rebatches(w, sql):
    org, pi, bill = setup_pi(w, qty=6, batch_size=10)
    MES.behavior[org] = lambda batch, h: (413, {"schemaVersion": "1.0", "code": "TOO_LARGE", "message": "too large"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["fail_code"], b["voidable"]) == ("FAILED", "TOO_LARGE", 1)
    ok(w.api.put(f"/api/factories/{org}/mes-config", m(batch_size=4), w.admin))
    MES.behavior.pop(org)
    assert w.api.post(f"/api/delivery/batches/{b['batch_no']}/void", m(reason="报文过大"), w.admin).code == (
        "BATCH_BASIS_REQUIRED"
    )
    ok(w.api.post(f"/api/delivery/batches/{b['batch_no']}/void", m(reason="报文过大", basis="MES 书面确认"), w.admin))
    assert (
        sql.scalar("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND delivery_batch_no IS NULL AND rev = 1", pi) == 6
    )
    w.push()
    rows = sql.all("SELECT qty, result FROM sn_delivery_batch WHERE pi_no = %s AND id <> %s ORDER BY id", pi, b["id"])
    assert rows == [{"qty": 4, "result": "SUCCESS"}, {"qty": 2, "result": "SUCCESS"}], "按调小后的单批枚数重新组批"
    token, _ = w.mes_account(org)
    assert w.mes_next(token, batchNo=b["batch_no"]).code == "BATCH_VOIDED"


def test_manual_confirm_stops_retry(w, sql):
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    MES.behavior[org] = lambda batch, h: (503, {"code": "X"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    ok(w.api.post(f"/api/delivery/batches/{b['batch_no']}/confirm", m(reason="MES 已入库", basis="邮件确认"), w.admin))
    b2 = sql.one("SELECT * FROM sn_delivery_batch WHERE id = %s", b["id"])
    assert (b2["result"], b2["confirm_method"], b2["push_state"]) == ("SUCCESS", "MANUAL", "IDLE")
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED' AND mes_holder = %s", pi, org)
        == 3
    )


def test_mes_secret_lock_after_failures(w, api, sql, monkeypatch):
    monkeypatch.setenv("SN_MES_AUTH_MAX_FAILURES", "3")
    org = w.org(uid("O")[:10])
    w.enable_mes(org)
    e = emp("M")
    secret = ok(api.post("/api/users", m(emp_no=e, name="MES", role="mes", factory_code=org), w.admin))["secret"]
    assert w.mes_next(f"{e}:{secret}").status == 200
    for _ in range(3):
        assert w.mes_next(f"{e}:wrong").code == "INVALID_CREDENTIALS"
    r = w.mes_next(f"{e}:{secret}")
    assert (r.status, r.code) == (423, "ACCOUNT_LOCKED"), "锁定期间密钥正确也拒绝（含已验过的密钥）"
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE operator = %s AND action = 'MES_AUTH_LOCK'", e) == 1


def test_mes_secret_invalid_after_reset(w, api, sql):
    org = w.org(uid("O")[:10])
    w.enable_mes(org)
    cred, e = w.mes_account(org)
    assert ok(w.mes_next(cred))["batch"] is None
    uid_ = sql.scalar("SELECT id FROM sn_user WHERE emp_no = %s", e)
    new = ok(api.post(f"/api/users/{uid_}/reset-secret", {}, w.admin))["secret"]
    r = w.mes_next(cred)
    assert (r.status, r.code) == (401, "INVALID_CREDENTIALS"), "重置后旧密钥立即失效（含已验过的缓存）"
    assert ok(w.mes_next(f"{e}:{new}"))["batch"] is None
    assert w.mes_next("").code == "INVALID_CREDENTIALS"
    assert api.post("/api/auth/login", m(emp_no=e, password="whatever1"), None).status == 401


def test_pull_scope_checks(w, sql):
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    token, _ = w.mes_account(org)
    r = w.mes_next(token, pi="NOPE-PI")
    assert (r.status, r.code) == (403, "ORG_NOT_ALLOWED")
    assert w.mes_next(token, batchNo="PS-NOPE").code == "BATCH_NOT_FOUND"
    other = w.org(uid("O")[:10])
    w.enable_mes(other)
    t2, _ = w.mes_account(other)
    assert w.mes_next(t2, pi=pi).code == "ORG_NOT_ALLOWED", "不能拉别的组织的 PI"


def test_pull_params_must_be_strings(w, sql):
    """② 的 pi、billNo、batchNo 须为字符串：类型不对返回 400 INVALID_REQUEST 并指出字段。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    token, _ = w.mes_account(org)
    for kw, field in (({"pi": 1}, "pi"), ({"billNo": ["x"]}, "billNo"), ({"batchNo": 1}, "batchNo")):
        x = w.mes_next(token, **kw)
        assert (x.status, x.code, x["details"][0]["field"]) == (400, "INVALID_REQUEST", field), str(x)


def test_older_rev_skipped_by_mes_without_alert(w, sql):
    """MES 已有更大的 rev：该枚跳过、不覆盖，整批仍 OK；本系统不再逐枚告警。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    sn = sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi)
    MES.store.setdefault(org, {})[sn] = {"rev": 5, "lastBatchNo": "PS-OLD", "transferredOut": False}
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["accepted_qty"]) == ("SUCCESS", 2)
    assert MES.store[org][sn]["rev"] == 5 and MES.store[org][sn]["lastBatchNo"] == "PS-OLD", "不用更小的 rev 覆盖"
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_alert WHERE sn = %s", sn) == 0


def test_rejected_batch_voided_rebatches_and_refuses_voided(w, sql):
    """9.2-5、9、15：整批退回（NG）→ 还没拉取过的失败批次取批照样给出、可按批次号取回 → 作废后号重新组批送达；
    作废的批次取回、回执都被拒绝。"""
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    MES.set_fault(org, {"mode": "reject"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["voidable"]) == ("FAILED", 1)
    assert b["batch_no"] not in MES.batches.get(org, {}), "整批拒收不登记批次号"
    token, _ = w.mes_account(org)
    first = ok(w.mes_next(token))
    assert first["batch"]["batchNo"] == b["batch_no"], "推送失败、还没拉取过的批次照样给（MES 没有批次号）"
    back = ok(w.mes_next(token, batchNo=b["batch_no"]))
    assert back["batch"]["batchNo"] == b["batch_no"]
    ng = {"batchNo": b["batch_no"], "code": "NG", "message": "仍不能处理"}
    rc = ok(w.mes_receipt(token, ng))
    # 整批退回已登记，本次回执生效（接口文档 3.3）
    assert (rc["batchResult"], rc["firstConfirmation"]) == ("FAILED", True)
    ok(w.api.post(f"/api/delivery/batches/{b['batch_no']}/void", m(reason="整批拒收", basis="MES 确认未处理"), w.admin))
    assert w.mes_next(token, batchNo=b["batch_no"]).code == "BATCH_VOIDED"
    late = {"batchNo": b["batch_no"], "code": "OK", "message": None}
    assert w.mes_receipt(token, late).code == "BATCH_VOIDED"
    MES.clear_fault(org)
    ok(w.api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin))
    w.push()
    rows = sql.all("SELECT batch_no, qty, result FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert [(r["qty"], r["result"]) for r in rows] == [(3, "VOIDED"), (3, "SUCCESS")]
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED' AND rev = 1", pi) == 3


def test_check_alert_closed_with_note(w, sql):
    """交付告警（每日自检）：填写核查结论后关闭；结论必填，已关闭的不能再处理。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT batch_no FROM sn_delivery_batch WHERE pi_no = %s", pi)
    sql.exec(
        "INSERT INTO sn_delivery_alert(alert_type, factory_code, batch_no, detail, created_at) "
        "VALUES ('CHECK',%s,%s,'自检问题',NOW())",
        org,
        b["batch_no"],
    )
    a = sql.one("SELECT id FROM sn_delivery_alert WHERE batch_no = %s", b["batch_no"])
    url = f"/api/delivery/alerts/{a['id']}/handle"
    assert w.api.post(url, m(note=" "), w.admin).code == "REASON_REQUIRED"
    ok(w.api.post(url, m(note="已核实"), w.admin))
    closed = sql.one("SELECT status, closed_by, note FROM sn_delivery_alert WHERE id = %s", a["id"])
    assert (closed["status"], closed["note"]) == ("CLOSED", "已核实") and closed["closed_by"]
    assert w.api.post(url, m(note="再处理"), w.admin).code == "DALERT_NOT_FOUND"


def test_pool_claims_one_batch_type_per_batch(w, sql):
    """号池里混有不同类型的号（如暂停期间留在号池的改挂）：认领时按类型分批，一批只有一种 batchType。"""
    from app.services import delivery

    org, pi, bill = setup_pi(w, qty=6, batch_size=10)
    w.allocate(pi)
    ids = [r["id"] for r in sql.all("SELECT id FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    sql.exec("UPDATE sn_item SET push_type = 'REASSIGN' WHERE id IN (%s, %s)", *ids[4:])
    assert delivery.remaining_pushes(org, pi)["pushes"] == 2, "按类型分批折算次数"
    w.push()
    rows = sql.all("SELECT batch_type, qty FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert [(r["batch_type"], r["qty"]) for r in rows] == [("NEW", 4), ("REMAP", 2)]
    got = [x["batch"]["batchType"] for x in MES.received if x["org"] == org and x["batch"]["batchType"] != "PING"]
    assert got == ["NEW", "REMAP"]
    # 批次查询「交付」= 全部交付类
    assert ok(w.api.get(f"/api/delivery/batches?pi={pi}&batch_type=DELIVERY", w.admin))["total"] == 2
    assert ok(w.api.get(f"/api/delivery/batches?pi={pi}&batch_type=REMAP", w.admin))["total"] == 1


def _receipt_first(monkeypatch, w, org: str, code: str) -> None:
    """模拟 MES 先调 ③ 回执、后回推送应答（应答在网络上晚到）：发送时先提交回执，再应答已收到。"""
    from app.services import mes_client

    token = w.receipt_token(org)

    def send(t, batch_no, body_gz, attempt, ping=False):
        r = ok(
            w.mes_receipt(
                token, m(batchNo=batch_no, code=code, message="先到的回执", processedAt="2026-10-26 11:47:06.008")
            )
        )
        assert r["firstConfirmation"] is True
        return mes_client.Outcome(mes_client.RECEIVED, body={"batchNo": batch_no, "code": "OK"}, http_status=200)

    monkeypatch.setattr(mes_client, "send", send)


def test_ok_receipt_before_push_reply_confirms_and_continues(w, sql, monkeypatch):
    """回执 OK 比推送应答先到：批次直接确认，应答到了也按已确认处理，接着推下一批。"""
    org, pi, bill = setup_pi(w, qty=7, batch_size=5)
    w.allocate(pi)
    _receipt_first(monkeypatch, w, org, "OK")
    w.push(receipts=False)
    rows = sql.all("SELECT result, push_state FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert rows == [{"result": "SUCCESS", "push_state": "IDLE"}] * 2


@pytest.mark.parametrize("resend", [False, True])
def test_ng_receipt_before_push_reply_keeps_failed(w, sql, monkeypatch, resend):
    """回执 NG 比推送应答先到（首次推送，或「继续推送」重推失败批次）：批次保持失败、任务停止并提示原因，
    不被随后到的应答改回等回执。"""
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    if resend:
        MES.set_fault(org, {"mode": "reject", "message": "首次退回"})
        w.allocate(pi)
        w.push()
        MES.clear_fault(org)
        assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE pi_no = %s", pi) == "FAILED"
        _receipt_first(monkeypatch, w, org, "NG")
        ok(w.api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin))
    else:
        _receipt_first(monkeypatch, w, org, "NG")
        w.allocate(pi)
    w.push(receipts=False)
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["push_state"], b["fail_code"], b["fail_reason"]) == (
        "FAILED",
        "IDLE",
        "REJECTED",
        "先到的回执",
    )
    task = sql.one("SELECT * FROM sn_push_task WHERE factory_code = %s AND task_key = %s", org, pi)
    assert task["state"] == "STOPPED" and "先到的回执" in task["message"]


def test_batch_no_serial_shared_across_push_pull_and_transfer_out(w, sql):
    """同一组织同一天推送、拉取、转出共用一个序号：PS- 与 PL- 的尾号不重复。"""
    org, pi, bill = setup_pi(w, qty=4, batch_size=2)
    MES.behavior[org] = lambda batch, h: ("drop", None)
    w.allocate(pi)
    w.push()
    MES.behavior.pop(org)
    token, _ = w.mes_account(org)
    pushed = sql.scalar("SELECT batch_no FROM sn_delivery_batch WHERE pi_no = %s", pi)
    r = ok(w.mes_next(token, pi=pi))
    assert r["batch"]["batchNo"] == pushed
    ok(w.mes_receipt(token, MES.process(org, r["batch"])))
    pulled = ok(w.mes_next(token, pi=pi))["batch"]["batchNo"]
    assert pushed.startswith("PS-") and pulled.startswith("PL-")
    assert pushed.rsplit("-", 1)[1] != pulled.rsplit("-", 1)[1]
    assert int(pulled.rsplit("-", 1)[1]) == int(pushed.rsplit("-", 1)[1]) + 1


def test_batch_no_serial_continues_from_old_per_prefix_counters(w, sql):
    """改为共用序号当天：从各前缀原有的序号接着编，不与当天已建的批次尾号重复。"""
    from app.core import utils as util
    from app.services.delivery.batches import _batch_no

    org = uid("Q")[:10]
    day = util.mes_local().strftime("%Y%m%d")
    sql.exec("INSERT INTO sn_seq(name, val) VALUES (%s, 7), (%s, 3)", f"PS-{org}-{day}", f"PL-{org}-{day}")
    assert _batch_no("PL", org) == f"PL-{org}-{day}-000008"
    assert _batch_no("PS", org) == f"PS-{org}-{day}-000009"


def test_failed_push_batch_pulled_first_then_rest(w, sql):
    """推送失败（MES 没收下，没有批次号）后 MES 不限范围拉取：先拿到推送失败的那一批，再拉号池里的号，
    全部号都经 MES 回执确认，不会少一批；剩余枚数含推送失败还没拉过的批次。"""
    org, pi, bill = setup_pi(w, qty=12, batch_size=5)
    MES.behavior[org] = lambda batch, h: (200, {"code": "ERROR", "message": "MES 内部错误"})
    w.allocate(pi)
    w.push()
    failed = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (failed["result"], failed["fail_code"]) == ("FAILED", "RESPONSE_INVALID")
    MES.behavior.pop(org)
    token, _ = w.mes_account(org)
    got = []
    while True:
        r = ok(w.mes_next(token))
        if r["batch"] is None:
            break
        got.append((r["batch"]["batchNo"], r["remaining"]))
        ok(w.mes_receipt(token, MES.process(org, r["batch"])))
    assert got[0] == (failed["batch_no"], 7), "推送失败的那一批先给，剩余含号池 7 枚"
    assert [x[0][:3] for x in got] == ["PS-", "PL-", "PL-"]
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 12
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_batch WHERE pi_no = %s AND result = 'SUCCESS'", pi) == 3
    assert len(MES.store[org]) == 12, "MES 拿到全部 12 枚"


def test_failed_batch_rejected_after_pull_goes_last(w, sql):
    """MES 拉到推送失败的批次后又整批退回：之后排在号池之后，不挡住后续的号，剩余枚数不含它。"""
    org, pi, bill = setup_pi(w, qty=4, batch_size=2)
    MES.behavior[org] = lambda batch, h: (400, {"code": "BAD", "message": "x"})
    w.allocate(pi)
    w.push()
    failed = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    MES.behavior.pop(org)
    token, _ = w.mes_account(org)
    r = ok(w.mes_next(token, pi=pi))
    assert (r["batch"]["batchNo"], r["remaining"]) == (failed["batch_no"], 2)
    ok(w.mes_receipt(token, {"batchNo": failed["batch_no"], "code": "NG", "message": "仍有问题"}))
    r = ok(w.mes_next(token, pi=pi))
    assert r["batch"]["batchNo"].startswith("PL-") and r["remaining"] == 0
    ok(w.mes_receipt(token, MES.process(org, r["batch"])))
    r = ok(w.mes_next(token))
    assert (r["batch"]["batchNo"], r["remaining"]) == (failed["batch_no"], 0)
