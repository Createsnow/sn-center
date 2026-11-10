"""生成页自定义规则的「流水上限自动突破」开关（F-08 / F-09）：
开启时流水用完位数后自动加位（2 位用到 99 后 100 起为 3 位），每个位数各是一个规则版本（同前缀、同格式），
号挂在自己位数的版本上；关闭（不传）时保持原逻辑（用完报规则位数已用尽）。"""

from __future__ import annotations

import json

from conftest import MES, m, ok, q, uid


def mk(w, *lines, org=None, mes=False):
    org = org or w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org, batch_size=500)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, *lines)
    return org, pi, bill


def P() -> str:
    return "P" + uid("").replace("-", "")


def sns(sql, pi):
    return [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]


def test_grow_crosses_width_and_each_width_is_a_version(w, api, sql):
    """开启：2 位流水用到 99 后接 3 位 100 起；预演按位数分段（各段带位数），号挂在自己位数的版本上；
    突破版本同前缀、归属本 PI、derived_from 为原版本，不改规则的当前版本；开关记在物料行上，生成后照旧保留。"""
    org, pi, bill = mk(w, ("M1", 120), ("M2", 5))
    p = P()
    c = m(prefix=p, base=10, seq_len=2, with_length=False, seq_grow=True)
    ok(w.select(pi, [(bill, 1)], custom=c))
    assert json.loads(sql.scalar("SELECT custom_json FROM sn_line_rule WHERE bill_no = %s AND seq = 1", bill))[
        "seq_grow"
    ]
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    rule1 = next(x for b in ctx["bills"] for x in b["lines"] if x["seq"] == 1)["rule"]
    assert rule1["seq_grow"] is True and rule1["max_seq"] == 10**12 - 1
    pv = ok(w.preview(pi, lines=[(bill, 1)]))
    item = pv["items"][0]
    assert item["seq_grow"] is True and item["seq_len"] == 2
    assert [
        (g["start_seq"], g["end_seq"], g["seq_len"], g["start_seq_text"], g["end_seq_text"]) for g in item["seg_list"]
    ] == [
        (1, 99, 2, "01", "99"),
        (100, 120, 3, "100", "120"),
    ]
    assert (item["start_sn"], item["end_sn"]) == (f"{p}01", f"{p}120")
    w.generate(pi, lines=[(bill, 1)])
    got = sns(sql, pi)
    assert got[:2] == [f"{p}01", f"{p}02"] and got[98:101] == [f"{p}99", f"{p}100", f"{p}101"] and got[-1] == f"{p}120"
    rid = sql.scalar("SELECT id FROM sn_rule WHERE rule_code = %s", f"PI-{pi}-1")
    vs = sql.all("SELECT * FROM sn_rule_version WHERE rule_id = %s ORDER BY version", rid)
    assert [(v["version"], v["prefix"], v["seq_len"], v["claimed_pi"], v["used"]) for v in vs] == [
        (1, p, 2, pi, 1),
        (2, p, 3, pi, 1),
    ]
    assert vs[1]["derived_from"] == vs[0]["id"]
    assert sql.scalar("SELECT current_version FROM sn_rule WHERE id = %s", rid) == 1, "不改规则的当前版本"
    per_ver = sql.all(
        "SELECT rule_version_id v, COUNT(*) n, MIN(seq_dec) a, MAX(seq_dec) b FROM sn_item WHERE pi_no = %s "
        "GROUP BY rule_version_id ORDER BY a",
        pi,
    )
    assert [(r["v"], r["n"], r["a"], r["b"]) for r in per_ver] == [
        (vs[0]["id"], 99, 1, 99),
        (vs[1]["id"], 21, 100, 120),
    ]
    row = sql.one("SELECT rule_id, custom_json FROM sn_line_rule WHERE bill_no = %s AND seq = 1", bill)
    assert (row["rule_id"], json.loads(row["custom_json"])) == (rid, {"seq_grow": True}), "开关生成后照旧保留"
    # 生成页：物料行现有号的开始 / 结束 SN 按位数比（…120 在 …99 之后）；默认规则仍是原版本的 2 位
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    line1 = next(x for b in ctx["bills"] for x in b["lines"] if x["seq"] == 1)
    assert (line1["start_sn"], line1["end_sn"]) == (f"{p}01", f"{p}120")
    assert line1["rule"]["seq_grow"] is True and line1["rule"]["version"] == 1
    assert ctx["rule_prefix"]["prefix"] == p and ctx["rule_prefix"]["custom"]["seq_len"] == 2
    # 本 PI 另一物料行沿用这个前缀（同格式、开启）：接着 121 起，沿用已有的 3 位版本，不另出版本
    ok(w.select(pi, [(bill, 2)], custom=c))
    row = sql.one("SELECT rule_id, custom_json FROM sn_line_rule WHERE bill_no = %s AND seq = 2", bill)
    assert (row["rule_id"], json.loads(row["custom_json"])) == (rid, {"seq_grow": True})
    w.generate(pi, lines=[(bill, 2)])
    assert sns(sql, pi)[-5:] == [f"{p}{i}" for i in range(121, 126)]
    assert sql.count("SELECT COUNT(*) FROM sn_rule_version WHERE rule_id = %s", rid) == 2
    # 同一前缀下仍按原版本的流水格式锁定：2 位可沿用，改成 3 位须换前缀
    _, pi2, bill2 = mk(w, ("M1", 1), org=org)
    assert w.select(pi2, [(bill2, 1)], custom=c).code == "RULE_PREFIX_CONFLICT", "别的 PI 不能用这个前缀"
    assert w.select(pi, [(bill, 1)], custom={**c, "seq_len": 3}).code == "RULE_PREFIX_FORMAT_LOCKED"
    # 规则库：突破版本列在版本里，当前版本仍是原版本
    view = next(r for r in ok(api.get(f"/api/rules?q={pi}", w.admin)).body if r["rule"]["id"] == rid)
    assert view["current"]["seq_len"] == 2 and sorted(v["seq_len"] for v in view["versions"]) == [2, 3]


