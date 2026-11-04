"""规则库与规则选取（F-08）、SN 生成（F-09）、生成页与分配页（F-10）。"""

from __future__ import annotations

from conftest import BILLS, m, ok, q, uid, xlsx_rows


def mk(w, *lines, pi=None, org=None, bill=None, customer="C1"):
    org = org or w.org(uid("O")[:10])
    pi = pi or uid("PI")
    bill = bill or uid("MO")
    w.order(bill, org, customer, pi, *lines)
    return org, pi, bill


def P() -> str:
    return "P" + uid("").replace("-", "")


# ---------------------------------------------------------------- 规则库


def test_prefix_any_characters_end_to_end(w, api, sql):
    """规则不限制字符：中文、小写、符号的前缀与后缀在生成、推送 MES、按 SN 查询、导出都原样。"""
    from conftest import MES

    base = m(rule_code=uid("R"), rule_name="x", category_value=uid("PI"), base=10, seq_len=4, with_length=False)
    prefix = "光缆s." + uid("").replace("-", "")[-6:] + "/#"
    org, pi, bill = mk(w, ("M1", 3))
    w.enable_mes(org)
    rid = ok(api.post("/api/rules", {**base, "prefix": prefix, "suffix": "尾-x"}, w.admin))["rule"]["id"]
    ok(w.bind_pi(pi, rid))
    w.generate(pi)
    w.allocate(pi)
    w.push()
    sns = [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert sns == [f"{prefix}0001尾-x", f"{prefix}0002尾-x", f"{prefix}0003尾-x"]
    assert set(MES.store[org]) >= set(sns), "MES 收到的 SN 原样"
    assert [x["sn"] for x in ok(api.get("/api/sn" + q(sn=sns[0]), w.admin))["items"]] == [sns[0]]
    r = api.client.get(f"/api/export/file?pi={pi}&bill_no={bill}", headers={"Authorization": f"Bearer {w.admin}"})
    rows = xlsx_rows(r.content)
    assert rows[1][rows[0].index("完整 SN")] == sns[0]


def test_sn_layout_serial_separators_padded_length_unit(w, api, sql):
    """SN = 前缀 + 流水（补 0）+ 米数前分隔符 + 米数（补 0）+ 米数后分隔符 + 单位 + 后缀；单位取物料行的产品单位
    （金蝶单位是中文时取页面填写的不含中文的单位）。"""
    org, pi, bill = mk(w, ("M1", 2, "51.5", "1", "条"), ("M2", 1, "1200", "2", "PCS"))
    p = P()
    rid = w.rule(p, seq_len=5, suffix="Z", length_pad=5, sep_before="-", sep_after="M", with_unit=True)
    r = ok(api.get(f"/api/rules?q={p}", w.admin)).body[0]["current"]
    assert r["format"] == f"{p} + 5 位 10 进制流水 + - + 米数（补足 5 位） + M + 单位 + Z"
    assert (r["length_pad"], r["length_sep_before"], r["length_sep_after"], r["with_unit"]) == (5, "-", "M", True)
    sample = ok(
        api.post(
            "/api/rules/preview",
            m(
                prefix=p,
                base=10,
                seq_len=5,
                length_pad=5,
                sep_before="-",
                sep_after="M",
                with_unit=True,
                suffix="Z",
                length="7",
                unit="KG",
            ),
            w.admin,
        )
    )
    assert sample["samples"][0]["sn"] == f"{p}00001-00007MKGZ"
    ok(w.bind_pi(pi, rid))
    ok(api.put("/api/orders/unit", m(bill_no=bill, seq=1, value="m"), w.admin))
    pv = ok(w.preview(pi))
    assert [
        (x["material_code"], x["prefix"], x["base"], x["seq_len"], x["start_seq_text"], x["end_seq_text"], x["unit"])
        for x in pv["items"]
    ] == [("M1", p, 10, 5, "00001", "00002", "m"), ("M2", p, 10, 5, "00003", "00003", "PCS")], "确认生成弹窗的列"
    w.generate(pi)
    sns = [x["sn"] for x in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert sns == [f"{p}00001-051.5MmZ", f"{p}00002-051.5MmZ", f"{p}00003-01200MPCSZ"]
    # 已分配明细与下载：产品单位按规则从 SN 截出，带流水位数
    w.allocate(pi)
    got = ok(api.get(f"/api/export/items?pi={pi}&bill_no={bill}", w.admin))["items"]
    assert [(x["unit"], x["seq_len"], x["seq_text"], x["length"]) for x in got] == [
        ("m", 5, "00001", "51.5"),
        ("m", 5, "00002", "51.5"),
        ("PCS", 5, "00003", "1200"),
    ]
    assert [
        r["length_text"] for r in sql.all("SELECT length_text FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)
    ] == [
        "51.5",
        "51.5",
        "1200",
    ], "号上记米数原值（未补 0）"
    got = ok(api.get("/api/sn" + q(pi=pi, seq_from=sns[0], seq_to=sns[-1]), w.admin))
    assert got["total"] == 3, "完整 SN 可反解出流水"


def test_unit_uses_k3_value_and_must_be_filled_only_when_empty(w, api, sql):
    """规则带单位：产品单位用金蝶原值（含中文（条、个等）也照用）；金蝶为空时须在页面填写才能生成；
    金蝶有值时也可选改（填的值不能含中文），留空或填回金蝶值即改回金蝶值；规则不带单位时为空也不挡。"""
    org, pi, bill = mk(w, ("M1", 2, "100", "1", " "), ("M2", 1, "100", "2", "条"), ("M3", 1, "100", "3", "PCS"))
    p = P()
    ok(w.bind_pi(pi, w.rule(p, with_unit=True)))
    pv = ok(w.preview(pi))
    assert not pv["generatable"]
    assert {x["seq"]: x["issues"] for x in pv["issues"]} == {1: ["UNIT_EMPTY"]}, "只有金蝶为空的须填写"
    line = next(x for x in ok(api.get(f"/api/orders/{bill}/lines", w.admin))["lines"] if x["seq"] == 2)
    assert (line["unit"], line["unit_source"], line["unit_raw"], line["unit_manual"]) == ("条", "K3", "条", None)
    assert api.put("/api/orders/unit", m(bill_no=bill, seq=1, value="米"), w.admin).code == "UNIT_FILL_CHINESE"
    assert api.put("/api/orders/unit", m(bill_no=bill, seq=1, value="x" * 65), w.admin).code == "UNIT_FILL_INVALID"
    r = ok(api.put("/api/orders/unit", m(bill_no=bill, seq=1, value=" M "), w.admin))
    assert (r["unit"], r["unit_source"], r["unit_manual"]) == ("M", "MANUAL", "M")
    r = ok(api.put("/api/orders/unit", m(bill_no=bill, seq=1, value=""), w.admin))
    assert (r["unit"], r["unit_source"]) == ("", "EMPTY"), "留空清除填写，又须填写"
    ok(api.put("/api/orders/unit", m(bill_no=bill, seq=1, value="M"), w.admin))
    r = ok(api.put("/api/orders/unit", m(bill_no=bill, seq=3, value="L"), w.admin))
    assert (r["unit"], r["unit_source"], r["unit_manual"]) == ("L", "MANUAL", "L"), "金蝶有值也可改"
    r = ok(api.put("/api/orders/unit", m(bill_no=bill, seq=3, value="PCS"), w.admin))
    assert (r["unit"], r["unit_source"], r["unit_manual"]) == ("PCS", "K3", None), "填回金蝶值即改回金蝶值"
    ok(api.put("/api/orders/unit", m(bill_no=bill, seq=3, value="L"), w.admin))
    audited = "SELECT COUNT(*) FROM sn_audit WHERE action = 'ORDER_UNIT_FILL' AND result = 'OK' AND bill_no = %s"
    assert sql.count(audited, bill) == 6
    w.generate(pi)
    sns = [x["sn"] for x in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert [s[len(p) :] for s in sns] == ["000001100M", "000002100M", "000003100条", "000004100L"]
    listed = ok(api.get(f"/api/sn?pi={pi}&bill_no={bill}", w.admin))["items"]
    assert sorted(x["unit"] for x in listed) == sorted(["M", "M", "条", "L"]), "SN 查询按规则从 SN 截出产品单位"
    assert api.put("/api/orders/unit", m(bill_no=bill, seq=3, value="K"), w.admin).code == "LINE_EDIT_GENERATED"

    _, pi2, _ = mk(w, ("M1", 1, "100", "1", " "))
    ok(w.bind_pi(pi2, w.rule(P())))
    assert ok(w.preview(pi2))["generatable"] is True, "规则不带单位时，单位为空不挡生成"


def test_length_uses_k3_value_and_must_be_filled_only_when_empty(w, api, sql):
    """米数用金蝶原值（含中文也照用）；金蝶为空时规则带米数须在页面填写才能生成；金蝶有值时也可选改
    （填的值不能含中文），留空或填回金蝶值即改回金蝶值；规则不带米数时为空也不挡。"""
    org, pi, bill = mk(w, ("M1", 2, "五十米"), ("M2", 1, " "), ("M3", 1, "60"))
    ok(w.bind_pi(pi, w.rule(P())))
    p = ok(w.preview(pi))
    assert not p["generatable"]
    assert {x["seq"]: x["issues"] for x in p["issues"]} == {2: ["LENGTH_EMPTY"]}, "只有金蝶为空的须填写"
    line = {x["seq"]: x for x in ok(api.get(f"/api/orders/{bill}/lines", w.admin))["lines"]}
    assert (line[1]["length"], line[1]["length_source"]) == ("五十米", "K3")
    assert (line[2]["length"], line[2]["length_source"]) == ("", "EMPTY")
    assert api.put("/api/orders/length", m(bill_no=bill, seq=2, value="五十"), w.admin).code == "LENGTH_FILL_CHINESE"
    r = ok(api.put("/api/orders/length", m(bill_no=bill, seq=3, value="61"), w.admin))
    assert (r["length"], r["length_source"]) == ("61", "MANUAL"), "金蝶有值也可改"
    r = ok(api.put("/api/orders/length", m(bill_no=bill, seq=3, value=" 60 "), w.admin))
    assert (r["length"], r["length_source"], r["length_manual"]) == ("60", "K3", None), "填回金蝶值即改回金蝶值"
    r = ok(api.put("/api/orders/length", m(bill_no=bill, seq=2, value="50"), w.admin))
    assert (r["length"], r["length_source"]) == ("50", "MANUAL")
    w.generate(pi)
    assert [
        r["length_text"] for r in sql.all("SELECT length_text FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)
    ] == ["五十米", "五十米", "50", "60"], "金蝶米数原样写入"

    _, pi2, _ = mk(w, ("M1", 1, " "))
    ok(w.bind_pi(pi2, w.rule(P(), with_length=False)))
    assert ok(w.preview(pi2))["generatable"], "规则不带米数时米数为空不影响生成"


def test_prefix_conflicts_identical_or_one_starts_another(w, api):
    """不同 PI（或尚未归属）的前缀不能相同，也不能一个是另一个的开头（如 SD 与 SDD 可能拼出相同的号）；区分大小写。"""
    stem = "AX" + uid("").replace("-", "")
    w.rule(stem)

    def new(prefix):
        return api.post(
            "/api/rules",
            m(rule_code=uid("R"), rule_name="x", category_value=uid("PI"), prefix=prefix, base=10, seq_len=5),
            w.admin,
        )

    assert new(stem).code == "RULE_PREFIX_CONFLICT"
    for p in (stem + "B", stem[:-1]):
        assert new(p).code == "RULE_PREFIX_OVERLAP", f"{p}：一个是另一个的开头"
    assert new(stem.lower()).status == 200, "前缀区分大小写"
    assert new("BX" + stem[2:]).status == 200


def test_rule_binds_pi_only(w, api):
    """新建规则须填 PI，规则只绑定 PI；修改时不传分类与 PI 则不变，传了也只能是 PI。"""
    prefix = P()
    body = m(rule_code=uid("R"), rule_name="x", prefix=prefix, base=10, seq_len=5)
    assert api.post("/api/rules", body, w.admin).code == "RULE_CATEGORY_REQUIRED", "PI 必填"
    assert api.post("/api/rules", body | m(category_value="  "), w.admin).code == "RULE_CATEGORY_REQUIRED"
    assert api.post("/api/rules", body | m(category_scope="CUSTOMER", category_value="C1"), w.admin).code == (
        "RULE_CATEGORY_INVALID"
    ), "不能按客户分类"
    assert api.post("/api/rules", body | m(category_scope="NONE"), w.admin).code == "RULE_CATEGORY_INVALID"
    pi = uid("PI")
    r = ok(api.post("/api/rules", body | m(category_value=pi), w.admin))["rule"]
    assert (r["category_scope"], r["category_value"]) == ("PI", pi), "分类不填按 PI"
    spec = m(rule_name="x", prefix=prefix, base=10, seq_len=5)
    ok(api.put(f"/api/rules/{r['id']}", spec, w.admin))
    rows = [x["rule"] for x in ok(api.get(f"/api/rules?q={pi}", w.admin)).body]
    assert [(x["id"], x["category_value"]) for x in rows] == [(r["id"], pi)], "不传分类与 PI 则不变"
    assert api.put(f"/api/rules/{r['id']}", spec | m(category_value=""), w.admin).code == "RULE_CATEGORY_REQUIRED"
    pi2 = uid("PI")
    assert ok(api.put(f"/api/rules/{r['id']}", spec | m(category_value=pi2), w.admin))["action"] == "RECATEGORIZED"


def test_rule_list_and_options_show_versions_pis_and_bindings(w, api):
    """规则库列表与生成页选项：各版本（新的在前）、用过的 PI、PI 绑定；本 PI 用哪一版。"""
    _, pi1, _ = mk(w, ("M1", 2))
    _, pi2, _ = mk(w, ("M1", 2))
    pi3 = uid("PI")
    rid = w.rule(P())
    ok(w.bind_pi(pi1, rid))
    w.generate(pi1)
    assert w.bind_pi(pi2, rid).code == "RULE_PREFIX_CHANGE_REQUIRED"
    ok(w.bind_pi(pi2, rid, new_prefix=P()))
    listed = [x for x in ok(api.get("/api/rules", w.admin)).body if x["rule"]["id"] == rid]
    assert len(listed) == 1
    v = listed[0]
    code = v["rule"]["rule_code"]
    assert [x["version"] for x in v["versions"]] == [2, 1] and v["current"]["version"] == 1
    assert v["used"] and v["pis"] == sorted([pi1, pi2])
    assert v["bindings"] == {"pi": sorted([pi1, pi2])}
    assert [x["rule"]["id"] for x in ok(api.get(f"/api/rules?q={pi2}", w.admin)).body] == [rid], "可按归属 PI 搜索"

    def option(pi: str) -> dict:
        return next(x for x in ok(api.get(f"/api/rules/options?pi={pi}", w.admin)).body if x["rule_code"] == code)

    assert (option(pi1)["plan"], option(pi1)["used_pis"]) == ("USE", 2)
    assert option(pi2)["plan"] == "USE", "已为本 PI 换前缀另出的版本"
    assert (option(pi3)["plan"], option(pi3)["other_pi"]) == ("PREFIX", pi1)


def test_used_version_format_change_keeps_prefix_unless_serial_changes(w, api, sql):
    """生成过号的版本：只改米数、单位、后缀，另出一版沿用同一前缀（仍归属原 PI，流水接续）；
    改进制、流水位数或字符表须换新前缀。"""
    org, pi, bill = mk(w, ("M1", 4, "100"))
    _, _, bill2 = mk(w, ("M1", 2, "200"), pi=pi, org=org)
    rid = w.rule(P(), seq_len=5)
    ok(w.bind_pi(pi, rid))
    w.generate(pi, bills=[bill])
    cur = sql.one("SELECT * FROM sn_rule_version WHERE rule_id = %s", rid)
    base = m(rule_name="x", prefix=cur["prefix"], base=10, seq_len=5)
    assert api.put(f"/api/rules/{rid}", base | m(base=16), w.admin).code == "RULE_PREFIX_FORMAT_LOCKED"
    assert api.put(f"/api/rules/{rid}", base | m(seq_len=6), w.admin).code == "RULE_PREFIX_FORMAT_LOCKED"
    r = ok(api.put(f"/api/rules/{rid}", base | m(with_length=True, sep_before="-", suffix="Z"), w.admin))
    assert r["action"] == "NEW_VERSION" and r["rule"]["rule"]["current_version"] == 2
    v2 = r["rule"]["current"]
    assert v2["prefix"] == cur["prefix"] and v2["claimed_pi"] == pi, "同一前缀仍归属原 PI"
    w.generate(pi, bills=[bill2])
    sns = [x["sn"] for x in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    p = cur["prefix"]
    assert sns == [f"{p}{i:05d}100" for i in range(1, 5)] + [f"{p}{i:05d}-200Z" for i in (5, 6)], "流水接续"
    r = ok(api.put(f"/api/rules/{rid}", base | m(prefix=P(), base=16), w.admin))
    assert r["action"] == "NEW_VERSION" and r["rule"]["rule"]["current_version"] == 3
    assert r["rule"]["current"]["claimed_pi"] is None, "换新前缀的版本首次生成时归属"
    assert (
        ok(
            api.put(
                f"/api/rules/{rid}",
                {**base, "prefix": r["rule"]["current"]["prefix"], "base": 16, "rule_name": "y"},
                w.admin,
            )
        )["action"]
        == "RENAMED"
    )
    assert api.delete(f"/api/rules/{rid}", w.admin).code == "RULE_IN_USE"


def test_other_pi_must_change_prefix(w, sql):
    org, p1, b1 = mk(w, ("M1", 10))
    _, p2, b2 = mk(w, ("M1", 10), org=org)
    rid = w.rule("HX" + uid("").replace("-", "")[-4:], seq_len=6)
    ok(w.bind_pi(p1, rid))
    w.generate(p1)
    r = w.bind_pi(p2, rid)
    assert r.code == "RULE_PREFIX_CHANGE_REQUIRED"
    newp = "HY" + uid("").replace("-", "")[-4:]
    ok(w.bind_pi(p2, rid, newp))
    w.generate(p2)
    assert sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s", p2).startswith(newp)
    v = sql.one("SELECT * FROM sn_rule_version WHERE prefix = %s", newp)
    assert v["claimed_pi"] == p2 and v["derived_from"] is not None


def test_lines_with_different_rules_share_one_serial(w, sql):
    org, pi, bill = mk(w, ("M1", 100), ("M2", 50))
    ra, rb = w.rule(P()), w.rule(P())
    ok(w.select(pi, [(bill, 1)], rule_id=ra))
    ok(w.select(pi, [(bill, 2)], rule_id=rb))
    w.generate(pi)
    rows = sql.all(
        "SELECT bill_seq, MIN(seq_dec) a, MAX(seq_dec) b, COUNT(DISTINCT rule_version_id) v FROM sn_item "
        "WHERE pi_no = %s GROUP BY bill_seq ORDER BY bill_seq",
        pi,
    )
    assert [(r["a"], r["b"]) for r in rows] == [(1, 100), (101, 150)]
    assert all(r["v"] == 1 for r in rows)


def test_custom_rule_saved_only_after_success(w, sql):
    org, pi, bill = mk(w, ("9148363", 3, "10.50"))
    p = P()
    ok(w.select(pi, [(bill, 1)], custom=m(prefix=p, base=10, seq_len=6, with_length=True)))
    assert sql.count("SELECT COUNT(*) FROM sn_rule") == 0, "生成前不留规则"
    w.generate(pi)
    r = sql.one("SELECT * FROM sn_rule")
    assert r["rule_name"] == f"{pi}_v1" and r["rule_code"] == f"PI-{pi}-1"
    assert sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi) == f"{p}00000110.50"
    assert sql.scalar("SELECT rule_id FROM sn_line_rule WHERE bill_no = %s", bill) == r["id"]


def test_custom_rule_names_follow_code_number(w, sql):
    """同一 PI 的第 n 条自定义规则：编码 PI-PI号-n，名称 PI号_vn。"""
    org, pi, bill = mk(w, ("M1", 3), ("M2", 3))
    ok(w.select(pi, [(bill, 1)], custom=m(prefix=P(), base=10, seq_len=6, with_length=True)))
    ok(w.select(pi, [(bill, 2)], custom=m(prefix=P(), base=10, seq_len=6, with_length=True)))
    w.generate(pi)
    rows = sql.all("SELECT rule_code, rule_name FROM sn_rule ORDER BY rule_code")
    assert [(r["rule_code"], r["rule_name"]) for r in rows] == [(f"PI-{pi}-1", f"{pi}_v1"), (f"PI-{pi}-2", f"{pi}_v2")]


def test_custom_rule_shared_by_lines_generated_separately(w, sql):
    """同一 PI 的两行选了同一自定义规则、分两次生成：第一次生成后两行都改记为存入的规则，第二次接着用这一版。"""
    org, pi, bill = mk(w, ("M1", 3), ("M2", 2))
    p = P()
    ok(w.select(pi, [(bill, 1), (bill, 2)], custom=m(prefix=p, base=10, seq_len=6, with_length=False)))
    w.generate(pi, lines=[(bill, 1)])
    rid = sql.scalar("SELECT id FROM sn_rule WHERE rule_code = %s", f"PI-{pi}-1")
    rows = sql.all("SELECT seq, rule_id, custom_json FROM sn_line_rule WHERE bill_no = %s ORDER BY seq", bill)
    assert [(r["rule_id"], r["custom_json"]) for r in rows] == [(rid, None), (rid, None)]
    w.generate(pi, lines=[(bill, 2)])
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE rule_code LIKE %s", f"PI-{pi}-%") == 1
    sns = [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert sns == [f"{p}{i:06d}" for i in range(1, 6)]


def test_custom_prefix_taken_after_select_flagged_in_preview(w):
    """两张 PI 选了同一前缀的自定义规则：一张先生成后，另一张预演即标「规则须换前缀」，不等生成时才失败。"""
    _, pa, ba = mk(w, ("M1", 2))
    _, pb, bb = mk(w, ("M1", 2))
    c = m(prefix=P(), base=10, seq_len=6, with_length=False)
    ok(w.select(pa, [(ba, 1)], custom=c))
    ok(w.select(pb, [(bb, 1)], custom=c))
    w.generate(pa)
    p = ok(w.preview(pb))
    assert p["generatable"] is False and p["issues"][0]["issues"] == ["RULE_PREFIX"]
    ok(w.select(pb, [(bb, 1)], custom={**c, "prefix": P()}))
    w.generate(pb)


def test_pi_bills_share_first_bill_prefix(w, api, sql):
    """「选规则」前缀默认取 PI 第一张单据编码后 7 位：第一张单据用它生成后，第二张单据用同一前缀、同一格式沿用那一版，
    流水接续；同一 PI 换进制或位数报格式锁定，只换米数、单位、后缀可共用前缀；别的 PI 用这个前缀报前缀冲突。"""
    stem = uid("").replace("-", "")
    org, pi, first = mk(w, ("M1", 2), bill="MOA" + stem)
    _, _, second = mk(w, ("M2", 3), pi=pi, org=org, bill="MOB" + stem)
    prefix = first[-7:]
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    assert ctx["rule_prefix"] == {"prefix": prefix, "custom": None}
    c = m(prefix=prefix, base=10, seq_len=4, with_length=False)
    ok(w.select(pi, [(first, 1)], custom=c))
    w.generate(pi, lines=[(first, 1)])
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    assert ctx["rule_prefix"]["prefix"] == prefix and ctx["rule_prefix"]["custom"]["seq_len"] == 4
    # 同一 PI 换流水位数：同一前缀下进制、位数、字符表锁定
    assert w.select(pi, [(second, 1)], custom={**c, "seq_len": 5}).code == "RULE_PREFIX_FORMAT_LOCKED"
    ok(w.select(pi, [(second, 1)], custom=ctx["rule_prefix"]["custom"]))
    rid = sql.scalar("SELECT id FROM sn_rule WHERE rule_code = %s", f"PI-{pi}-1")
    assert sql.scalar("SELECT rule_id FROM sn_line_rule WHERE bill_no = %s AND seq = 1", second) == rid
    w.generate(pi, lines=[(second, 1)])
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE rule_code LIKE %s", f"PI-{pi}-%") == 1
    sns = [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert sns == [f"{prefix}{i:04d}" for i in range(1, 6)]
    # 别的 PI 用这个前缀：全局唯一，冲突
    _, other, ob = mk(w, ("M1", 1))
    assert w.select(other, [(ob, 1)], custom=c).code == "RULE_PREFIX_CONFLICT"


def test_pi_prefix_stays_after_first_bill_changes(w, api, sql):
    """默认前缀不随 PI 的单据增减漂移：第一张单据用它出号后，这张单据离开快照（金蝶删除）、
    或有单据编号更小的单据加入本 PI，「选规则」仍默认本 PI 已用的这个前缀并带出其格式。"""
    stem = uid("").replace("-", "")
    org, pi, first = mk(w, ("M1", 2), bill="MO" + stem + "2")
    _, _, second = mk(w, ("M2", 3), pi=pi, org=org, bill="MO" + stem + "3")
    prefix = first[-7:]
    c = m(prefix=prefix, base=10, seq_len=4, with_length=False)
    ok(w.select(pi, [(first, 1)], custom=c))
    w.generate(pi, lines=[(first, 1)])
    # 单据编号更小的单据加入本 PI：MIN(bill_no) 变了，默认前缀不变
    mk(w, ("M3", 1), pi=pi, org=org, bill="MO" + stem + "1")
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    assert ctx["rule_prefix"]["prefix"] == prefix and ctx["rule_prefix"]["custom"]["seq_len"] == 4
    # 第一张单据离开快照：号仍挂在本 PI 下，默认前缀不变
    del BILLS[first]
    w.compare()
    assert not sql.count("SELECT COUNT(*) FROM sn_prd_mo WHERE bill_no = %s", first)
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    assert ctx["rule_prefix"]["prefix"] == prefix and ctx["rule_prefix"]["custom"]["seq_len"] == 4
    ok(w.select(pi, [(second, 1)], custom=ctx["rule_prefix"]["custom"]))
    w.generate(pi, lines=[(second, 1)])
    sns = [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert sns == [f"{prefix}{i:04d}" for i in range(1, 6)], "同一前缀、流水接续"


def test_pi_prefix_follows_first_bill_before_any_sn(w, api):
    """本 PI 还没有生成的号：custom 为 None（页面取点选的第一个单据编码后 7 位）；prefix 为当前第一张单据
    （单据编号最小）编码后 7 位，只在取不到点选的单据时兜底。"""
    stem = uid("").replace("-", "")
    org, pi, b = mk(w, ("M1", 2), bill="MO" + stem + "2")
    assert ok(api.get(f"/api/generate/context?pi={pi}", w.admin))["rule_prefix"] == {"prefix": b[-7:], "custom": None}
    _, _, a = mk(w, ("M1", 1), pi=pi, org=org, bill="MO" + stem + "1")
    assert ok(api.get(f"/api/generate/context?pi={pi}", w.admin))["rule_prefix"] == {"prefix": a[-7:], "custom": None}


def test_pi_prefix_saved_rule_not_generated_is_not_used(w, api):
    """只给物料行保存了规则、还没有生成：不算已用，下一张单据仍按点选的第一个单据取默认前缀（custom 为 None）。"""
    stem = uid("").replace("-", "")
    org, pi, a = mk(w, ("M1", 2), bill="MO" + stem + "1")
    mk(w, ("M1", 1), pi=pi, org=org, bill="MO" + stem + "2")
    ok(w.select(pi, [(a, 1)], custom=m(prefix=a[-7:], base=10, seq_len=4, with_length=False)))
    assert ok(api.get(f"/api/generate/context?pi={pi}", w.admin))["rule_prefix"]["custom"] is None


def test_pi_prefix_hand_typed_prefix_is_reused(w, api, sql):
    """本 PI 第一次生成用的是手填前缀（不是单据编码后 7 位）：之后各张单据都默认沿用它，流水接续。"""
    stem = uid("").replace("-", "")
    org, pi, a = mk(w, ("M1", 2), bill="MO" + stem + "1")
    _, _, b = mk(w, ("M1", 3), pi=pi, org=org, bill="MO" + stem + "2")
    hand = P()
    ok(w.select(pi, [(a, 1)], custom=m(prefix=hand, base=10, seq_len=4, with_length=False)))
    w.generate(pi, lines=[(a, 1)])
    rp = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))["rule_prefix"]
    assert rp["prefix"] == hand and rp["custom"]["prefix"] == hand and rp["custom"]["seq_len"] == 4
    ok(w.select(pi, [(b, 1)], custom=rp["custom"]))
    w.generate(pi, lines=[(b, 1)])
    sns = [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert sns == [f"{hand}{i:04d}" for i in range(1, 6)]


def test_pi_prefix_kept_until_all_sns_deleted(w, api):
    """已生成的号没有全部删掉：继续沿用这个前缀；全部删掉后按默认规则（custom 为 None，页面取点选的第一个单据）。"""
    stem = uid("").replace("-", "")
    org, pi, a = mk(w, ("M1", 3), bill="MO" + stem + "1")
    hand = P()
    ok(w.select(pi, [(a, 1)], custom=m(prefix=hand, base=10, seq_len=4, with_length=False)))
    w.generate(pi, lines=[(a, 1)])
    ok(w.delete(pi, start_sn=f"{hand}0001", end_sn=f"{hand}0002", reason="t"))
    rp = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))["rule_prefix"]
    assert rp["prefix"] == hand and rp["custom"] is not None, "还剩 1 个号：沿用"
    ok(w.delete(pi, lines=[(a, 1)], reason="t"))
    assert ok(api.get(f"/api/generate/context?pi={pi}", w.admin))["rule_prefix"] == {"prefix": a[-7:], "custom": None}


def test_custom_prefix_taken_before_run_reports_conflict(w, api):
    """确认生成时再查前缀是否全局唯一：预演后前缀被别的 PI 先用，生成报前缀冲突（页面弹窗），不只是「预演已改变」。"""
    _, pa, ba = mk(w, ("M1", 2))
    _, pb, bb = mk(w, ("M1", 2))
    c = m(prefix=P(), base=10, seq_len=6, with_length=False)
    ok(w.select(pa, [(ba, 1)], custom=c))
    ok(w.select(pb, [(bb, 1)], custom=c))
    token = ok(w.preview(pb))["token"]
    w.generate(pa)
    r = api.post("/api/generate/run", m(token=token), w.admin)
    assert r.code == "RULE_PREFIX_CONFLICT"


def test_custom_rule_seq_start(w, api, sql):
    """自定义规则可手动指定起始号：首次生成从最大号之后编号时第一个号不小于它，跳过的流水成为空号段；
    同格式没填起始号的物料行与它是同一条规则，排在其后接着编。"""
    org, pi, bill = mk(w, ("M1", 3), ("M2", 2))
    p = P()
    c = m(prefix=p, base=10, seq_len=4, with_length=False)
    ok(w.select(pi, [(bill, 1)], custom={**c, "seq_start": 101}))
    ok(w.select(pi, [(bill, 2)], custom=c))
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    rule1 = next(x for b in ctx["bills"] for x in b["lines"] if x["seq"] == 1)["rule"]
    assert rule1["seq_start"] == 101 and rule1["first_sn"] == f"{p}0101"
    w.generate(pi)
    sns = [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert sns == [f"{p}{i:04d}" for i in range(101, 106)]
    # 起始号不同仍是同一条规则
    assert sql.count("SELECT COUNT(*) FROM sn_rule WHERE rule_code LIKE %s", f"PI-{pi}-%") == 1
    rid = sql.scalar("SELECT id FROM sn_rule WHERE rule_code = %s", f"PI-{pi}-1")
    rows = sql.all("SELECT rule_id, custom_json FROM sn_line_rule WHERE bill_no = %s ORDER BY seq", bill)
    assert [(r["rule_id"], r["custom_json"]) for r in rows] == [(rid, None), (rid, None)]
    g = ok(api.get(f"/api/generate/gaps?pi={pi}", w.admin))
    assert (g["next_seq"], g["total"], g["segments"][0]["start_seq"], g["segments"][0]["end_seq"]) == (106, 100, 1, 100)


def test_custom_rule_seq_start_validation_and_sample(w, api):
    """起始号须为 1..规则最大流水；示例号从起始号起。"""
    org, pi, bill = mk(w, ("M1", 1))
    c = m(prefix=P(), base=10, seq_len=2, with_length=False)
    assert w.select(pi, [(bill, 1)], custom={**c, "seq_start": 100}).code == "RULE_SEQ_START_INVALID"
    assert w.select(pi, [(bill, 1)], custom={**c, "seq_start": 0}).code == "RULE_SEQ_START_INVALID"
    r = ok(api.post("/api/rules/preview", {**c, "seq_start": 7}, w.admin))
    assert [x["seq_dec"] for x in r["samples"]] == [7, 8, 9, 10, 11, 99]
    assert api.post("/api/rules/preview", {**c, "seq_start": 100}, w.admin).code == "RULE_SEQ_START_INVALID"
    r = ok(api.post("/api/rules/preview", c, w.admin))
    assert [x["seq_dec"] for x in r["samples"]] == [1, 2, 3, 4, 5, 99], "不填起始号照旧从 1 起"


def test_custom_rule_seq_start_with_range_and_used_prefix(w, sql):
    """手动起止号时范围内的空闲流水先用，溢出部分才按起始号；本 PI 已用过这个前缀时流水接续，起始号不起作用。"""
    org, pi, bill = mk(w, ("M1", 4), ("M2", 2))
    p = P()
    c = m(prefix=p, base=10, seq_len=4, with_length=False)
    ok(w.select(pi, [(bill, 1)], custom={**c, "seq_start": 50}))
    w.generate(pi, lines=[(bill, 1)], seq=(1, 2))
    seqs = [r["seq_dec"] for r in sql.all("SELECT seq_dec FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert seqs == [1, 2, 50, 51]
    ok(w.select(pi, [(bill, 2)], custom={**c, "seq_start": 500}))
    assert sql.scalar("SELECT custom_json FROM sn_line_rule WHERE bill_no = %s AND seq = 2", bill) is None
    w.generate(pi, lines=[(bill, 2)])
    seqs = [r["seq_dec"] for r in sql.all("SELECT seq_dec FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)]
    assert seqs == [1, 2, 50, 51, 52, 53]


def test_rules_bound_by_pi_only(w, api):
    """规则只按 PI 维度绑定：没有按客户绑定；物料行没选、PI 也没绑时须先选规则，同一客户的别的 PI 不受影响。"""
    cust = uid("CU")
    _, p1, _ = mk(w, ("M1", 2), customer=cust)
    _, p2, _ = mk(w, ("M1", 2), customer=cust)
    assert api.post("/api/rules/customer-binding", m(customer_code=cust, rule_id=1), w.admin).status in (404, 405)
    assert api.delete(f"/api/rules/customer-binding?customer={cust}", w.admin).status in (404, 405)
    assert api.post("/api/rules/customer-template", m(pi_no=p1, new_prefix=P()), w.admin).status in (404, 405)
    ok(w.bind_pi(p1, w.rule(P())))
    w.generate(p1)
    p = ok(w.preview(p2))
    assert p["generatable"] is False and p["issues"][0]["issues"] == ["NO_RULE"]
    ctx = ok(api.get(f"/api/generate/context?pi={p2}", w.admin))
    assert ctx["pi_rule"] is None and "customer_rule" not in ctx.body


# ---------------------------------------------------------------- 生成


def test_start_after_own_max_ignoring_transferred_in(w, sql):
    org, pi, bill = mk(w, ("M1", 1200), ("M1", 100))
    rid = w.rule(P())
    ok(w.bind_pi(pi, rid))
    w.generate(pi, lines=[(bill, 1)])
    j = w.generate(pi, lines=[(bill, 2)])
    assert (j["start_seq_dec"], j["end_seq_dec"]) == (1201, 1300)


def test_generation_takes_whole_lines(w, api, sql):
    org, pi, bill = mk(w, ("M1", 7), ("M2", 5), ("M3", 3))
    ok(w.bind_pi(pi, w.rule(P())))
    r = api.post(
        "/api/generate/preview",
        m(pi_no=pi, scope="LINES", lines=[m(bill_no=bill, seq=2), m(bill_no=bill, seq=1)], qty=1),
        w.admin,
    )
    p = ok(r)
    assert p["qty"] == 12 and [(x["seq"], x["qty"]) for x in p["items"]] == [(2, 5), (1, 7)], "不填数量，每行整行生成"
    w.generate(pi, lines=[(bill, 2), (bill, 1)])
    c = {r["seq"]: r["own_generated"] for r in sql.all("SELECT * FROM sn_line_count WHERE bill_no = %s", bill)}
    assert c == {1: 7, 2: 5}
    p = ok(w.preview(pi))
    assert p["qty"] == 3 and [(x["seq"], x["qty"]) for x in p["items"]] == [(3, 3)], "已生成满的行不再参与"


def test_preview_lists_reasons(w, api):
    org, pi, bill = mk(w, ("M1", 5, " "), ("M2", 5, "x1"), ("M3", 5, "1", ""))
    rid = w.rule(P())
    ok(w.bind_pi(pi, rid))
    p = ok(w.preview(pi))
    assert p["generatable"] is False
    got = {x["seq"]: x["issues"] for x in p["issues"]}
    assert got == {1: ["LENGTH_EMPTY"], 3: ["ATTACH_EMPTY"]}, "金蝶米数为空须填写（有值的原样用），附件项次必填"
    ok(api.put("/api/orders/attach", m(bill_no=bill, seq=3, value="A9"), w.admin))
    assert ok(w.preview(pi, lines=[(bill, 3)]))["generatable"] is True, "填写附件项次后可以生成；可缩小范围"
    assert ok(w.preview(pi))["generatable"] is False
    assert api.put("/api/orders/attach", m(bill_no=bill, seq=1, value="A9"), w.admin).code == "ATTACH_FILL_K3"
    r = ok(api.put("/api/orders/length", m(bill_no=bill, seq=2, value="7"), w.admin))
    assert (r["length"], r["length_source"]) == ("7", "MANUAL"), "金蝶有米数也可改"
    assert api.put("/api/orders/length", m(bill_no=bill, seq=1, value="x" * 65), w.admin).code == "LENGTH_FILL_INVALID"
    r = ok(api.put("/api/orders/length", m(bill_no=bill, seq=1, value=" 020.50 "), w.admin))
    assert (r["length"], r["length_source"]) == ("020.50", "MANUAL"), "米数是字符串，原样保存"
    r = ok(api.put("/api/orders/length", m(bill_no=bill, seq=1, value=""), w.admin))
    assert (r["length"], r["length_source"]) == ("", "EMPTY"), "留空清除填写，又须填写"
    assert ok(w.preview(pi))["generatable"] is False
    ok(api.put("/api/orders/length", m(bill_no=bill, seq=1, value="000"), w.admin))
    assert ok(w.preview(pi))["generatable"] is True


def test_attach_and_length_locked_once_line_has_sns(w, api, sql):
    org, pi, bill = mk(w, ("M1", 4, "", ""), ("M2", 2, "", ""))
    p = P()
    ok(w.bind_pi(pi, w.rule(p)))
    for seq in (1, 2):
        ok(api.put("/api/orders/attach", m(bill_no=bill, seq=seq, value="A1"), w.admin))
        ok(api.put("/api/orders/length", m(bill_no=bill, seq=seq, value="000"), w.admin))
    j = w.generate(pi, lines=[(bill, 1)])
    line = {x["seq"]: x for x in ok(api.get(f"/api/orders/{bill}/lines", w.admin))["lines"]}
    assert (line[1]["edit_lock"], line[2]["edit_lock"]) == ("GENERATED", None)
    assert api.put("/api/orders/attach", m(bill_no=bill, seq=1, value="A2"), w.admin).code == "LINE_EDIT_GENERATED"
    assert api.put("/api/orders/length", m(bill_no=bill, seq=1, value="5"), w.admin).code == "LINE_EDIT_GENERATED"
    # 订单数量增加后，同一物料行接着生成新增的额度
    BILLS[bill]["TreeEntity"][0]["Qty"] = 6.0
    w.touch(bill)
    w.compare()
    w.generate(pi, lines=[(bill, 1)])
    assert [
        r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE bill_seq = 1 AND pi_no = %s ORDER BY seq_dec", pi)
    ] == [
        f"{p}000001000",
        f"{p}000002000",
        f"{p}000003000",
        f"{p}000004000",
        f"{p}000005000",
        f"{p}000006000",
    ], "同一物料行接着生成仍用锁定的米数"
    # 删掉该行的号后可修改，再生成用新值；最大号不退回，新号接在最大号之后
    assert j["status"] == "SUCCESS"
    ok(w.delete(pi, lines=[(bill, 1)], reason="改米数"))
    r = ok(api.put("/api/orders/length", m(bill_no=bill, seq=1, value="5"), w.admin))
    assert (r["length"], r["edit_lock"]) == ("5", None)
    w.generate(pi, lines=[(bill, 1)])
    assert sql.scalar("SELECT sn FROM sn_item WHERE bill_seq = 1 AND pi_no = %s ORDER BY seq_dec LIMIT 1", pi) == (
        f"{p}0000075"
    )
    w.allocate(pi)
    line = {x["seq"]: x for x in ok(api.get(f"/api/orders/{bill}/lines", w.admin))["lines"]}
    assert line[1]["edit_lock"] == "ALLOCATED"
    assert api.put("/api/orders/length", m(bill_no=bill, seq=1, value="6"), w.admin).code == "LINE_EDIT_ALLOCATED"
    # 已分配的号也能删，删后可改
    ok(w.delete(pi, lines=[(bill, 1)]))
    assert ok(api.put("/api/orders/length", m(bill_no=bill, seq=1, value="6"), w.admin))["edit_lock"] is None


def test_revoke_config_resets_lines_without_sns(w, api, sql):
    """待处理页签撤销配置：没有号的物料行，物料行规则、手填的附件项次、米数和产品单位恢复为未配置；有号的行跳过。"""
    org, pi, bill = mk(w, ("M1", 2, "", ""), ("M2", 2, "", "", "条"), ("M3", 2, "", ""))
    ok(w.bind_pi(pi, w.rule(P())))
    ok(w.select(pi, [(bill, 2), (bill, 3)], w.rule(P())))
    for seq in (1, 2, 3):
        ok(api.put("/api/orders/attach", m(bill_no=bill, seq=seq, value="A1"), w.admin))
    ok(api.put("/api/orders/length", m(bill_no=bill, seq=2, value="7"), w.admin))
    ok(api.put("/api/orders/unit", m(bill_no=bill, seq=2, value="M"), w.admin))
    ok(api.put("/api/orders/length", m(bill_no=bill, seq=3, value="8"), w.admin))
    w.generate(pi, lines=[(bill, 3)])
    lines = lambda: {x["seq"]: x for x in ok(api.get(f"/api/orders/{bill}/lines", w.admin))["lines"]}  # noqa: E731
    before = lines()
    url = "/api/generate/revoke-config"
    assert api.post(url, m(pi_no=pi, lines=[]), w.admin).code == "RULE_LINE_REQUIRED"
    assert api.post(url, m(pi_no=uid("PI"), lines=[m(bill_no=bill, seq=1)]), w.admin).code == "GEN_SCOPE_INVALID"
    r = ok(api.post(url, m(pi_no=pi, lines=[m(bill_no=bill, seq=s) for s in (1, 2, 3)]), w.admin))
    assert (r["revoked"], r["skipped"]) == (2, 1)
    after = lines()
    assert [(after[s]["attach_source"], after[s]["length_source"]) for s in (1, 2)] == [("EMPTY", "EMPTY")] * 2
    assert (after[2]["unit"], after[2]["unit_source"], after[2]["unit_manual"]) == ("条", "K3", None)
    assert sql.count("SELECT COUNT(*) FROM sn_line_rule WHERE bill_no = %s", bill) == 1, "有号的行规则不动"
    assert (after[3]["attach_no"], after[3]["edit_lock"]) == (before[3]["attach_no"], "GENERATED")
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    issues = {x["seq"]: x["issues"] for x in ctx["bills"][0]["lines"]}
    assert issues[1] == issues[2] == ["LENGTH_EMPTY", "ATTACH_EMPTY"], "规则改回按 PI 绑定，米数、附件项次待填写"
    a = sql.one("SELECT * FROM sn_audit WHERE action = 'LINE_CONFIG_REVOKE' AND result = 'OK' AND pi_no = %s", pi)
    assert a["qty"] == 2 and a["bill_no"] == bill and f"{bill}/2:rule+attach+length+unit" in a["detail"]
    r = ok(api.post(url, m(pi_no=pi, lines=[m(bill_no=bill, seq=1)]), w.admin))
    assert r["revoked"] == 0, "没有可撤的配置"


def test_empty_length_must_be_filled(w, api, sql):
    """金蝶米数为空：规则带米数时须填写才能生成（不再默认 000）；填 000 也原样写入。"""
    org, pi, bill = mk(w, ("M1", 1, " "))
    p = P()
    ok(w.bind_pi(pi, w.rule(p)))
    assert [x["issues"] for x in ok(w.preview(pi))["issues"]] == [["LENGTH_EMPTY"]]
    ok(api.put("/api/orders/length", m(bill_no=bill, seq=1, value="000"), w.admin))
    w.generate(pi)
    assert sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s", pi) == f"{p}000001000"


def test_length_is_written_as_string(w, sql):
    org, pi, bill = mk(w, ("M1", 1, "10.50"), ("M2", 1, "001"), ("M3", 1, "L2"))
    p = P()
    ok(w.bind_pi(pi, w.rule(p)))
    w.generate(pi)
    assert [r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec", pi)] == [
        f"{p}00000110.50",
        f"{p}000002001",
        f"{p}000003L2",
    ], "米数不按数值规范化，000 / 001 / 其他原样写入"


def test_click_order_and_repeated_material_lines(w, sql):
    org, pi, bill = mk(w, ("9148363", 100), ("9148363", 50), ("9150001", 30))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi, lines=[(bill, 3), (bill, 1)])
    rows = {
        r["bill_seq"]: (r["a"], r["b"])
        for r in sql.all(
            "SELECT bill_seq, MIN(seq_dec) a, MAX(seq_dec) b FROM sn_item WHERE pi_no = %s GROUP BY bill_seq", pi
        )
    }
    assert rows == {3: (1, 30), 1: (31, 130)}, "先点序号 3 再点序号 1"
    c = {r["seq"]: r for r in sql.all("SELECT * FROM sn_line_count WHERE bill_no = %s", bill)}
    assert c[1]["own_generated"] == 100 and 2 not in c


def test_whole_bill_order(w, sql):
    org, pi, bill = mk(w, ("9148363", 100), ("9148363", 50), ("9150001", 30))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    rows = {
        r["bill_seq"]: (r["a"], r["b"])
        for r in sql.all(
            "SELECT bill_seq, MIN(seq_dec) a, MAX(seq_dec) b FROM sn_item WHERE pi_no = %s GROUP BY bill_seq", pi
        )
    }
    assert rows == {1: (1, 100), 2: (101, 150), 3: (151, 180)}


def test_preview_invalidated_by_other_generation(w, api):
    org, pi, bill = mk(w, ("M1", 3), ("M2", 2))
    ok(w.bind_pi(pi, w.rule(P())))
    p = ok(w.preview(pi))
    w.generate(pi, lines=[(bill, 2)])
    r = api.post("/api/generate/run", m(token=p["token"]), w.admin)
    assert r.code == "GEN_PREVIEW_STALE"


def test_preview_single_use_and_max(w, api, monkeypatch):
    org, pi, bill = mk(w, ("M1", 10), ("M2", 3))
    ok(w.bind_pi(pi, w.rule(P())))
    monkeypatch.setenv("SN_GEN_MAX_PER_RUN", "12")
    assert w.preview(pi).code == "GEN_QTY_OVER_MAX", "整行合计超过单次硬上限：少选几行"
    p = ok(w.preview(pi, lines=[(bill, 1)]))
    ok(api.post("/api/generate/run", m(token=p["token"]), w.admin))
    assert api.post("/api/generate/run", m(token=p["token"]), w.admin).code == "GEN_PREVIEW_USED"


def test_alloc_context_lists_pending_lines(w, api):
    org, pi, b1 = mk(w, ("M1", 2), ("M2", 3))
    _, _, b2 = mk(w, ("M1", 1), pi=pi, org=org)
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.touch(b2, status="D")
    w.compare()
    ctx = ok(api.get(f"/api/allocation/context?pi={pi}", w.admin))
    rows = [(x["bill_no"], x["seq"], x["qty"], x["org_code"], x["skip"]) for x in ctx["lines"]]
    assert rows == [(b1, 1, 2, org, None), (b1, 2, 3, org, None), (b2, 1, 1, None, "REVERSAL")]
    assert ctx["lines"][0]["start_sn"] <= ctx["lines"][0]["end_sn"]
    assert ctx["summary"]["pending_alloc"] == 6 and ctx["push"] == []


def test_background_generation_and_cancel(w, api, sql, monkeypatch):
    monkeypatch.setenv("SN_GEN_SYNC_THRESHOLD", "10")
    org, pi, bill = mk(w, ("M1", 5000))
    ok(w.bind_pi(pi, w.rule(P())))
    p = ok(w.preview(pi))
    j = ok(api.post("/api/generate/run", m(token=p["token"]), w.admin))
    batch = j["gen_batch"]
    import time

    for _ in range(100):
        prog = ok(api.get(f"/api/generate/batches/{batch}", w.admin))
        if prog["status"] != "RUNNING":
            break
        time.sleep(0.1)
    assert prog["status"] == "SUCCESS" and prog["percent"] == 100
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 5000
    assert api.post(f"/api/generate/batches/{batch}/cancel", {}, w.admin).code == "GEN_CANCEL_NOT_RUNNING"


def test_workbench_lists_status_and_buttons(w, api):
    org, pi, bill = mk(w, ("M1", 10))
    r = ok(api.get(f"/api/generate/pis?q={pi}", w.admin))
    assert r["items"][0]["status"] == "NEED_RULE"
    ok(w.bind_pi(pi, w.rule(P())))
    assert ok(api.get(f"/api/generate/pis?q={pi}", w.admin))["items"][0]["status"] == "TO_GENERATE"

    def tabs() -> tuple[list[str], ...]:
        pages = (
            ("generate/pis", "TO_GENERATE"),
            ("generate/pis", "GENERATED"),
            ("allocation/bills", "TO_ALLOCATE"),
            ("allocation/bills", "ALLOCATED"),
        )
        return tuple([x["pi_no"] for x in ok(api.get(f"/api/{p}?q={pi}&tab={t}", w.admin))["items"]] for p, t in pages)

    assert tabs() == ([pi], [], [], [])
    w.generate(pi)
    x = ok(api.get(f"/api/generate/pis?q={pi}", w.admin))["items"][0]
    assert (x["status"], x["pending_alloc"], x["generated_qty"]) == ("TO_ALLOCATE", 10, 10)
    ctx = ok(api.get(f"/api/generate/context?bill_no={bill}", w.admin))
    assert ctx["pi_no"] == pi and ctx["bills"][0]["lines"][0]["rule"]["format"].startswith("P")
    actx = ok(api.get(f"/api/allocation/context?pi={pi}", w.admin))
    assert actx["summary"]["pending_alloc"] == 10 and actx["summary"]["quota"] == 0
    assert tabs() == ([], [pi], [pi], [])
    w.allocate(pi)
    assert ok(api.get(f"/api/generate/pis?q={pi}", w.admin))["items"][0]["status"] == "DONE"
    assert tabs() == ([], [pi], [], [pi])


def test_generating_pi_listed_first_under_to_generate(w, api, sql):
    _, running_pi, _ = mk(w, ("M1", 10))
    ok(w.bind_pi(running_pi, w.rule(P())))
    w.generate(running_pi)
    _, other, _ = mk(w, ("M1", 10))
    ok(w.bind_pi(other, w.rule(P())))
    # 模拟生成中：额度已用完，仍归「待生成」页签并排在最前
    sql.exec("UPDATE sn_gen_job SET running_pi = pi_no WHERE pi_no = %s", running_pi)
    try:
        items = ok(api.get("/api/generate/pis?tab=TO_GENERATE", w.admin))["items"]
        names = [x["pi_no"] for x in items]
        assert items[0]["status"] == "GENERATING" and running_pi in names and other in names
        assert names.index(running_pi) < names.index(other)
    finally:
        sql.exec("UPDATE sn_gen_job SET running_pi = NULL WHERE pi_no = %s", running_pi)


def test_allocation_skips_reversed_bill(w, api, sql):
    org, pi, b1 = mk(w, ("M1", 2))
    _, _, b2 = mk(w, ("M1", 2), pi=pi, org=org)
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.touch(b2, status="D")
    w.compare()
    r = w.allocate(pi)
    assert r["qty"] == 2 and r["skipped"][0]["bill_no"] == b2 and r["skipped"][0]["reason"] == "REVERSAL"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'ALLOCATED'", b1) == 2


def test_generate_page_sorts_like_orders_page(w, api):
    """生成页 PI 列表与详情里的单据都按修改日期新的在前（与订单页同一排序）；整张 PI 生成仍按单据编号排号。"""
    org = w.org(uid("O")[:10])
    pi_old, pi_new = uid("PI"), uid("PI")
    a, b = "A" + uid("MO"), "B" + uid("MO")
    for no, pi, when in ((a, pi_new, "2026-09-02T00:00:00"), (b, pi_new, "2026-09-01T00:00:00")):
        BILLS[no] = w.doc(no, org, "C1", pi, ("M1", 1))
        BILLS[no]["ModifyDate"] = when
    c = uid("MO")
    BILLS[c] = w.doc(c, org, "C1", pi_old, ("M1", 1))
    BILLS[c]["ModifyDate"] = "2026-08-01T00:00:00"
    w.compare()
    ctx = ok(api.get(f"/api/generate/context?pi={pi_new}", w.admin))
    assert [x["bill_no"] for x in ctx["bills"]] == [a, b], "单据按修改日期新的在前"
    assert ctx["bills"][0]["modify_time"] == "2026-09-02 00:00:00"
    items = ok(api.get("/api/generate/pis?tab=TO_GENERATE", w.admin))["items"]
    order = [x["pi_no"] for x in items if x["pi_no"] in (pi_old, pi_new)]
    assert order == [pi_new, pi_old], "PI 按其单据的最新修改日期排"
    ok(w.bind_pi(pi_new, w.rule(P(), with_length=False)))
    p = ok(w.preview(pi_new, [a, b]))
    assert [x["bill_no"] for x in p["items"]] == [a, b], "排号按所给单据顺序（页面默认传单据编号从小到大）"


def test_allocation_bill_list_driven_by_sns(w, api, sql):
    """分配页左侧按单据列出（按号取）：单据移出快照仍列出；只按 PI、单据编号筛选；SN 枚数与状态（待分配 / 已分配）。"""
    org = w.org(uid("O")[:10])
    _, pi, b1 = mk(w, ("M1", 3), org=org, bill="A" + uid("MO"))
    _, _, b2 = mk(w, ("M1", 2), pi=pi, org=org, bill="A" + uid("MO"))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    BILLS.pop(b2)
    w.compare()

    def rows(tab: str = "", q: str = pi) -> dict[str, tuple]:
        r = ok(api.get(f"/api/allocation/bills?tab={tab}&q={q}", w.admin))
        return {x["bill_no"]: (x["pi_no"], x["qty"], x["state"]) for x in r["items"]}

    assert rows("TO_ALLOCATE") == {b1: (pi, 3, "PENDING"), b2: (pi, 2, "PENDING")}, "移出快照的单据有号仍列出"
    assert rows("TO_ALLOCATE", b2) == {b2: (pi, 2, "PENDING")}, "可按单据编号筛选"
    # 生成在一分钟前：分配后 b1 的处理时间更近
    sql.exec("UPDATE sn_item SET updated_at = updated_at - INTERVAL 1 MINUTE WHERE pi_no = %s", pi)
    w.allocate(pi, lines=[(b1, 1)])
    assert rows("TO_ALLOCATE") == {b2: (pi, 2, "PENDING")}
    assert rows("ALLOCATED") == {b1: (pi, 3, "ALLOCATED")}, "未对接 MES：分配即已分配"
    r = ok(api.get(f"/api/allocation/bills?q={pi}&page_size=1", w.admin))
    assert (r["total"], [x["bill_no"] for x in r["items"]]) == (2, [b1]), "分页；处理时间近的在前"
    ctx = ok(api.get(f"/api/allocation/context?pi={pi}", w.admin))
    got = {(x["bill_no"], x["qty"], x["pending_alloc"], x["bill_state"]) for x in ctx["lines"]}
    assert got == {(b1, 3, 0, "ALLOCATED"), (b2, 2, 2, "PENDING")}, "详情列出本 PI 全部单据的物料行与状态"
    assert ctx["summary"]["allocated_qty"] == 3, "已分配按单据：只数已分配单据的号"


def test_generate_context_bill_alloc_state(w, api):
    """生成详情各单据带分配状态（与分配页左侧同一口径）：还没有号为 None；待分配；
    号都已分配但还在推送 = 待处理；号都已分配且推送完成 = 已分配（页面在「可生成」页签置灰、不可勾选）。"""
    org = w.org(uid("O")[:10])
    _, pi, b1 = mk(w, ("M1", 3), ("M2", 2), org=org)
    _, _, b2 = mk(w, ("M1", 2), pi=pi, org=org)
    _, _, b3 = mk(w, ("M1", 1), pi=pi, org=org)
    mes = w.org(uid("O")[:10])
    w.enable_mes(mes)
    _, _, b4 = mk(w, ("M1", 2), pi=pi, org=mes)
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi, lines=[(b1, 1), (b2, 1), (b4, 1)])

    def states() -> dict[str, tuple]:
        ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
        return {b["bill_no"]: (b["alloc_state"], b["pending_alloc"], b["quota"]) for b in ctx["bills"]}

    assert states() == {
        b1: ("PENDING", 3, 2),
        b2: ("PENDING", 2, 0),
        b3: (None, 0, 1),
        b4: ("PENDING", 2, 0),
    }
    w.allocate(pi, bills=[b1, b4])
    got = states()
    assert got[b1] == ("ALLOCATED", 0, 2), "号都已分配（未对接 MES 分配即完成），还有额度的行仍列在可生成"
    assert got[b4] == ("PROCESSING", 0, 0), "推送未完成的单据为待处理，不算已分配"
    assert got[b2] == ("PENDING", 2, 0) and got[b3] == (None, 0, 1)
    w.push()
    assert states()[b4] == ("ALLOCATED", 0, 0), "推送完成后为已分配"


def test_interrupted_job_recovered_only_after_owner_exits(w, sql):
    """多实例：执行生成的实例退出后（PI 操作锁随连接释放）定期回收把 RUNNING 任务标为中断并释放 PI；
    锁还被持有（仍在执行）时不动它。"""
    from app.services import generate, locks

    org, pi, bill = mk(w, ("M1", 3))
    ok(w.bind_pi(pi, w.rule(P())))
    batch = w.generate(pi)["gen_batch"]

    def state() -> dict:
        return sql.one("SELECT status, error_code, running_pi FROM sn_gen_job WHERE gen_batch = %s", batch)

    owner = locks.pi_lock(pi)
    try:
        sql.exec(
            "UPDATE sn_gen_job SET status = 'RUNNING', running_pi = pi_no, error_code = NULL WHERE gen_batch = %s",
            batch,
        )
        generate.recover_interrupted()
        assert state() == {"status": "RUNNING", "error_code": None, "running_pi": pi}, "仍在执行：不回收"
    finally:
        owner.release()
    generate.recover_interrupted()
    assert state() == {"status": "FAILED", "error_code": "INTERRUPTED", "running_pi": None}
