"""SN 导入：表头固定为 PI + 单据编号 + SN + 生产组织；导入的号进号池（待分配），只能分配；
先检查后确定导入，有一行不能导入整个文件不导入；
导入的号不能与号池、已导入的号重复，之后生成撞上已导入的号整次回滚；普通操作员只能导入、查看授权组织的单据。"""

from __future__ import annotations

import io

from conftest import MES, emp, ok, uid
from openpyxl import Workbook

HEAD = ("PI", "单据编号", "SN", "生产组织")


def xlsx(*rows, headers=HEAD) -> bytes:
    wb = Workbook()
    ws = wb.active
    if headers is not None:
        ws.append(list(headers))
    for r in rows:
        ws.append(list(r))
    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


def upload(api, path: str, content: bytes, token: str):
    from conftest import Res

    r = api.client.request(
        "POST",
        path,
        content=content,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/octet-stream"},
    )
    data = r.json() if "json" in r.headers.get("content-type", "") and r.content else None
    return Res(r.status_code, data, r.content)


def check(api, content: bytes, token: str):
    return upload(api, "/api/sn-import/check", content, token)


def run(api, content: bytes, token: str, name: str = "t.xlsx"):
    return upload(api, f"/api/sn-import?file_name={name}", content, token)


def codes(res) -> dict[int, list[str]]:
    return {x["row"]: [i["code"] for i in x["issues"]] for x in res["issues"]}


def mk(w, *lines, org=None):
    org = org or w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, "C1", pi, *lines)
    return org, pi, bill


def test_template_has_the_four_columns(w, api):
    r = api.client.get("/api/sn-import/template", headers={"Authorization": f"Bearer {w.admin}"})
    assert r.status_code == 200
    from conftest import xlsx_rows

    assert xlsx_rows(r.content)[0] == list(HEAD)


def test_check_lists_every_bad_row_and_import_is_all_or_nothing(w, api, sql):
    org, pi, bill = mk(w, ("M1", 2))
    org2, pi2, bill2 = mk(w, ("M1", 1))
    p = "I" + uid("").replace("-", "")
    ok(w.bind_pi(pi, w.rule(p, with_length=False, pi=pi)))
    w.generate(pi)
    pooled = sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi)
    a, b = uid("SA"), uid("SB")
    content = xlsx(
        (pi, bill, a, org),  # 2 可导入（生产组织填编码）
        (pi, bill, a, org),  # 3 文件内重复
        (pi, bill, pooled, org),  # 4 号池已有
        (pi, "NO-SUCH-BILL", uid("SC"), org),  # 5 单据不在快照
        (pi2, bill, uid("SD"), org),  # 6 PI 与单据不符
        (pi, bill, None, org),  # 7 SN 为空
        (pi, bill, "A B", org),  # 8 SN 含空白
        (None, None, None, None),  # 空行跳过
        (pi2, bill2, 123456789012, f"组织{org2}"),  # 10 数字 SN 按文本；生产组织填名称
        (pi2, bill2, b, org),  # 11 生产组织与单据不符
        (pi2, bill2, uid("SE"), None),  # 12 生产组织为空
    )
    r = ok(check(api, content, w.admin))
    assert (r["total"], r["ok"], r["bad"], r["importable"]) == (10, 2, 8, False)
    assert codes(r) == {
        3: ["FILE_DUP"],
        4: ["IN_POOL"],
        5: ["BILL_NOT_FOUND"],
        6: ["PI_MISMATCH"],
        7: ["SN_EMPTY"],
        8: ["SN_SPACE"],
        11: ["ORG_MISMATCH"],
        12: ["ORG_EMPTY"],
    }
    mis = next(x for x in r["issues"] if x["row"] == 11)["issues"][0]["params"]
    assert mis == {"bill_no": bill2, "factory": f"{org2} 组织{org2}"}
    dup = next(x for x in r["issues"] if x["row"] == 3)["issues"][0]["params"]
    assert dup == {"row": 2}
    rej = run(api, content, w.admin)
    assert (rej.status, rej.code) == (400, "IMPORT_HAS_ISSUES"), str(rej)
    assert sql.count("SELECT COUNT(*) FROM sn_import_item WHERE sn IN (%s, %s)", a, "123456789012") == 0
    assert sql.count(
        "SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_IMPORT' AND result = 'FAIL' AND operator = 'admin' "
        "AND error_msg LIKE %s",
        "%8 行不能导入%",
    )