def test_grow_off_keeps_original_logic(w, api, sql):
    """关闭（不传或 false）：位数用尽照旧报规则位数已用尽；物料行存的自定义规则与原来相同（不带 seq_grow）。"""
    org, pi, bill = mk(w, ("M1", 100))
    p = P()
    c = m(prefix=p, base=10, seq_len=2, with_length=False)
    for body in (c, {**c, "seq_grow": False}):
        ok(w.select(pi, [(bill, 1)], custom=body))
        assert "seq_grow" not in json.loads(
            sql.scalar("SELECT custom_json FROM sn_line_rule WHERE bill_no = %s AND seq = 1", bill)
        )
        r = w.preview(pi)
        assert r.code == "GEN_RULE_EXHAUSTED" and r.body["params"] == {"max": 99, "end": 100}
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    rule = ctx["bills"][0]["lines"][0]["rule"]
    assert rule["seq_grow"] is False and rule["max_seq"] == 99
    assert w.select(pi, [(bill, 1)], custom={**c, "seq_start": 100}).code == "RULE_SEQ_START_INVALID"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0


def test_grow_preview_samples_and_seq_start(w, api, sql):
    """示例：开启时最大号之后另带突破后的第一个号；起始号可超过规则位数（按突破后的位数）。"""
    c = m(prefix=P(), base=10, seq_len=2, with_length=False)
    r = ok(api.post("/api/rules/preview", c, w.admin))
    assert [x["seq_dec"] for x in r["samples"]] == [1, 2, 3, 4, 5, 99], "关闭：与原来相同"
    r = ok(api.post("/api/rules/preview", {**c, "seq_grow": True}, w.admin))
    assert [(x["seq_dec"], x["seq_text"]) for x in r["samples"]][-2:] == [(99, "99"), (100, "100")]
    assert r["max_seq"] == 99
    r = ok(api.post("/api/rules/preview", {**c, "seq_grow": True, "seq_start": 150}, w.admin))
    assert [x["seq_text"] for x in r["samples"]] == ["150", "151", "152", "153", "154"]
    assert api.post("/api/rules/preview", {**c, "seq_start": 150}, w.admin).code == "RULE_SEQ_START_INVALID"
    # 起始号超过规则位数：首次生成直接从 3 位起
    org, pi, bill = mk(w, ("M1", 3))
    ok(w.select(pi, [(bill, 1)], custom={**c, "seq_grow": True, "seq_start": 150}))
    rule = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))["bills"][0]["lines"][0]["rule"]
    assert rule["first_sn"] == f"{c['prefix']}150"
    w.generate(pi)
    assert sns(sql, pi) == [f"{c['prefix']}{i}" for i in (150, 151, 152)]
    assert (
        sql.count(
            "SELECT COUNT(*) FROM sn_item i JOIN sn_rule_version v ON v.id = i.rule_version_id "
            "WHERE i.pi_no = %s AND v.seq_len = 3",
            pi,
        )
        == 3
    )


