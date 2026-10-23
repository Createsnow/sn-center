"""交付：分配后分批推送、自动重试与续推、MES 拉取与回执、人工确认与作废、交付告警（F-11、F-12、5.2.5）。"""

from __future__ import annotations

import json

from conftest import MES, emp, m, ok, uid


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
    w.push()
    batches = sql.all("SELECT * FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert [b["qty"] for b in batches] == [5, 5, 2]
    assert len({b["batch_no"] for b in batches}) == 3
    assert {b["result"] for b in batches} == {"SUCCESS"}
    assert {b["confirm_method"] for b in batches} == {"PUSH_RESPONSE"}
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
    # 报文：逐枚带单据编号、序号、当前 PI、流水归属 PI；十进制流水、米标按字符串
    body = MES.received[-1]["batch"]
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
        "materialCode",
        "length",
        "customerCode",
    }
    assert item["billNo"] == bill and item["billSeq"] == 1 and item["pi"] == pi and item["serialPi"] == pi
    assert isinstance(item["serialDec"], str) and item["length"] == "0100"
    h = MES.received[-1]["headers"]
    # 默认发原始 JSON，不带 Content-Encoding
    assert h["x-sn-batch-no"] == batches[-1]["batch_no"] and "content-encoding" not in h
    assert json.loads(MES.received[-1]["raw"])["batchNo"] == batches[-1]["batch_no"]


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
    # 号保持冻结：不能页面领取
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
    org, pi, bill = setup_pi(w, qty=4, batch_size=5)
    MES.behavior[org] = lambda batch, h: (
        200,
        {"schemaVersion": "1.0", "batchNo": batch["batchNo"], "code": "NG", "message": "materialCode 超长"},
    )
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["voidable"], b["push_count"]) == ("FAILED", 1, 1), "整批拒收不自动重试"
    assert b["fail_code"] == "REJECTED" and b["fail_reason"] == "materialCode 超长"
    MES.behavior.pop(org)
    ok(w.api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin))
    w.push()
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE id = %s", b["id"]) == "SUCCESS"


def test_receipt_batch_no_mismatch_fails_batch(w, sql):
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    MES.set_fault(org, {"mode": "bad_receipt"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["fail_code"], b["voidable"]) == ("FAILED", "RECEIPT_MISMATCH", 0)
    r = w.api.post(f"/api/delivery/batches/{b['batch_no']}/void", m(reason="x", basis="y"), w.admin)
    assert r.code == "BATCH_NOT_VOIDABLE", "回执不符说明 MES 已处理过，不能作废"


def test_unknown_receipt_code_is_invalid_response(w, sql):
    """HTTP 200 但 code 不是 OK / NG：不当回执，记为应答无效、不重试。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    MES.behavior[org] = lambda batch, h: (200, {"schemaVersion": "1.0", "batchNo": batch["batchNo"], "code": "DONE"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["fail_code"], b["push_count"]) == ("FAILED", "RESPONSE_INVALID", 1)


def test_same_rev_from_other_batch_overwrites_without_alert(w, sql):
    """MES 已有同 rev、来自别的批次的号（如作废后重新组批）：同 rev 挂靠相同，逐枚覆盖，不再告警。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    sn = sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi)
    MES.store.setdefault(org, {})[sn] = {"rev": 1, "lastBatchNo": "PS-OTHER", "transferredOut": False}
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["accepted_qty"], b["conflict_qty"]) == ("SUCCESS", 2, 0)
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
    assert r1["batch"]["batchNo"] == pushed["batch_no"] and r1["newlyCreated"] is False
    assert r1["batchSource"] == "PUSH" and r1["remaining"] == 2
    r2 = ok(w.mes_next(token))
    assert r2["batch"]["batchNo"] == pushed["batch_no"], "没回执前再取仍是同一批"
    receipt = MES.process(org, r1["batch"])
    rc = ok(w.mes_receipt(token, pushed["batch_no"], receipt))
    assert (rc["batchResult"], rc["firstConfirmation"]) == ("SUCCESS", True)
    again = ok(w.mes_receipt(token, pushed["batch_no"], receipt))
    assert again["firstConfirmation"] is False, "已确认的只存档"
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item WHERE delivery_batch_no = %s AND delivery_mode = 'PULL'", pushed["batch_no"]
        )
        == 5
    )
    # 再取：建一个拉取批次
    r3 = ok(w.mes_next(token))
    assert r3["newlyCreated"] is True and r3["batchSource"] == "PULL" and r3["batch"]["count"] == 2
    assert r3["remaining"] == 0
    ok(w.mes_receipt(token, r3["batch"]["batchNo"], MES.process(org, r3["batch"])))
    r4 = ok(w.mes_next(token))
    assert r4["batch"] is None and r4["remaining"] == 0


