"""SN 导出（F-14）：已分配的号按「生产组织 + PI + 单据」查看与下载；只读，不改状态；与推送、拉取相互独立。"""

from __future__ import annotations

from conftest import MES, emp, m, ok, uid, xlsx_rows

from app.services import sn_export


def P() -> str:
    return "P" + uid("").replace("-", "")


def ready(w, qty, mes=False, batch_size=5000, allocate=True):
    org = w.org(uid("O")[:10])
    if mes:
        w.enable_mes(org, batch_size=batch_size)
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", qty))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    if allocate:
        w.allocate(pi)
    return org, pi, bill


def get_file(api, token, **params):
    q = "&".join(f"{k}={v}" for k, v in params.items())
    return api.client.get(f"/api/export/file?{q}", headers={"Authorization": f"Bearer {token}"})


def test_export_is_read_only_and_repeatable(w, api, sql):
    org, pi, bill = ready(w, 5)
    op = w.user(emp("F"), "operator", org)
    rows = ok(api.get(f"/api/export/bills?q={pi}", op))["items"]
    assert [(r["pi_no"], r["bill_no"], r["qty"], r["suspended"], r["mes_enabled"]) for r in rows] == [
        (pi, bill, 5, 0, False)
    ]
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'ALLOCATED'", pi) == 5, "分配即已分配"
    for _ in range(2):
        r = get_file(api, op, pi=pi, bill_no=bill, factory_code=org)
        rows = xlsx_rows(r.content)
        assert r.status_code == 200 and rows[0] == sn_export.FILE_HEADERS and len(rows) == 6
        got = dict(zip(rows[0], rows[1], strict=True))
        assert (got["组织编号"], got["生产组织"], got["PI"], got["物料编码"]) == (org, f"组织{org}", pi, "M1")
        assert (got["附件项次"], got["米数"], got["产品单位"], got["流水位数"]) == ("1", "100", None, 6)
        assert (got["十进制流水"], got["流水号"]) == ("1", "000001")
    sns = [x[4] for x in rows[1:]]
    assert sns == [
        r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY bill_seq, seq_pi_no, seq_dec", pi)
    ]
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'ALLOCATED'", pi) == 5, "不改状态"
    x = get_file(api, op, pi=pi, bill_no=bill, format="csv")
    assert x.status_code == 200 and len(xlsx_rows(x.content)) == 6, "不再提供 csv：format 参数忽略，一律 xlsx"


def test_mes_org_lists_every_allocated_sn_and_push_unaffected(w, api, sql):
    org, pi, bill = ready(w, 13, mes=True, batch_size=5)
    row = ok(api.get(f"/api/export/bills?q={bill}", w.admin))["items"][0]
    assert (row["qty"], row["mes_enabled"]) == (13, True)
    r = get_file(api, w.admin, pi=pi, bill_no=bill)
    assert len(xlsx_rows(r.content)) == 14
    w.push()  # 导出后照常推送
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND status = 'PUSHED'", pi) == 13
    assert len(MES.store[org]) >= 13
    row = ok(api.get(f"/api/export/bills?q={bill}&mes=true", w.admin))["items"][0]
    assert row["qty"] == 13, "已推送的号照样展示"
    r = get_file(api, w.admin, pi=pi, bill_no=bill)
    assert len(xlsx_rows(r.content)) == 14
    seg = ok(api.get(f"/api/export/segments?pi={pi}&bill_no={bill}", w.admin))
    assert seg["total"] == 1 and seg["items"][0]["qty"] == 13 and seg["items"][0]["start_seq"] == 1


