"""物料行失效（line_void）：业务状态、领料状态的同步，失效条件（全系统一套），失效行不参与排号、计数与相关操作。"""

from __future__ import annotations

from conftest import BILLS, K3_FAIL, emp, m, ok, uid
from test_sn_import import check, rec, xlsx
from test_transfers import bill_body


def P() -> str:
    return "P" + uid("").replace("-", "")


def status(bill: str, seq: int, biz: str, pick: str) -> None:
    """改假金蝶里物料行的业务状态、领料状态（不改修改日期：金蝶执行操作、领料回写都不改）。"""
    line = next(x for x in BILLS[bill]["TreeEntity"] if x["Seq"] == seq)
    line["Status"], line["PickMtrlStatus"] = biz, pick


def mk(w, *lines, statuses=(), org=None, pi=None):
    """建单据（statuses = [(序号, 业务状态, 领料状态)]，同步前写进假金蝶）并绑定规则。"""
    org = org or w.org(uid("O")[:10])
    pi = pi or uid("PI")
    bill = uid("MO")
    BILLS[bill] = w.doc(bill, org, "C1", pi, *lines)
    for seq, biz, pick in statuses:
        status(bill, seq, biz, pick)
    ok(w.api.post("/api/orders/sync", m(bill_no=bill), w.admin))
    return org, pi, bill


def snap(sql, bill):
    return [
        (r["seq"], r["biz_status"], r["pick_status"])
        for r in sql.all("SELECT seq, biz_status, pick_status FROM sn_prd_mo WHERE bill_no = %s ORDER BY seq", bill)
    ]


def set_rule(w, enabled=True, biz=("5", "6", "7"), pick=("1",), token=None):
    return w.api.put(
        "/api/generate/void-rule",
        m(enabled=enabled, biz_status=list(biz), pick_status=list(pick)),
        token or w.admin,
    )


def ctx_lines(w, pi):
    c = ok(w.api.get(f"/api/generate/context?pi={pi}", w.admin))
    return c, {(x["bill_no"], x["seq"]): x for b in c["bills"] for x in b["lines"]}


def pi_row(w, pi):
    return next(x for x in ok(w.api.get(f"/api/generate/pis?q={pi}", w.admin))["items"] if x["pi_no"] == pi)


# ---------------------------------------------------------------- 状态同步


def test_status_synced_by_bill_compare_and_rebuild(w, sql):
    """按单据同步（View 的 Status / PickMtrlStatus）、全量重建（明细查询的只显示列）取到两列；
    业务状态、领料状态变了金蝶修改日期不变，比对同步另拉状态刷新；拉状态失败不影响比对同步。"""
    org, pi, bill = mk(w, ("M1", 1), ("M2", 1), statuses=[(1, "2", "1"), (2, "5", "1")])
    assert snap(sql, bill) == [(1, "2", "1"), (2, "5", "1")]
    status(bill, 1, "4", "3")
    r = w.compare()
    assert r["replaced"] == 0, "修改日期没变，单据不整单替换"
    assert snap(sql, bill) == [(1, "4", "3"), (2, "5", "1")], "状态单独刷新"
    status(bill, 2, "6", "1")
    K3_FAIL["status"] = True
    w.compare()
    assert snap(sql, bill)[1] == (2, "5", "1"), "拉状态失败：状态停在上次的值"
    K3_FAIL["status"] = False
    ok(w.api.post("/api/orders/rebuild", {}, w.admin))
    assert snap(sql, bill) == [(1, "4", "3"), (2, "6", "1")]
    K3_FAIL["display"] = True
    w.touch(bill)
    status(bill, 1, "5", "1")
    w.compare()
    assert snap(sql, bill) == [(1, "5", "1"), (2, "6", "1")], "金蝶不认只显示列时，整单替换后状态仍由状态查询补上"


# ---------------------------------------------------------------- 失效条件