def test_import_registers_and_blocks_reimport(w, api, sql):
    org, pi, bill = mk(w, ("M1", 2), ("M2", 1))
    a, b = uid("SA"), uid("SB")
    # 表头比较时去首尾空白、不分大小写
    content = xlsx((pi, bill, a, org), (pi, bill, b, org), headers=(" pi ", "单据编号", "sn", "生产组织 "))
    r = ok(check(api, content, w.admin))
    assert (r["total"], r["ok"], r["importable"], r["pis"], r["bills"]) == (2, 2, True, 1, 1)
    done = ok(run(api, content, w.admin, "旧号.xlsx"))
    assert (done["qty"], done["pis"], done["bills"]) == (2, 1, 1)
    rows = sql.all("SELECT * FROM sn_import_item WHERE import_no = %s ORDER BY row_no", done["import_no"])
    assert [(x["sn"], x["pi_no"], x["bill_no"], x["factory_code"], x["row_no"]) for x in rows] == [
        (a, pi, bill, org, 2),
        (b, pi, bill, org, 3),
    ]
    assert rows[0]["file_name"] == "旧号.xlsx"
    au = sql.one(
        "SELECT * FROM sn_audit WHERE action = 'SN_IMPORT' AND result = 'OK' AND batch_no = %s", done["import_no"]
    )
    assert (au["pi_no"], au["bill_no"], au["factory_code"], au["qty"]) == (pi, bill, org, 2)
    # 列表：按 PI、SN 前缀、导入单号筛选
    lst = ok(api.get(f"/api/sn-import/items?pi={pi}", w.admin))
    assert [x["sn"] for x in lst["items"]] == [b, a] and lst["total"] == 2
    assert ok(api.get(f"/api/sn-import/items?sn={a}", w.admin))["total"] == 1
    assert ok(api.get(f"/api/sn-import/items?import_no={done['import_no']}", w.admin))["total"] == 2
    # 再导入同一枚号：已导入（号也在号池里，只报「已导入」）
    again = ok(check(api, xlsx((pi, bill, a, org)), w.admin))
    assert codes(again) == {2: ["IMPORTED"]}
    assert again["issues"][0]["issues"][0]["params"] == {"import_no": done["import_no"]}


def test_generation_refuses_imported_sn(w, api, sql):
    """导入了将来规则会生成的号：生成整次回滚、不跳号；号池、最大号不变。"""
    org, pi, bill = mk(w, ("M1", 3))
    p = "J" + uid("").replace("-", "")
    ok(w.bind_pi(pi, w.rule(p, with_length=False, pi=pi)))
    taken = f"{p}000002"
    ok(run(api, xlsx((pi, bill, taken, org)), w.admin))
    pv = ok(w.preview(pi))
    assert pv["generatable"], str(pv)
    r = api.post("/api/generate/run", {"token": pv["token"]}, w.admin)
    assert (r.status, r.code) == (409, "GEN_IMPORTED_SN"), str(r)
    assert r["params"] == {"sn": taken, "pi": pi}
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND origin = 'GEN'", pi) == 0
    assert (sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) or 0) == 0


