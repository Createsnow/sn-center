"""删号重建：按单据、物料行、单枚或一段 SN 删号（状态不限），最大流水号不退回、留下空号段；
重新生成时可从最大号之后编，或手动起止号用空号段（不够时从最大号之后接着编）；额度还给号现在挂的物料行。"""

from __future__ import annotations

from conftest import BILLS, MES, m, mark_bill_stat, ok, uid


def mk(w, *lines, pi=None, org=None, bill=None):
    org = org or w.org(uid("O")[:10])
    pi = pi or uid("PI")
    bill = bill or uid("MO")
    w.order(bill, org, "C1", pi, *lines)
    return org, pi, bill


def P() -> str:
    return "P" + uid("").replace("-", "")


def sns(sql, pi: str) -> list[tuple[int, int, str]]:
    """（序号，十进制流水，SN）按流水排。"""
    return [
        (r["bill_seq"], r["seq_dec"], r["sn"])
        for r in sql.all("SELECT bill_seq, seq_dec, sn FROM sn_item WHERE seq_pi_no = %s ORDER BY seq_dec", pi)
    ]


def gaps(w, pi: str) -> list[tuple[int, int]]:
    g = ok(w.api.get(f"/api/generate/gaps?pi={pi}", w.admin))
    return [(x["start_seq"], x["end_seq"]) for x in g["segments"]]


def counts(sql, bill: str, seq: int) -> tuple[int, int, int]:
    c = sql.one("SELECT * FROM sn_line_count WHERE bill_no = %s AND seq = %s", bill, seq)
    return int(c["own_generated"]), int(c["gen_count"]), int(c["alloc_count"])


def test_delete_any_state_keeps_max_and_returns_quota(w, api, sql):
    """已分配的号也能删；最大号不退回、留下空号段；额度、计数退回；删前的号存档；默认从最大号之后接着编。"""
    org, pi, bill = mk(w, ("M1", 3), ("M2", 4), ("M3", 2))
    p = P()
    ok(w.bind_pi(pi, w.rule(p, with_length=False)))
    w.generate(pi)
    w.allocate(pi, lines=[(bill, 2)])
    pre = ok(w.delete(pi, lines=[(bill, 2)], preview=True))
    assert (pre["qty"], pre["by_status"], pre["orgs"]) == (4, {"ALLOCATED": 4}, [org])
    r = ok(w.delete(pi, lines=[(bill, 2)], reason="数量改了"))
    assert (r["qty"], r["lines"]) == (4, [{"bill_no": bill, "seq": 2, "qty": 4}])
    assert sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) == 9, "最大号不退回"
    assert gaps(w, pi) == [(4, 7)]
    assert counts(sql, bill, 2) == (0, 0, 0)
    assert (
        sql.count("SELECT COUNT(*) FROM sn_item_deleted WHERE del_no = %s AND status = 'ALLOCATED'", r["del_no"]) == 4
    )
    assert sql.scalar("SELECT reason FROM sn_item_deleted WHERE del_no = %s LIMIT 1", r["del_no"]) == "数量改了"
    rec = ok(api.get(f"/api/generate/deleted?pi={pi}", w.admin)).body
    assert [(x["del_no"], x["qty"], x["bills"]) for x in rec] == [(r["del_no"], 4, [bill])]
    # 数量改了再生成：默认从最大号之后接着编，空号段不用
    BILLS[bill]["TreeEntity"][1]["Qty"] = 5.0
    w.touch(bill)
    w.compare()
    j = w.generate(pi, lines=[(bill, 2)])
    assert (j["start_seq_dec"], j["end_seq_dec"]) == (10, 14)
    assert gaps(w, pi) == [(4, 7)]
    # 删中间的物料行（待分配）也行，不限流水先后
    ok(w.delete(pi, lines=[(bill, 1)]))
    assert gaps(w, pi) == [(1, 7)]
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 7