def test_void_rule_default_save_validation_and_audit(w, api, sql):
    v = ok(api.get("/api/generate/void-rule", w.admin))
    assert (v["enabled"], v["biz_status"], v["pick_status"]) == (True, ["5", "6", "7"], ["1"]), "默认"
    for body in (
        m(enabled=True, biz_status=[], pick_status=["1"]),
        m(enabled=True, biz_status=["5"], pick_status=[]),
        m(enabled=True, biz_status=["8"], pick_status=["1"]),
        m(enabled=None, biz_status=["5"], pick_status=["1"]),
    ):
        assert api.put("/api/generate/void-rule", body, w.admin).code == "LINE_VOID_RULE_INVALID"
    org = w.org(uid("O")[:10])
    op = w.user(emp("OP"), "operator", org)
    v = ok(set_rule(w, True, ("7", "5"), ("1", "2"), token=op))
    assert (v["biz_status"], v["pick_status"]) == (["5", "7"], ["1", "2"]), "普通操作员也能改；按状态码排序"
    a = sql.one("SELECT * FROM sn_audit WHERE action = 'LINE_VOID_RULE' AND result = 'OK' ORDER BY id DESC LIMIT 1")
    assert a is not None and "on biz=5,6,7 pick=1 -> on biz=5,7 pick=1,2" in a["detail"]
    auditor = w.user(emp("AU"), "auditor", org)
    assert (
        api.put("/api/generate/void-rule", m(enabled=False, biz_status=["5"], pick_status=["1"]), auditor).status == 403
    )
    ctx_rule = ok(api.get("/api/generate/void-rule", w.admin))
    assert ctx_rule["updated_by"] is not None


# ---------------------------------------------------------------- 排号与计数


def test_void_lines_excluded_from_numbering_and_counts(w, api, sql):
    """失效行不参与排号（整单勾选也不生成它）、不计订单数与可生成；关掉失效即恢复。状态未取到的行不失效。"""
    org, pi, bill = mk(
        w,
        ("M1", 2, "100", "1"),
        ("M2", 3, "100", "2"),
        ("M3", 4, "100", "3"),
        ("M4", 5, "100", "4"),
        statuses=[(1, "2", "1"), (2, "5", "1"), (3, "6", "3")],
    )
    ok(w.bind_pi(pi, w.rule(P())))
    c, ls = ctx_lines(w, pi)
    assert [ls[(bill, s)]["void"] for s in (1, 2, 3, 4)] == [False, True, False, False]
    assert ls[(bill, 2)]["quota"] == 0 and ls[(bill, 2)]["qty"] == 3
    assert (c["bills"][0]["total_qty"], c["bills"][0]["quota"]) == (11, 11)
    assert (c["summary"]["total_qty"], c["summary"]["quota"]) == (11, 11)
    assert c["void_rule"]["enabled"] is True
    row = pi_row(w, pi)
    assert (row["total_qty"], row["quota"]) == (11, 11)
    w.generate(pi, bills=[bill])
    got = {
        r["bill_seq"]: r["n"]
        for r in sql.all("SELECT bill_seq, COUNT(*) n FROM sn_item WHERE pi_no = %s GROUP BY bill_seq", pi)
    }
    assert got == {1: 2, 3: 4, 4: 5}, "失效行不生成"
    assert not sql.all("SELECT * FROM sn_pi_reserve WHERE pi_no = %s AND bill_no = %s AND seq = 2", pi, bill), (
        "第一次生成排号段预留时不给失效行留号段"
    )
    row = pi_row(w, pi)
    assert row["quota"] == 0 and row["status"] in ("TO_ALLOCATE", "DONE"), "剩下的额度只在失效行上：已生成完"
    alloc = ok(api.get(f"/api/allocation/context?pi={pi}", w.admin))
    assert alloc["summary"]["quota"] == 0
    assert ok(w.preview(pi, lines=[(bill, 2)]))["issues"][0]["issues"] == ["QUOTA_EMPTY"]
    ok(set_rule(w, False))
    _c, ls = ctx_lines(w, pi)
    assert (ls[(bill, 2)]["void"], ls[(bill, 2)]["quota"]) == (False, 3), "取消失效即生效"
    assert pi_row(w, pi)["quota"] == 3
    w.generate(pi, lines=[(bill, 2)])
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND bill_seq = 2", pi) == 3


def test_preview_then_line_turns_void_is_rejected_at_run(w, api):
    org, pi, bill = mk(w, ("M1", 2), statuses=[(1, "5", "1")])
    ok(set_rule(w, False))
    ok(w.bind_pi(pi, w.rule(P())))
    p = ok(w.preview(pi))
    assert p["generatable"]
    ok(set_rule(w, True))
    assert api.post("/api/generate/run", m(token=p["token"]), w.admin).code == "GEN_PREVIEW_CHANGED"