def test_pull_receipt_mismatch_rejected_then_fixed(w, sql):
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    # 不跑推送任务：MES 直接拉取
    w.allocate(pi)
    token, _ = w.mes_account(org)
    r = ok(w.mes_next(token, pi=pi))
    batch = r["batch"]
    good = MES.process(org, batch)
    bad = {**good, "code": "PARTIAL"}
    x = w.mes_receipt(token, batch["batchNo"], bad)
    assert x.status == 422 and x.code == "RECEIPT_MISMATCH" and x["details"]
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE batch_no = %s", batch["batchNo"]) == "FAILED"
    # 失败的批次取批时跳过（没有别的号可取 → 空）
    assert ok(w.mes_next(token))["batch"] is None
    # 按批次号取回、补交正确回执
    back = ok(w.mes_next(token, batchNo=batch["batchNo"]))
    assert back["batch"]["itemsDigest"] == batch["itemsDigest"]
    ok(w.mes_receipt(token, batch["batchNo"], good))
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE batch_no = %s", batch["batchNo"]) == "SUCCESS"
    assert w.mes_next(token, batchNo=batch["batchNo"]).code == "BATCH_CONFIRMED"


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


def test_older_rev_skipped_by_mes_without_alert(w, sql):
    """MES 已有更大的 rev：该枚跳过、不覆盖，整批仍 OK；本系统不再逐枚告警。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    sn = sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi)
    MES.store.setdefault(org, {})[sn] = {"rev": 5, "lastBatchNo": "PS-OLD", "transferredOut": False}
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["accepted_qty"], b["stale_qty"]) == ("SUCCESS", 2, 0)
    assert MES.store[org][sn]["rev"] == 5 and MES.store[org][sn]["lastBatchNo"] == "PS-OLD", "不用更小的 rev 覆盖"
    assert sql.count("SELECT COUNT(*) FROM sn_delivery_alert WHERE sn = %s", sn) == 0


def test_rejected_batch_voided_rebatches_and_refuses_voided(w, sql):
    """9.2-5、9、15：整批退回（NG）→ 取批跳过、可按批次号取回 → 作废后号重新组批送达；作废的批次取回、回执都被拒绝。"""
    org, pi, bill = setup_pi(w, qty=3, batch_size=5)
    MES.set_fault(org, {"mode": "reject"})
    w.allocate(pi)
    w.push()
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert (b["result"], b["voidable"]) == ("FAILED", 1)
    assert b["batch_no"] not in MES.batches.get(org, {}), "整批拒收不登记批次号"
    token, _ = w.mes_account(org)
    assert ok(w.mes_next(token))["batch"] is None, "失败的批次不再给"
    back = ok(w.mes_next(token, batchNo=b["batch_no"]))
    assert back["batch"]["batchNo"] == b["batch_no"] and back["batchResult"] == "FAILED"
    ok(w.api.post(f"/api/delivery/batches/{b['batch_no']}/void", m(reason="整批拒收", basis="MES 确认未处理"), w.admin))
    assert w.mes_next(token, batchNo=b["batch_no"]).code == "BATCH_VOIDED"
    late = {"schemaVersion": "1.0", "batchNo": b["batch_no"], "code": "OK", "message": None}
    assert w.mes_receipt(token, b["batch_no"], late).code == "BATCH_VOIDED"
    MES.clear_fault(org)
    ok(w.api.post("/api/delivery/resume", m(factory_code=org, key=pi), w.admin))
    w.push()
    rows = sql.all("SELECT batch_no, qty, result FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id", pi)
    assert [(r["qty"], r["result"]) for r in rows] == [(3, "VOIDED"), (3, "SUCCESS")]
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED' AND rev = 1", pi) == 3


def test_legacy_conflict_alert_resend_bumps_rev(w, sql):
    """旧协议留下的逐枚冲突告警仍可「以本系统为准重推」：rev 取较大者 + 1，改挂重推。"""
    org, pi, bill = setup_pi(w, qty=2, batch_size=5)
    w.allocate(pi)
    w.push()
    it = sql.one("SELECT id, sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi)
    b = sql.one("SELECT batch_no FROM sn_delivery_batch WHERE pi_no = %s", pi)
    sql.exec(
        "INSERT INTO sn_delivery_alert(alert_type, factory_code, batch_no, item_id, sn, rev, existing_rev, "
        "existing_batch_no, detail, created_at) VALUES ('CONFLICT',%s,%s,%s,%s,1,1,'PS-OTHER','疑似重复交付',NOW())",
        org,
        b["batch_no"],
        it["id"],
        it["sn"],
    )
    a = sql.one("SELECT id FROM sn_delivery_alert WHERE sn = %s", it["sn"])
    ok(w.api.post(f"/api/delivery/alerts/{a['id']}/handle", m(action="RESEND", note="以本系统为准"), w.admin))
    assert sql.one("SELECT rev, status FROM sn_item WHERE id = %s", it["id"]) == {"rev": 2, "status": "TO_PUSH"}
    w.push()
    assert MES.store[org][it["sn"]]["rev"] == 2
    assert sql.scalar("SELECT status FROM sn_item WHERE id = %s", it["id"]) == "PUSHED"