def test_imported_sns_wait_on_allocation_page_and_can_only_be_allocated(w, api, sql):
    """导入的号进号池待分配（单据序号 0、没有规则与流水、客户与组织取快照），在分配页标为「导入」行；
    不在生成页（统计、单据分配状态、号段都不计，不占可生成数量），不能删号、转移；分配到单据的生产组织后照常推送。"""
    org, pi, bill = mk(w, ("M1", 2))
    w.enable_mes(org)
    a, b = uid("SA"), uid("SB")
    done = ok(run(api, xlsx((pi, bill, a, org), (pi, bill, b, org)), w.admin))
    rows = sql.all("SELECT * FROM sn_item WHERE sn IN (%s, %s) ORDER BY sn", a, b)
    assert [
        (x["origin"], x["status"], x["pi_no"], x["bill_no"], x["bill_seq"], x["material_code"], x["seq_pi_no"])
        for x in rows
    ] == [("IMPORT", "PENDING_ALLOC", pi, bill, 0, "", "")] * 2
    # 只填文件里有的列：客户、物料、附件项次、米数为空
    assert {
        (x["customer_code"], x["attach_no"], x["length_text"], x["gen_org_code"], x["rule_version_id"], x["gen_batch"])
        for x in rows
    } == {("", "", None, org, 0, done["import_no"])}
    assert sql.count("SELECT COUNT(*) FROM sn_bill_ledger WHERE bill_no = %s", bill) == 1, "与生成一样建立台账"
    # 生成页：不计导入的号，可生成数量不变
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    gb = next(x for x in ctx["bills"] if x["bill_no"] == bill)
    assert (gb["alloc_state"], gb["pending_alloc"], gb["quota"], gb["segments"]["use_count"]) == (None, 0, 2, 0)
    assert (ctx["summary"]["pending_alloc"], ctx["summary"]["transferred_in"]) == (0, 0)
    gp = next(x for x in ok(api.get(f"/api/generate/pis?q={pi}", w.admin))["items"] if x["pi_no"] == pi)
    assert (gp["pending_alloc"], gp["quota"]) == (0, 2)
    assert ok(api.get(f"/api/generate/segments?pi={pi}&bill_no={bill}", w.admin))["use"] == []
    # 删号：按单据删没有号，按 SN 删视为不存在
    d = api.post("/api/generate/delete", {"pi_no": pi, "bills": [bill]}, w.admin)
    assert d.code == "DELETE_NOTHING", str(d)
    d = api.post("/api/generate/delete", {"pi_no": pi, "start_sn": a}, w.admin)
    assert d.code == "DELETE_SN_NOT_FOUND", str(d)
    # 分配页：一行「导入」（单据序号 0），分到单据的生产组织
    al = ok(api.get(f"/api/allocation/context?pi={pi}", w.admin))
    line = next(x for x in al["lines"] if x["bill_no"] == bill)
    assert (line["seq"], line["imported"], line["pending_alloc"], line["org_code"]) == (0, True, 2, org)
    assert al["summary"]["pending_alloc"] == 2 and al["summary"]["transferred_in"] == 0
    left = ok(api.get(f"/api/allocation/bills?q={bill}", w.admin))["items"]
    assert [(x["bill_no"], x["state"], x["pending_alloc"]) for x in left] == [(bill, "PENDING", 2)]
    r = w.allocate(pi, lines=[(bill, 0)])
    assert (r["qty"], r["orgs"]) == (2, [org])
    assert {x["status"] for x in sql.all("SELECT status FROM sn_item WHERE sn IN (%s, %s)", a, b)} == {"TO_PUSH"}
    # 推送：明细只带文件里有的 SN、PI、单据编号（生产组织在批次头），其余为 null
    w.push()
    sent = [x["batch"] for x in MES.received if any(i["sn"] in (a, b) for i in x["batch"].get("items", []))]
    assert sent and sent[-1]["orgCode"] == org
    items = [i for x in sent for i in x["items"] if i["sn"] in (a, b)]
    assert len(items) == 2
    blank = ("serial", "serialDec", "serialPi", "billSeq", "attachNo", "materialCode", "length", "customerCode")
    for i in items:
        assert (i["pi"], i["billNo"], i["rev"]) == (pi, bill, 1)
        assert {k: i[k] for k in blank} == dict.fromkeys(blank), i
    # 接口 ④ 读回的明细同样
    tok = w.receipt_token(org)
    got = ok(w.mes_delivered(tok, pi=pi))
    back = [i for i in got["items"] if i["sn"] in (a, b)]
    assert len(back) == 2 and all(i["serial"] is None and i["customerCode"] is None for i in back), back
    assert {x["status"] for x in sql.all("SELECT status FROM sn_item WHERE sn IN (%s, %s)", a, b)} == {"PUSHED"}
    st = ok(api.get(f"/api/sn-import/items?import_no={done['import_no']}", w.admin))["items"]
    assert {(x["status"], x["allocated_to"]) for x in st} == {("PUSHED", org)}
    # 查询：十进制流水为空，不算转入
    q = ok(api.get(f"/api/sn?sn={a}", w.admin))["items"][0]
    assert (q["seq_dec"], q["seq_text"], q["transferred_in"]) == ("", "", False)
    # 分配后也不能转移
    loc = api.get(f"/api/transfers/locate?q={a}", w.admin)
    assert loc.code == "TR_SN_NOT_TRANSFERABLE", str(loc)


def test_bill_transfer_leaves_imported_sns_behind(w, api, sql):
    """单据里既有生成的号又有导入的号：整张单据转单只转生成的号，导入的号留在原单据。"""
    org, pi, bill = mk(w, ("M1", 2))
    _, _, to_bill = mk(w, ("M1", 5), org=org)
    sql_pi_to = sql.scalar("SELECT pi FROM sn_prd_mo WHERE bill_no = %s LIMIT 1", to_bill)
    p = "K" + uid("").replace("-", "")
    ok(w.bind_pi(pi, w.rule(p, with_length=False, pi=pi)))
    w.generate(pi)
    a = uid("SA")
    ok(run(api, xlsx((pi, bill, a, org)), w.admin))
    w.allocate(pi)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND status = 'ALLOCATED'", bill) == 3
    body = {
        "pi_no": pi,
        "bill_no": bill,
        "scope": "BILL",
        "to_pi": sql_pi_to,
        "to_bill": to_bill,
        "to_factory": org,
        "to_material": "M1",
        "reason": "转单",
    }
    pre = ok(api.post("/api/transfers/bill/preview", body, w.admin))
    assert (pre["total"], pre["transferable"], pre["blocked"]) == (3, 2, {"IMPORTED": 1}), str(pre)
    ok(api.post("/api/transfers/bill", body, w.admin))
    assert sql.one("SELECT bill_no, bill_seq FROM sn_item WHERE sn = %s", a) == {"bill_no": bill, "bill_seq": 0}
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE bill_no = %s AND origin = 'GEN'", to_bill) == 2