def test_regenerate_into_gaps_with_overflow(w, api, sql):
    """手动起止号：用其间的空号段与未使用的号，按流水从小到大；不够时从 max(最大号, 止号) + 1 接着编；
    一个物料行可分到几段不连续的号；同样的参数重新生成的号与删掉的相同（不考虑旧号）。"""
    org, pi, bill = mk(w, ("M1", 3), ("M2", 4), ("M3", 2))
    p = P()
    ok(w.bind_pi(pi, w.rule(p, seq_len=4, with_length=False)))
    w.generate(pi)
    before = sns(sql, pi)
    ok(w.delete(pi, lines=[(bill, 2)]))
    assert w.preview(pi, lines=[(bill, 2)], seq=(5, 4)).code == "GEN_SEQ_RANGE_INVALID"
    assert w.preview(pi, lines=[(bill, 2)], seq=(0, 4)).code == "GEN_SEQ_RANGE_INVALID"
    pv = ok(w.preview(pi, lines=[(bill, 2)], seq=(4, 7)))
    assert pv["items"][0]["segs"] == [[4, 7]] and (pv["range_qty"], pv["overflow_qty"]) == (4, 0)
    assert pv["gaps"]["segments"] == [{"start_seq": 4, "end_seq": 7, "qty": 4, "bill_no": bill}]
    w.generate(pi, lines=[(bill, 2)], seq=(4, 7))
    assert sns(sql, pi) == before, "同样的参数用回空号段，号与删掉的相同"
    assert sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) == 9
    # 空号段不够：序号 1 改成 5 枚，空号段 1～3 只有 3 个，其余 2 枚从最大号之后接着编
    ok(w.delete(pi, lines=[(bill, 1)]))
    BILLS[bill]["TreeEntity"][0]["Qty"] = 5.0
    w.touch(bill)
    w.compare()
    pv = ok(w.preview(pi, lines=[(bill, 1)], seq=(1, 9)))
    item = pv["items"][0]
    assert item["segs"] == [[1, 3], [10, 11]] and (pv["range_qty"], pv["overflow_qty"]) == (3, 2)
    assert [(x["start_sn"], x["end_sn"], x["qty"]) for x in item["seg_list"]] == [
        (f"{p}0001", f"{p}0003", 3),
        (f"{p}0010", f"{p}0011", 2),
    ]
    j = ok(w.api.post("/api/generate/run", m(token=pv["token"]), w.admin)).body
    assert j["status"] == "SUCCESS" and j["lines"][0]["seg_list"][1]["start_sn"] == f"{p}0010"
    assert [x[1] for x in sns(sql, pi) if x[0] == 1] == [1, 2, 3, 10, 11]
    assert sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) == 11
    assert gaps(w, pi) == []
    # 起止号在最大号之后：跳过的流水成为空号段
    ok(w.delete(pi, lines=[(bill, 3)]))
    w.generate(pi, lines=[(bill, 3)], seq=(20, 21))
    assert gaps(w, pi) == [(8, 9), (12, 19)]
    assert sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) == 21


def segs(w, pi: str, bill: str) -> dict:
    return ok(w.api.get(f"/api/generate/segments?pi={pi}&bill_no={bill}", w.admin))


def test_bill_segments_sorted_by_attach_no(w):
    """单据号段弹窗的已用号段按附件项次从小到大（按数字比：2 在 10 前），项次相同按行号。"""
    org, pi, bill = mk(w, ("M1", 2, "100", "10"), ("M2", 3, "100", "2"), ("M3", 1, "100", "2"))
    ok(w.bind_pi(pi, w.rule(P(), seq_len=4, with_length=False)))
    w.generate(pi)
    rows = [
        (x["attach_no"], x["bill_seq"], x["material_code"], x["start_seq"], x["end_seq"])
        for x in segs(w, pi, bill)["use"]
    ]
    assert rows == [("2", 2, "M2", 1, 3), ("2", 3, "M3", 4, 4), ("10", 1, "M1", 5, 6)]