def test_grow_with_length_unit_and_free_range(w, api, sql):
    """带米数、单位：突破后流水变长、米数单位照常接在后面；手动起止号用空号段时，2 位范围内的空号仍按 2 位编。"""
    org, pi, bill = mk(w, ("M1", 3, "100", "1", "M"), ("M2", 4, "100", "2", "M"))
    p = P()
    c = m(prefix=p, base=10, seq_len=2, with_length=True, sep_before="-", with_unit=True, seq_grow=True)
    ok(w.select(pi, [(bill, 1), (bill, 2)], custom={**c, "seq_start": 98}))
    w.generate(pi, lines=[(bill, 1)])
    assert sns(sql, pi) == [f"{p}98-100M", f"{p}99-100M", f"{p}100-100M"]
    # 空号段 1..97：手动起止号 96 ~ 101 用 96、97（2 位），不够从最大号之后接着编（101、102，3 位）
    pv = ok(w.preview(pi, lines=[(bill, 2)], seq=(96, 97)))
    assert [(g["start_sn"], g["end_sn"], g["seq_len"]) for g in pv["items"][0]["seg_list"]] == [
        (f"{p}96-100M", f"{p}97-100M", 2),
        (f"{p}101-100M", f"{p}102-100M", 3),
    ]
    w.generate(pi, lines=[(bill, 2)], seq=(96, 97))
    # 按 SN 区间查询：跨位数的起止号按号池精确取流水
    got = ok(api.get("/api/sn" + q(pi=pi, seq_from=f"{p}97-100M", seq_to=f"{p}101-100M"), w.admin))
    assert sorted(x["sn"] for x in got["items"]) == sorted(
        [f"{p}97-100M", f"{p}98-100M", f"{p}99-100M", f"{p}100-100M", f"{p}101-100M"]
    )
    # 删号按 SN 起止号（同一物料行）跨位数
    r = ok(w.delete(pi, start_sn=f"{p}99-100M", end_sn=f"{p}100-100M", reason="t"))
    assert r["qty"] == 2
    assert sns(sql, pi) == [f"{p}96-100M", f"{p}97-100M", f"{p}98-100M", f"{p}101-100M", f"{p}102-100M"]
    rec = ok(w.api.get(f"/api/generate/deleted?pi={pi}", w.admin)).body[0]
    assert (rec["start_sn"], rec["end_sn"]) == (f"{p}99-100M", f"{p}100-100M"), "删号记录的起止号按位数比"


def test_grow_delivery_serial_and_export(w, api, sql):
    """推送给 MES：突破后的号 serial 为实际位数（字段、结构不变）；已分配明细的流水位数取号所在版本。"""
    org, pi, bill = mk(w, ("M1", 101, "0100"), mes=True)
    p = P()
    ok(w.select(pi, [(bill, 1)], custom=m(prefix=p, base=10, seq_len=2, with_length=False, seq_grow=True)))
    w.generate(pi)
    w.allocate(pi)
    w.push()
    items = [x for r in MES.received if r["batch"].get("pi") == pi for x in r["batch"]["items"]]
    by_sn = {x["sn"]: x for x in items}
    assert (by_sn[f"{p}99"]["serial"], by_sn[f"{p}99"]["serialDec"]) == ("99", "99")
    assert (by_sn[f"{p}100"]["serial"], by_sn[f"{p}100"]["serialDec"]) == ("100", "100")
    assert set(by_sn[f"{p}100"]) == set(by_sn[f"{p}99"]), "明细字段不变"
    got = ok(api.get(f"/api/export/items?pi={pi}&bill_no={bill}&page_size=200", w.admin))["items"]
    rows = {x["sn"]: (x["seq_len"], x["seq_text"]) for x in got}
    assert rows[f"{p}99"] == (2, "99") and rows[f"{p}100"] == (3, "100")


def test_locked_prefix_format_after_all_sns_deleted(w, api, sql):
    """号全部删掉后前缀仍归属本 PI、进制和流水位数仍锁定：生成页上下文 prefix_locked 给出锁定的格式
    （rule_prefix 不变，custom 回到 None）；按默认 4 位保存报 RULE_PREFIX_FORMAT_LOCKED，
    按锁定的 5 位（开启上限自动突破）可保存、流水接续。"""
    org, pi, bill = mk(w, ("M1", 3))
    p = bill[-7:]
    ok(w.select(pi, [(bill, 1)], custom=m(prefix=p, base=10, seq_len=5, with_length=False)))
    w.generate(pi)
    ok(w.delete(pi, bills=[bill], reason="t"))
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    assert ctx["rule_prefix"] == {"prefix": p, "custom": None}
    assert ctx["prefix_locked"][p]["seq_len"] == 5 and ctx["prefix_locked"][p]["base"] == 10
    c = m(prefix=p, base=10, seq_len=4, with_length=False, seq_grow=True)
    assert w.select(pi, [(bill, 1)], custom=c).code == "RULE_PREFIX_FORMAT_LOCKED"
    ok(w.select(pi, [(bill, 1)], custom={**ctx["prefix_locked"][p], "seq_grow": True}))
    w.generate(pi)
    assert sns(sql, pi) == [f"{p}{i:05d}" for i in (4, 5, 6)], "最大流水号不退回，接着编"
    # 上限自动突破另出的版本不列为锁定格式（锁定的仍是原版本的位数）
    _, pi2, bill2 = mk(w, ("M1", 101), org=org)
    p2 = P()
    ok(w.select(pi2, [(bill2, 1)], custom=m(prefix=p2, base=10, seq_len=2, with_length=False, seq_grow=True)))
    w.generate(pi2)
    locked = ok(api.get(f"/api/generate/context?pi={pi2}", w.admin))["prefix_locked"]
    assert list(locked) == [p2] and locked[p2]["seq_len"] == 2