def test_operator_only_imports_and_sees_authorized_orgs(w, api, sql):
    org_a, pi_a, bill_a = mk(w, ("M1", 1))
    org_b, pi_b, bill_b = mk(w, ("M1", 1))
    op = w.user(emp("OP"), "operator", None, [org_a])
    a, b = uid("SA"), uid("SB")
    r = ok(check(api, xlsx((pi_a, bill_a, a, org_a), (pi_b, bill_b, b, org_b)), op))
    assert codes(r) == {3: ["FACTORY_FORBIDDEN"]}
    ok(run(api, xlsx((pi_a, bill_a, a, org_a)), op))
    ok(run(api, xlsx((pi_b, bill_b, b, org_b)), w.admin))
    mine = ok(api.get(f"/api/sn-import/items?sn={a}", op))
    assert mine["total"] == 1
    assert ok(api.get(f"/api/sn-import/items?sn={b}", op))["total"] == 0
    aud = w.user(emp("AU"), "auditor", None, [org_a])
    assert api.get("/api/sn-import/items", aud).status == 403
    assert check(api, xlsx((pi_a, bill_a, uid("SC"), org_a)), aud).status == 403


def test_bad_files(w, api):
    assert check(api, b"not an xlsx", w.admin).code == "IMPORT_FILE_INVALID"
    assert check(api, b"", w.admin).code == "IMPORT_EMPTY"
    # 表头固定：少列、多列、换顺序、改列名都不行
    for head in (
        ("PI", "单据编号", "SN"),
        (*HEAD, "其他"),
        ("单据编号", "PI", "SN", "生产组织"),
        ("PI", "单据", "SN", "生产组织"),
    ):
        r = check(api, xlsx(("P", "B", "S", "O"), headers=head), w.admin)
        assert (r.code, r["params"]) == ("IMPORT_HEADER_INVALID", {"columns": "PI、单据编号、SN、生产组织"}), head
    assert check(api, xlsx(headers=HEAD), w.admin).code == "IMPORT_EMPTY"


def test_delete_whole_import_only_while_all_pending(w, api, sql):
    """整单删除：导入单的号全部待分配时才能删，号从号池移除、记录标为已删除，之后可重新导入同样的号；
    有一枚已分配就不能删；删过的再删报不存在；普通操作员只能删授权组织的导入单。"""
    org, pi, bill = mk(w, ("M1", 1))
    _, pi2, bill2 = mk(w, ("M1", 1), org=org)
    a, b, c = uid("SA"), uid("SB"), uid("SC")
    first = ok(run(api, xlsx((pi, bill, a, org), (pi2, bill2, b, org)), w.admin))["import_no"]
    s = ok(api.get(f"/api/sn-import/imports/{first}", w.admin))
    assert (s["qty"], s["pending"], s["allocated"], s["deletable"], s["bills"]) == (
        2,
        2,
        0,
        True,
        sorted([bill, bill2]),
    )
    other = w.user(emp("OP"), "operator", None, [w.org(uid("O")[:10])])
    assert api.post(f"/api/sn-import/imports/{first}/delete", {}, other).code == "FACTORY_FORBIDDEN"
    d = ok(api.post(f"/api/sn-import/imports/{first}/delete", {}, w.admin))
    assert (d["qty"], d["bills"]) == (2, 2)
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE sn IN (%s, %s)", a, b) == 0
    assert sql.count("SELECT COUNT(*) FROM sn_import_item WHERE import_no = %s AND deleted_by = 'admin'", first) == 2
    assert sql.count("SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_IMPORT_DELETE' AND batch_no = %s", first) == 2
    assert api.post(f"/api/sn-import/imports/{first}/delete", {}, w.admin).code == "IMPORT_NOT_FOUND"
    # 删后可重新导入同样的号；列表里旧记录为已删除，新记录待分配
    again = ok(run(api, xlsx((pi, bill, a, org), (pi, bill, c, org)), w.admin))["import_no"]
    rows = ok(api.get(f"/api/sn-import/items?sn={a}", w.admin))["items"]
    assert [(x["import_no"], x["status"], x["deleted_at"] is not None) for x in rows] == [
        (again, "PENDING_ALLOC", False),
        (first, None, True),
    ]
    # 有一枚已分配：整单不能删，号都还在
    w.allocate(pi, lines=[(bill, 0)])
    s = ok(api.get(f"/api/sn-import/imports/{again}", w.admin))
    assert (s["allocated"], s["deletable"]) == (2, False)
    r = api.post(f"/api/sn-import/imports/{again}/delete", {}, w.admin)
    assert (r.status, r.code, r["params"]) == (409, "IMPORT_DELETE_ALLOCATED", {"import_no": again, "count": 2})
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE sn IN (%s, %s)", a, c) == 2