def test_bill_segments_use_free_and_sn_detail(w, api, sql):
    """单据号段：现有的号按物料行分开、行内按流水连续合并（转入的号按流水归属 PI 单独成段），空闲号段按删号时的
    生成单据归属；生成详情的单据头带号段数与枚数（按物料行计）；SN 明细按流水分页（可只看一个物料行）；
    跳过的流水（没有删号存档）不归属任何单据。"""
    org, pi, bill = mk(w, ("M1", 3), ("M2", 4), ("M3", 2))
    _, _, bill2 = mk(w, ("M4", 3), pi=pi, org=org)
    p = P()
    ok(w.bind_pi(pi, w.rule(p, seq_len=4, with_length=False)))
    w.generate(pi)  # bill 1～9，bill2 10～12（排号按单据编号）
    # 流水连续的几个物料行也按物料行分开列出起止号
    s = segs(w, pi, bill)
    assert [(x["bill_seq"], x["material_code"], x["start_seq"], x["end_seq"], x["qty"]) for x in s["use"]] == [
        (1, "M1", 1, 3, 3),
        (2, "M2", 4, 7, 4),
        (3, "M3", 8, 9, 2),
    ]
    assert (s["use"][1]["start_sn"], s["use"][1]["end_sn"]) == (f"{p}0004", f"{p}0007")
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    assert {b["bill_no"]: b["segments"]["use_count"] for b in ctx["bills"]}[bill] == 3
    d = ok(api.get(f"/api/generate/segments/sns?pi={pi}&bill_no={bill}&bill_seq=2&start=1&end=9", w.admin))
    assert (d["total"], d["items"][0], d["items"][-1]) == (4, f"{p}0004", f"{p}0007")
    ok(w.delete(pi, lines=[(bill, 2)]))  # bill 腾出 4～7
    s = segs(w, pi, bill)
    assert [(x["start_seq"], x["end_seq"], x["qty"], x["transferred_in"]) for x in s["use"]] == [
        (1, 3, 3, False),
        (8, 9, 2, False),
    ]
    assert (s["use"][0]["start_sn"], s["use"][0]["end_sn"], s["use"][0]["pending"]) == (f"{p}0001", f"{p}0003", 3)
    assert [(x["start_seq"], x["end_seq"], x["bill_no"]) for x in s["free"]] == [(4, 7, bill)]
    assert (s["next_seq"], s["use_qty"], s["free_qty"]) == (13, 5, 4)
    assert segs(w, pi, bill2)["free"] == []
    # 别的 PI 的号转入本单据：按流水归属 PI 单独成段，排在所在物料行本 PI 的号段之后
    _, pi2, bill3 = mk(w, ("M5", 2), org=org)
    ok(w.bind_pi(pi2, w.rule(P(), with_length=False)))
    w.generate(pi2)
    sql.exec("UPDATE sn_item SET pi_no = %s, bill_no = %s, bill_seq = 1 WHERE seq_pi_no = %s", pi, bill, pi2)
    mark_bill_stat(sql)
    s = segs(w, pi, bill)
    assert [(x["bill_seq"], x["seq_pi_no"], x["start_seq"], x["end_seq"], x["transferred_in"]) for x in s["use"]] == [
        (1, pi, 1, 3, False),
        (1, pi2, 1, 2, True),
        (3, pi, 8, 9, False),
    ]
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    heads = {b["bill_no"]: b["segments"] for b in ctx["bills"]}
    assert heads[bill] == {"use_count": 3, "use_qty": 7, "free_count": 1, "free_qty": 4}
    assert heads[bill2] == {"use_count": 1, "use_qty": 3, "free_count": 0, "free_qty": 0}
    # SN 明细
    d = ok(api.get(f"/api/generate/segments/sns?pi={pi}&bill_no={bill}&start=1&end=9&page=2&size=3", w.admin))
    assert (d["total"], d["items"]) == (5, [f"{p}0008", f"{p}0009"])
    d = ok(api.get(f"/api/generate/segments/sns?pi={pi}&bill_no={bill}&seq_pi={pi2}&start=1&end=2", w.admin))
    assert d["total"] == 2
    assert api.get(f"/api/generate/segments/sns?pi={pi}&bill_no={bill}&start=5&end=4", w.admin).code == (
        "GEN_SEQ_RANGE_INVALID"
    )
    assert api.get(f"/api/generate/segments?pi={pi}", w.admin).code == "ORDER_BILL_REQUIRED"
    # 起止号在最大号之后：跳过的流水是空号段，但不归属任何单据
    ok(w.delete(pi, lines=[(bill2, 1)]))
    w.generate(pi, lines=[(bill2, 1)], seq=(20, 22))
    g = ok(api.get(f"/api/generate/gaps?pi={pi}", w.admin))
    assert [(x["start_seq"], x["end_seq"], x["bill_no"]) for x in g["segments"]] == [
        (4, 7, bill),
        (10, 12, bill2),
        (13, 19, ""),
    ]