def test_detail_lists_every_sn_not_ranges(w, api, sql):
    """明细逐枚列出该单据的全部 SN（不按号段合并），分页，顺序与下载文件一致；带十进制流水、暂停、申请中。"""
    org, pi, bill = ready(w, 7)
    want = [
        r["sn"] for r in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY bill_seq, seq_pi_no, seq_dec", pi)
    ]
    url = f"/api/export/items?pi={pi}&bill_no={bill}&factory_code={org}"
    got = ok(api.get(url + "&page_size=5", w.admin))
    assert got["total"] == 7 and [x["sn"] for x in got["items"]] == want[:5]
    x = got["items"][0]
    assert (x["line_no"], x["seq_dec"], x["seq_text"], x["bill_seq"], x["material_code"], x["length"]) == (
        1,
        1,
        "000001",
        1,
        "M1",
        "100",
    )
    assert (x["status"], x["delivery_mode"], x["delivery_batch_no"]) == ("ALLOCATED", "", "")
    assert (x["suspend"], x["transferred_in"]) == (None, False)
    page2 = ok(api.get(url + "&page_size=5&page=2", w.admin))
    assert [x["sn"] for x in page2["items"]] == want[5:] and [x["line_no"] for x in page2["items"]] == [6, 7]
    file_sns = [r[4] for r in xlsx_rows(get_file(api, w.admin, pi=pi, bill_no=bill, factory_code=org).content)[1:]]
    assert file_sns == want, "顺序同下载文件"
    assert (x["pi_no"], x["factory_code"], x["factory_name"], x["seq_len"], x["unit"]) == (pi, org, f"组织{org}", 6, "")
    other = w.org(uid("O")[:10])
    op = w.user(emp("F"), "operator", other)
    assert api.get(url, op).code == "FACTORY_FORBIDDEN"
    assert api.get(f"/api/export/items?bill_no={bill}", w.admin).code == "PI_REQUIRED"
    hit = ok(api.get(url + f"&q={want[3][-8:]}", w.admin))
    assert [x["sn"] for x in hit["items"]] == [want[3]], "按完整 SN 模糊搜索"


def test_detail_filters_one_bill_line(w, api):
    org = w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 3, "120"), ("M2", 2, "080"))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    url = f"/api/export/items?pi={pi}&bill_no={bill}&factory_code={org}"
    assert ok(api.get(url, w.admin))["total"] == 5
    got = ok(api.get(url + "&bill_seq=2&material=M2", w.admin))
    assert got["total"] == 2 and {(x["bill_seq"], x["material_code"], x["length"]) for x in got["items"]} == {
        (2, "M2", "080")
    }
    assert [x["line_no"] for x in got["items"]] == [1, 2]
    assert ok(api.get(url + "&bill_seq=2&material=M1", w.admin))["total"] == 0
    # 分配页「已分配」页签：按物料编码、SN 起止号（含两端）筛选
    every = [x["sn"] for x in ok(api.get(url, w.admin))["items"]]
    assert [x["sn"] for x in ok(api.get(url + "&material=M1", w.admin))["items"]] == every[:3]
    got = ok(api.get(url + f"&sn_from={every[1]}&sn_to={every[3]}", w.admin))
    assert [x["sn"] for x in got["items"]] == every[1:4]
    assert [x["sn"] for x in ok(api.get(url + f"&sn_from={every[3]}", w.admin))["items"]] == every[3:]
    assert [x["sn"] for x in ok(api.get(url + f"&material=M2&sn_to={every[3]}", w.admin))["items"]] == [every[3]]
    assert ok(api.get(url + "&sn_from=NOPE", w.admin))["total"] == 0, "起止号不是本单据的号：没有结果"
    # 下载同一组筛选：只下载范围内的号；列为组织编号、生产组织、PI、物料编码、SN、流水位数、十进制流水、流水号、
    # 附件项次、米数、产品单位
    rows = xlsx_rows(get_file(api, w.admin, pi=pi, bill_no=bill, sn_from=every[1], sn_to=every[3]).content)
    assert rows[0] == sn_export.FILE_HEADERS and [r[4] for r in rows[1:]] == every[1:4]
    head = dict(zip(rows[0], rows[1], strict=True))
    assert (head["组织编号"], head["生产组织"], head["PI"], head["物料编码"], head["米数"]) == (
        org,
        f"组织{org}",
        pi,
        "M1",
        "120",
    )
    rows = xlsx_rows(get_file(api, w.admin, pi=pi, bill_no=bill, material="M2").content)
    assert [r[4] for r in rows[1:]] == every[3:]
    assert get_file(api, w.admin, pi=pi, bill_no=bill, sn_from="NOPE").json()["code"] == "EXPORT_NOTHING"


def test_push_failure_does_not_affect_export(w, api):
    """导出与推送相互独立：推送失败、批次未确认时照常列出、下载，号段也不按推送状态拆开。"""
    org, pi, bill = ready(w, 13, mes=True, batch_size=5)
    MES.behavior[org] = lambda b, h: (503, {"code": "X"})
    w.push()  # 第 1 批 5 枚未确认，另 8 枚未入批
    row = ok(api.get(f"/api/export/bills?q={bill}", w.admin))["items"][0]
    assert row["qty"] == 13
    seg = ok(api.get(f"/api/export/segments?pi={pi}&bill_no={bill}", w.admin))
    assert [(x["start_seq"], x["end_seq"], x["qty"]) for x in seg["items"]] == [(1, 13, 13)]
    r = get_file(api, w.admin, pi=pi, bill_no=bill)
    assert len(xlsx_rows(r.content)) == 14, "未确认批次里的号也导出"