# ---------------------------------------------------------------- 失效行不能操作


def test_void_line_config_operations_rejected(w, api):
    org, pi, bill = mk(w, ("M1", 2, " ", " ", "条"), ("M2", 1), statuses=[(1, "7", "1")])
    rid = w.rule(P())
    r = w.select(pi, [(bill, 2), (bill, 1)], rule_id=rid)
    assert (r.code, r.body["params"]) == ("LINE_VOID", {"bill_no": bill, "seq": 1}), str(r)
    ok(w.select(pi, [(bill, 2)], rule_id=rid))
    body = m(pi_no=pi, lines=[m(bill_no=bill, seq=1)])
    assert api.post("/api/rules/line-clear", body, w.admin).code == "LINE_VOID"
    assert api.post("/api/generate/revoke-config", body, w.admin).code == "LINE_VOID"
    for path, value in (("attach", "9"), ("length", "50"), ("unit", "M")):
        assert api.put(f"/api/orders/{path}", m(bill_no=bill, seq=1, value=value), w.admin).code == "LINE_VOID", path
    ok(api.put("/api/orders/attach", m(bill_no=bill, seq=2, value="9"), w.admin))
    ok(set_rule(w, False))
    ok(api.put("/api/orders/attach", m(bill_no=bill, seq=1, value="9"), w.admin))


def test_existing_sns_on_line_that_turns_void_still_handled(w, api, sql):
    """已有号的行失效后：号照常分配、删号；不能再生成；已生成照算。"""
    org, pi, bill = mk(w, ("M1", 3), statuses=[(1, "4", "2")])
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    status(bill, 1, "6", "1")
    w.compare()
    _c, ls = ctx_lines(w, pi)
    assert (ls[(bill, 1)]["void"], ls[(bill, 1)]["generated_qty"], ls[(bill, 1)]["quota"]) == (True, 3, 0)
    assert pi_row(w, pi)["generated_qty"] == 3
    sns = [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    ok(w.delete(pi, start_sn=sns[0]))
    w.allocate(pi)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'ALLOCATED'", pi) == 2
    _c, ls = ctx_lines(w, pi)
    assert ls[(bill, 1)]["quota"] == 0, "删号还回的额度在失效行上也不算"


def test_transfer_into_void_line_rejected_but_org_transfer_in_place_allowed(w, api, sql):
    a = w.org(uid("O")[:10])
    b = w.org(uid("O")[:10])
    _o, pi, bill = mk(w, ("M1", 2), org=a)
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    _o, _pi, tgt = mk(w, ("M1", 2), statuses=[(1, "5", "1")], org=b, pi=pi)
    r = api.post("/api/transfers/bill", bill_body(pi, bill, tgt, b), w.admin)
    assert (r.code, r.body["params"]) == ("TR_TARGET_LINE_VOID", {"bill_no": tgt, "seq": 1}), str(r)
    r = api.post("/api/transfers/bill", bill_body(pi, bill, tgt, b, to_seq=1), w.admin)
    assert r.code == "TR_TARGET_LINE_VOID"
    # 来源行失效：同一单据内转组织（号留在原行）照常
    status(bill, 1, "7", "1")
    w.compare()
    ok(
        api.post(
            "/api/transfers/org/direct",
            m(
                factory_code=a,
                pi_no=pi,
                bill_no=bill,
                scope="BILL",
                to_factory=b,
                to_pi=pi,
                to_bill=bill,
                to_material="M1",
                reason="调拨",
            ),
            w.admin,
        )
    )
    assert {r["factory_code"] for r in sql.all("SELECT factory_code FROM sn_item WHERE pi_no = %s", pi)} == {b}


def test_import_onto_void_line_rejected(w, api):
    org, pi, bill = mk(w, ("M1", 2), statuses=[(1, "5", "1")])
    r = ok(check(api, xlsx(rec(pi, bill, uid("S"), org)), w.admin))
    assert not r["importable"]
    assert [i["code"] for i in r["issues"][0]["issues"]] == ["LINE_VOID"]
    ok(set_rule(w, False))
    assert ok(check(api, xlsx(rec(pi, bill, uid("S"), org)), w.admin))["importable"]