def test_two_lines_share_gaps_in_order_and_preview_tracks_gaps(w, api, sql):
    """几个物料行按排号顺序（单据内按附件项次，项次相同按行号，与点选先后无关）依次用空号段；
    按手动起止号预演后空号段变了，确认生成时预演失效。"""
    org, pi, bill = mk(w, ("M1", 2), ("M2", 2), ("M3", 2), ("M4", 1))
    ok(w.bind_pi(pi, w.rule(P(), with_length=False)))
    w.generate(pi)
    ok(w.delete(pi, lines=[(bill, 1), (bill, 3)]))
    assert gaps(w, pi) == [(1, 2), (5, 6)]
    pv = ok(w.preview(pi, lines=[(bill, 3), (bill, 1)], seq=(1, 6)))
    assert [(x["seq"], x["segs"]) for x in pv["items"]] == [(1, [[1, 2]]), (3, [[5, 6]])]
    pv = ok(w.preview(pi, lines=[(bill, 3), (bill, 1)], seq=(5, 6)))
    assert [(x["seq"], x["segs"]) for x in pv["items"]] == [(1, [[5, 6]]), (3, [[8, 9]])]
    # 预演后又删了号（空号段变了）：确认生成按最新空号段重算，与预演不同即失效
    ok(w.delete(pi, lines=[(bill, 4)]))
    pv = ok(w.preview(pi, lines=[(bill, 1)], seq=(3, 7)))
    assert pv["items"][0]["segs"] == [[5, 6]]
    ok(w.delete(pi, lines=[(bill, 2)]))
    assert w.api.post("/api/generate/run", m(token=pv["token"]), w.admin).code == "GEN_PREVIEW_CHANGED"
    pv = ok(w.preview(pi, lines=[(bill, 1)], seq=(3, 7)))
    assert pv["items"][0]["segs"] == [[3, 4]]
    # 不选起止号（从最大号之后）不受空号段影响
    j = w.generate(pi, lines=[(bill, 3)])
    assert j["start_seq_dec"] == 8


def test_delete_single_sn_and_range(w, api, sql):
    """单枚、一段 SN（起止号须在同一物料行）；额度按删掉的枚数退回。"""
    org, pi, bill = mk(w, ("M1", 10), ("M2", 2))
    p = P()
    ok(w.bind_pi(pi, w.rule(p, seq_len=4, with_length=False)))
    w.generate(pi)
    w.allocate(pi)
    ok(w.delete(pi, start_sn=f"{p}0005"))
    ok(w.delete(pi, start_sn=f"{p}0007", end_sn=f"{p}0008"))
    assert gaps(w, pi) == [(5, 5), (7, 8)]
    assert counts(sql, bill, 1) == (7, 7, 7)
    line = next(x for x in ok(api.get(f"/api/orders/{bill}/lines", w.admin))["lines"] if x["seq"] == 1)
    assert line["quota"] == 3
    assert w.delete(pi, start_sn=f"{p}0009", end_sn=f"{p}0011").code == "DELETE_RANGE_INVALID", "跨物料行"
    assert w.delete(pi, start_sn=f"{p}0009", end_sn=f"{p}0006").code == "DELETE_RANGE_INVALID", "止号在起号之前"
    assert w.delete(pi, start_sn=f"{p}0005").code == "DELETE_SN_NOT_FOUND", "已删"
    # 重新生成 3 枚补回空号段
    w.generate(pi, lines=[(bill, 1)], seq=(1, 12))
    assert [x[1] for x in sns(sql, pi)] == list(range(1, 13))