def test_unallocated_not_listed_and_scope(w, api):
    org, pi, bill = ready(w, 2, allocate=False)
    assert ok(api.get(f"/api/export/bills?q={pi}", w.admin))["items"] == [], "待分配的号不展示"
    assert get_file(api, w.admin, pi=pi, bill_no=bill).json()["code"] == "EXPORT_NOTHING"
    w.allocate(pi)
    other = w.org(uid("O")[:10])
    op = w.user(emp("F"), "operator", other)
    assert ok(api.get(f"/api/export/bills?q={pi}", op))["items"] == [], "操作员只看授权组织"
    assert get_file(api, op, pi=pi, bill_no=bill, factory_code=org).json()["code"] == "FACTORY_FORBIDDEN"
    assert get_file(api, op, pi=pi, bill_no=bill).json()["code"] == "EXPORT_NOTHING"
    q = w.user(emp("Q"), "auditor", None, [org, other])
    assert ok(api.get(f"/api/export/bills?q={pi}", q))["items"][0]["qty"] == 2, "审计员可查看、下载"
    assert get_file(api, q, pi=pi, bill_no=bill).status_code == 200
    assert ok(api.get(f"/api/export/bills?q={pi}&mes=true", w.admin))["items"] == []
    assert [r["bill_no"] for r in ok(api.get(f"/api/export/bills?pi={pi}", w.admin))["items"]] == [bill]
    assert ok(api.get(f"/api/export/bills?pi={pi[:-1]}", w.admin))["items"] == [], "pi 按 PI 精确匹配（SN 分配页）"


def test_xlsx_keeps_formula_like_text_and_leading_dash_sn(w, api):
    """以 = 开头的物料编码按文本写出；前缀以 - 开头的 SN 原样导出，不加防公式前缀。"""
    org = w.org(uid("O")[:10])
    pi, bill = uid("PI"), "=" + uid("MO")
    w.order(bill, org, "C1", pi, ("=M1", 1))
    prefix = "-" + P()
    ok(w.bind_pi(pi, w.rule(prefix)))
    w.generate(pi)
    w.allocate(pi)
    r = get_file(api, w.admin, pi=pi, bill_no=bill.replace("=", "%3D"))
    row = dict(zip(*xlsx_rows(r.content)[:2], strict=True))
    assert row["物料编码"] == "=M1" and row["SN"].startswith(prefix)


def test_lines_summarize_each_bill_line(w, api):
    org, other = w.org(uid("O")[:10]), w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, ("M1", 4, "120"), ("M2", 3, "080"))
    ok(w.bind_pi(pi, w.rule(P())))
    w.generate(pi)
    w.allocate(pi)
    segs = ok(api.get(f"/api/export/segments?pi={pi}&bill_no={bill}&factory_code={org}", w.admin))["items"]
    moved = segs[0]["start_sn"]
    ok(
        w.api.post(
            "/api/transfers/org/direct",
            m(
                factory_code=org,
                pi_no=pi,
                bill_no=bill,
                scope="RANGE",
                material_code="M1",
                start_sn=moved,
                to_factory=other,
                to_pi=pi,
                to_bill=bill,
                to_material="M1",
                reason="调拨",
            ),
            w.admin,
        )
    )
    got = ok(api.get(f"/api/export/lines?pi={pi}&bill_no={bill}&factory_code={org}", w.admin)).body
    assert [(x["bill_seq"], x["material_code"], x["length"], x["qty"], x["suspended"]) for x in got] == [
        (1, "M1", "120", 3, 0),
        (2, "M2", "080", 3, 0),
    ]
    q = w.user(emp("Q"), "auditor", org)
    assert len(ok(api.get(f"/api/export/lines?pi={pi}&bill_no={bill}", q)).body) == 2
    op = w.user(emp("F"), "operator", other)
    assert api.get(f"/api/export/lines?pi={pi}&bill_no={bill}&factory_code={org}", op).code == "FACTORY_FORBIDDEN"