def test_delete_returns_quota_to_current_line(w, api, sql):
    """转过单的号：额度还给号现在挂的物料行（现住址），生成行的额度不变；流水空出在原 PI。"""
    org = w.org(uid("O")[:10])
    _, pi, src = mk(w, ("M1", 5), org=org)
    _, pi2, dst = mk(w, ("M1", 2), org=org)
    ok(w.bind_pi(pi, w.rule(P(), with_length=False)))
    w.generate(pi)
    w.allocate(pi)
    body = m(pi_no=pi, bill_no=src, scope="MATERIAL", material_code="M1", to_pi=pi2, to_bill=dst, to_factory=org)
    body |= m(to_material="M1", reason="拆单")
    ok(api.post("/api/transfers/bill", body, w.admin))
    assert counts(sql, src, 1) == (5, 0, 0) and counts(sql, dst, 1) == (0, 5, 5)
    ok(w.delete(pi2, lines=[(dst, 1)]))
    assert counts(sql, src, 1) == (5, 0, 0), "生成行不变"
    assert counts(sql, dst, 1) == (0, 0, 0)
    assert gaps(w, pi) == [(1, 5)], "流水空出在原 PI"
    assert gaps(w, pi2) == []
    ok(w.bind_pi(pi2, w.rule(P(), with_length=False)))
    j = w.generate(pi2)
    assert (j["qty"], j["start_seq_dec"]) == (2, 1), "目标行按自己的数量生成，用自己 PI 的流水"


def test_delete_sn_in_pending_batch_voids_and_rebuilds(w, api, sql):
    """号在未确认的交付批次中：删后该批次作废，其余号回号池重新分批推送；已确认批次里的号删了批次不动。"""
    org = w.org(uid("O")[:10])
    w.enable_mes(org, batch_size=3)
    _, pi, bill = mk(w, ("M1", 6), org=org)
    p = P()
    ok(w.bind_pi(pi, w.rule(p, seq_len=4, with_length=False)))
    w.generate(pi)
    w.allocate(pi)
    w.push(receipts=False)
    first = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s ORDER BY id LIMIT 1", pi)
    assert first["result"] == "UNCONFIRMED"
    pre = ok(w.delete(pi, start_sn=f"{p}0001", preview=True))
    assert pre["batches"] == [first["batch_no"]]
    r = ok(w.delete(pi, start_sn=f"{p}0001"))
    assert r["voided"] == [first["batch_no"]]
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE id = %s", first["id"]) == "VOIDED"
    MES.take_outbox()
    w.push()
    got = {s for x in MES.received if x["org"] == org for s in (i["sn"] for i in x["batch"]["items"])}
    assert f"{p}0002" in got and f"{p}0006" in got
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 5
    # 已推送的号也能删：成功批次不动
    done = sql.scalar("SELECT delivery_batch_no FROM sn_item WHERE sn = %s", f"{p}0006")
    ok(w.delete(pi, start_sn=f"{p}0006"))
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE batch_no = %s", done) == "SUCCESS"
    assert counts(sql, bill, 1) == (4, 4, 4)
    # 删后重新生成同样的号再交付：自检不把它当作同一枚号重复交付
    w.generate(pi, seq=(1, 6))
    w.allocate(pi)
    w.push()
    from app.services import delivery

    delivery.self_check()
    assert not sql.count(
        "SELECT COUNT(*) FROM sn_delivery_alert WHERE sn IN (%s, %s) AND status = 'OPEN'", f"{p}0001", f"{p}0006"
    )


def test_delete_does_not_void_batch_confirmed_meanwhile(w, api, sql, monkeypatch):
    """删号读出要作废的批次之后、加锁之前，这一批恰好收到 MES 回执 OK：已确认的批次不能再作废（号已是已推送）。"""
    import threading

    from app.core.security import CurrentUser
    from app.services import deletion, delivery

    org = w.org(uid("O")[:10])
    w.enable_mes(org, batch_size=3)
    _, pi, _bill = mk(w, ("M1", 3), org=org)
    p = P()
    ok(w.bind_pi(pi, w.rule(p, seq_len=4, with_length=False)))
    w.generate(pi)
    w.allocate(pi)
    w.push(receipts=False)
    b = sql.one("SELECT * FROM sn_delivery_batch WHERE pi_no = %s", pi)
    assert b["result"] == "UNCONFIRMED"
    real = deletion._summary
    calls = []

    def summary_then_receipt(tpl, args):
        out = real(tpl, args)
        calls.append(1)
        if len(calls) == 1:
            # 事务内这次读出批次后，回执在别的连接上先确认这一批
            th = threading.Thread(
                target=delivery.apply_receipt,
                args=(CurrentUser.system(), b["batch_no"], {"batchNo": b["batch_no"], "code": "OK"}),
            )
            th.start()
            th.join()
        return out

    monkeypatch.setattr(deletion, "_summary", summary_then_receipt)
    r = ok(w.delete(pi, start_sn=f"{p}0001"))
    assert r["voided"] == [] and r["rebuilt"] == []
    assert sql.scalar("SELECT result FROM sn_delivery_batch WHERE id = %s", b["id"]) == "SUCCESS"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 2


def test_regenerated_sn_rev_continues_after_deleted_rev(w, api, sql):
    """MES 已持有、转移过（rev > 1）的号删掉后重新生成同样的号：rev 接着删除前的往上加，MES 按 rev 覆盖为新挂靠，
    不会因 rev 回到 1 被 MES 当作旧数据跳过（回执照样 OK，本系统却以为 MES 已拿到新挂靠）。"""
    org = w.org(uid("O")[:10])
    w.enable_mes(org)
    _, pi, bill = mk(w, ("M1", 2), org=org)
    p = P()
    rule = w.rule(p, seq_len=4, with_length=False)
    ok(w.bind_pi(pi, rule))
    w.generate(pi)
    w.allocate(pi)
    w.push()
    # 同组织转单到另一张单据：rev 2，MES 覆盖
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 2))
    body = m(
        pi_no=pi, bill_no=bill, scope="BILL", to_pi=pi, to_bill=tgt, to_factory=org, to_material="M1", reason="转单"
    )
    ok(api.post("/api/transfers/bill", body, w.admin))
    w.push()
    sn = f"{p}0001"
    assert MES.store[org][sn]["rev"] == 2 and MES.store[org][sn]["billNo"] == tgt
    ok(w.delete(pi, bills=[tgt]))
    # 删号后目标单据有额度：重新生成同样的号（用空号段）
    ok(w.bind_pi(pi, rule))
    w.generate(pi, bills=[tgt], seq=(1, 2))
    assert sql.scalar("SELECT rev FROM sn_item WHERE sn = %s", sn) == 3
    w.allocate(pi)
    w.push()
    assert MES.store[org][sn]["rev"] == 3


def test_imported_sn_rev_continues_after_deleted_rev(w, api, sql):
    """删掉的号（MES 已持有、rev 2）再经 SN 导入进号池：rev 同样接着删前的往上加。"""
    from test_sn_import import rec, xlsx
    from test_sn_import import run as import_run

    org = w.org(uid("O")[:10])
    w.enable_mes(org)
    _, pi, bill = mk(w, ("M1", 1), org=org)
    p = P()
    ok(w.bind_pi(pi, w.rule(p, seq_len=4, with_length=False)))
    w.generate(pi)
    w.allocate(pi)
    w.push()
    tgt = uid("MO")
    w.order(tgt, org, "C1", pi, ("M1", 1))
    body = m(
        pi_no=pi, bill_no=bill, scope="BILL", to_pi=pi, to_bill=tgt, to_factory=org, to_material="M1", reason="转单"
    )
    ok(api.post("/api/transfers/bill", body, w.admin))
    w.push()
    sn = f"{p}0001"
    assert MES.store[org][sn]["rev"] == 2
    ok(w.delete(pi, bills=[tgt]))
    ok(import_run(api, xlsx(rec(pi, bill, sn, org)), w.admin))
    assert sql.scalar("SELECT rev FROM sn_item WHERE sn = %s", sn) == 3
    w.allocate(pi)
    w.push()
    # 这里只看送出的 rev
    sent = [y["rev"] for x in MES.received if x["org"] == org for y in x["batch"]["items"] if y["sn"] == sn]
    assert sent == [1, 2, 3]
