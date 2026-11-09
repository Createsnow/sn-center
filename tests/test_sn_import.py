"""SN 导入：系统定的固定模板（PI、单据编号、附件项次、物料编码、ERP行号、完整 SN、流水位数、进制位数、流水号、
十进制流水号、米数、产品单位、生产组织编号、生产组织，与分配页「已分配」下载的列相同；流水号、米数、产品单位、
生产组织编号选填，流水号不填时按十进制流水号换算；客户编码不在模板里，取单据的），按列名认列、顺序不限、其他列不读；
逐行按订单快照检查（PI、单据、ERP行号、物料编码、附件项次、生产组织须与单据一致；单据已有生成或已分配的号不能导入），
先检查后确定导入，有一行不能导入整个文件不导入；
导入的号进号池（待分配，号池结构不变：单据序号 0，行号、流水号等在导入记录），之后生成撞上导入的号整次回滚；
推送 / 拉取 / 转出给 MES 的明细字段都有值；分配后可与生成的号一样转移（所在物料行记在导入记录，不计已生成数）；
普通操作员只能导入、查看授权组织的单据。"""

from __future__ import annotations

import io

from conftest import MES, emp, m, ok, uid, xlsx_rows
from openpyxl import Workbook

from app.core.encoding import DEFAULT_CHARSETS
from app.services import sn_export
from app.services.sn_import import HEADERS as FULL

#: rec 的字段 → 列名
COL = {
    "pi": "PI",
    "bill": "单据编号",
    "seq": "ERP行号",
    "material": "物料编码",
    "sn": "完整 SN",
    "seq_len": "流水位数",
    "base": "进制位数",
    "serial": "流水号",
    "dec": "十进制流水号",
    "attach": "附件项次",
    "length": "米数",
    "unit": "产品单位",
    "org": "生产组织编号",
    "org_name": "生产组织",
}
#: 别名列 → 字段
ALIAS = {
    "SN": "sn",
    "sn": "sn",
    "物料编号": "material",
    " pi ": "pi",
    "单据 编号": "bill",
    "行号": "seq",
    "单据行号": "seq",
    "十进制流水": "dec",
}
ALIAS |= {f"* {v}": k for k, v in COL.items()} | {f"＊{v}": k for k, v in COL.items()}


def dec_of(serial: str, base: str) -> str:
    """流水号按进制默认字符表换算的十进制（换算不了时给 "1"，留给被测的流水号检查报错）。"""
    chars = DEFAULT_CHARSETS.get(int(base) if str(base).isdigit() else 0)
    if not serial or not chars or any(ch not in chars for ch in serial):
        return "1"
    return str(sum(chars.index(ch) * int(base) ** i for i, ch in enumerate(reversed(serial))))


def rec(pi, bill, sn, org, **kw) -> dict:
    """一行（默认对上 mk 建的单据第 1 行：物料 M1、附件项次 1、米数 100、生产组织名称「组织{org}」），
    kw 按字段名改；没给十进制流水号时按流水号换算（与流水号一致）。"""
    r = dict(
        pi=pi,
        bill=bill,
        seq="1",
        material="M1",
        sn=sn,
        seq_len="6",
        base="10",
        serial="000001",
        attach="1",
        length="100",
        unit="",
        org=org,
        org_name=f"组织{org}",
    )
    r.update(kw)
    if "dec" not in kw:
        r["dec"] = dec_of(str(r["serial"] or ""), str(r["base"] or ""))
    return r


def xlsx(*rows, headers=FULL) -> bytes:
    """rows 为 rec 的 dict（按 headers 的列名取值；不是模板列名的按原样作键）；headers = None 不写表头。"""
    by_name = {v: k for k, v in COL.items()} | ALIAS
    wb = Workbook()
    ws = wb.active
    if headers is not None:
        ws.append(list(headers))
    for r in rows:
        ws.append([r.get(by_name.get(h, h)) for h in headers or FULL])
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


def issue(res, row: int, n: int = 0) -> dict:
    return next(x for x in res["issues"] if x["row"] == row)["issues"][n]


def mk(w, *lines, org=None, customer="C1"):
    org = org or w.org(uid("O")[:10])
    pi, bill = uid("PI"), uid("MO")
    w.order(bill, org, customer, pi, *lines)
    return org, pi, bill


def get_bytes(api, path: str, token: str) -> bytes:
    r = api.client.get(path, headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200, r.text[:300]
    return r.content


def test_template_marks_required_columns(w, api, sql):
    """模板：必填列名前带 *、标红并带批注；第二张表「填写说明」逐列写明是否必填、要求与示例；
    下载的模板直接填好就能导入（表头里的 * 不参与比较）。"""
    import openpyxl

    content = get_bytes(api, "/api/sn-import/template", w.admin)
    wb = openpyxl.load_workbook(io.BytesIO(content))
    assert wb.sheetnames == ["SN导入", "填写说明"]
    ws = wb["SN导入"]
    head = [c.value for c in ws[1]]
    assert head == [
        "*PI",
        "*单据编号",
        "*附件项次",
        "*物料编码",
        "*ERP行号",
        "*完整 SN",
        "*流水位数",
        "*进制位数",
        "流水号",
        "*十进制流水号",
        "米数",
        "产品单位",
        "生产组织编号",
        "*生产组织",
    ]
    assert ws.max_row == 1, "只有表头，没有示例数据行"
    reds = {c.value for c in ws[1] if c.font.color is not None and c.font.color.rgb.endswith("C00000")}
    assert reds == {h for h in head if h.startswith("*")}
    assert all(c.comment is not None and c.comment.text.startswith(("必填", "选填")) for c in ws[1])
    guide = [r for r in wb["填写说明"].iter_rows(values_only=True)]
    assert guide[0] == ("列名", "是否必填", "填写要求", "示例")
    req = {r[0]: r[1] for r in guide[1 : len(FULL) + 1]}
    assert req == {h.lstrip("*"): "必填" if h.startswith("*") else "选填" for h in head}
    # 用模板的表头（带 *）直接导入
    org, pi, bill = mk(w, ("M1", 1))
    a = uid("SA")
    wb["SN导入"].append([rec(pi, bill, a, org).get({v: k for k, v in COL.items()}[h.lstrip("*")]) for h in head])
    out = io.BytesIO()
    wb.save(out)
    r = ok(check(api, out.getvalue(), w.admin))
    assert (r["total"], r["ok"]) == (1, 1), str(r)
    ok(run(api, out.getvalue(), w.admin))
    assert sql.count("SELECT COUNT(*) FROM sn_import_item WHERE sn = %s", a) == 1
    assert FULL == [
        "PI",
        "单据编号",
        "附件项次",
        "物料编码",
        "ERP行号",
        "完整 SN",
        "流水位数",
        "进制位数",
        "流水号",
        "十进制流水号",
        "米数",
        "产品单位",
        "生产组织编号",
        "生产组织",
    ]
    assert FULL == sn_export.FILE_HEADERS, "模板的列与分配页「已分配」下载的列相同"


def test_check_lists_every_bad_row_and_import_is_all_or_nothing(w, api, sql):
    org, pi, bill = mk(w, ("M1", 2), ("M2", 2, "200", "A2"))
    org2, pi2, bill2 = mk(w, ("M1", 1))
    _, pi3, bill3 = mk(w, ("M3", 1, "", ""), org=org)
    # 另一张已生成的单据：号在号池里，单据本身也不能再导入
    _, pig, billg = mk(w, ("M1", 1), ("M2", 1, "200", "A2"), org=org)
    p = "I" + uid("").replace("-", "")
    ok(w.bind_pi(pig, w.rule(p, with_length=False, pi=pig)))
    w.generate(pig, lines=[(billg, 1)])
    pooled = sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pig)
    a = uid("SA")
    rows = [
        rec(pi, bill, a, org),  # 2 可导入
        rec(pi, bill, a, org),  # 3 文件内重复
        rec(pi, bill, pooled, org),  # 4 号池已有
        rec(pi, "NO-SUCH-BILL", uid("S"), org),  # 5 单据不在快照
        rec(pi2, bill, uid("S"), org),  # 6 PI 与单据不符
        rec(pi, bill, None, org),  # 7 SN 为空
        rec(pi, bill, "A B", org),  # 8 SN 含空白
        {},  # 空行跳过
        rec(pi2, bill2, 123456789012, org2),  # 10 数字 SN 按文本；生产组织编号、名称相符
        rec(pi2, bill2, uid("S"), org),  # 11 生产组织编号与单据不符
        rec(pi2, bill2, uid("S"), org2, org_name="别的组织"),  # 12 编号相符、生产组织名称不符
        rec(pi, bill, uid("S"), org),  # 13 可导入（模板没有客户编码列，客户取单据的）
        rec(pi, bill, uid("S"), org, seq="9"),  # 14 行号不在单据里
        rec(pi, bill, uid("S"), org, seq="x"),  # 15 行号不是正整数
        rec(pi, bill, uid("S"), org, material="M2"),  # 16 第 1 行不是 M2
        rec(pi, bill, uid("S"), org, seq="2", material="M2", attach="A9", length="200"),  # 17 附件项次不符
        rec(pi, bill, uid("S"), org, length="150"),  # 18 米数不符
        rec(pi3, bill3, uid("S"), org, material="M3", attach="Z1", length=""),  # 19 该行没有附件项次：不能导入
        rec(pi, bill, uid("S"), org, base="8"),  # 20 进制不对
        rec(pi, bill, uid("S"), org, seq_len="13", serial="0" * 13),  # 21 流水位数超过 12（流水号也超长）
        rec(pi, bill, uid("S"), org, serial="00001"),  # 22 流水号位数不对
        rec(pi, bill, uid("S"), org, base="32", serial="00000I"),  # 23 32 进制不用 I
        rec(pi, bill, uid("S"), org, base="16", serial="00000a"),  # 24 字母要大写
        rec(pi, bill, uid("S"), org, material="", serial="", dec=""),  # 25 必填为空（流水号选填）
        rec(pig, billg, uid("S"), org, seq="2", material="M2", attach="A2", length="200"),  # 26 单据已有生成的号
        rec(pi, bill, uid("S"), org, serial="000002", dec="1"),  # 27 流水号与十进制流水号不符
        rec(pi, bill, uid("S"), org, serial="", dec="1000000"),  # 28 十进制流水号超出 10 进制 6 位
        rec(pi, bill, uid("S"), org, serial="", dec="-1"),  # 29 十进制流水号不是非负整数
        rec(pi, bill, uid("S"), org, serial="", dec="42"),  # 30 流水号不填：按十进制流水号换算
        rec(pi, bill, uid("S"), "", org_name=f"组织{org}"),  # 31 生产组织编号不填：按生产组织名称对照
        rec(pi, bill, uid("S"), "", org_name="别的组织"),  # 32 没填编号、生产组织名称与单据不符
        rec(pi, bill, uid("S"), org, org_name=""),  # 33 生产组织为空
    ]
    r = ok(check(api, xlsx(*rows), w.admin))
    assert (r["total"], r["ok"], r["bad"], r["importable"]) == (31, 5, 26, False), str(r)
    assert codes(r) == {
        3: ["FILE_DUP"],
        4: ["IN_POOL"],
        5: ["BILL_NOT_FOUND"],
        6: ["PI_MISMATCH"],
        7: ["EMPTY"],
        8: ["SN_SPACE"],
        11: ["ORG_MISMATCH"],
        12: ["ORG_NAME_MISMATCH"],
        14: ["LINE_NOT_FOUND"],
        15: ["SEQ_INVALID"],
        16: ["MATERIAL_MISMATCH"],
        17: ["ATTACH_MISMATCH"],
        18: ["LENGTH_MISMATCH"],
        19: ["LINE_NO_ATTACH"],
        20: ["BASE_INVALID"],
        21: ["TOO_LONG", "SEQ_LEN_INVALID"],
        22: ["SERIAL_LEN_MISMATCH"],
        23: ["SERIAL_CHAR_INVALID"],
        24: ["SERIAL_CHAR_INVALID"],
        25: ["EMPTY", "EMPTY"],
        26: ["BILL_HAS_SN"],
        27: ["SERIAL_DEC_MISMATCH"],
        28: ["DEC_OVERFLOW"],
        29: ["DEC_INVALID"],
        32: ["ORG_MISMATCH"],
        33: ["EMPTY"],
    }, codes(r)
    assert issue(r, 3)["params"] == {"row": 2}
    assert issue(r, 7)["params"] == {"column": "sn"}
    assert issue(r, 11)["params"] == {"bill_no": bill2, "factory": f"{org2} 组织{org2}"}
    assert issue(r, 12)["params"] == {"factory": org2, "name": f"组织{org2}"}
    assert issue(r, 14)["params"] == {"bill_no": bill, "seq": 9}
    assert issue(r, 16)["params"] == {"bill_no": bill, "seq": 1, "material": "M1"}
    assert issue(r, 17)["params"] == {"bill_no": bill, "seq": 2, "attach": "A2"}
    assert issue(r, 18)["params"] == {"bill_no": bill, "seq": 1, "length": "100"}
    assert issue(r, 19)["params"] == {"bill_no": bill3, "seq": 1}
    assert issue(r, 22)["params"] == {"len": 6}
    assert issue(r, 23)["params"]["base"] == 32
    assert [i["params"]["column"] for i in next(x for x in r["issues"] if x["row"] == 25)["issues"]] == [
        "material",
        "dec_in",
    ]
    assert issue(r, 26)["params"] == {"bill_no": billg, "count": 1}
    assert issue(r, 27)["params"] == {"serial": "000002", "dec": 2}
    assert issue(r, 28)["params"] == {"base": 10, "len": 6, "max": 999999}
    assert issue(r, 32)["params"] == {"bill_no": bill, "factory": f"{org} 组织{org}"}
    assert issue(r, 33)["params"] == {"column": "org_name"}
    rej = run(api, xlsx(*rows), w.admin)
    assert (rej.status, rej.code) == (400, "IMPORT_HAS_ISSUES"), str(rej)
    assert sql.count("SELECT COUNT(*) FROM sn_import_item WHERE sn IN (%s, %s)", a, "123456789012") == 0
    assert sql.count(
        "SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_IMPORT' AND result = 'FAIL' AND operator = 'admin' "
        "AND error_msg LIKE %s",
        "%26 行不能导入%",
    )


def test_bill_with_generated_or_allocated_sns_cannot_import(w, api, sql):
    """单据在本系统已有生成的号（不论哪个物料行、是否已分配）或已分配的导入号时不能导入；
    只有待分配的导入号时可以继续导入；号删掉后又可以导入。"""
    org, pi, bill = mk(w, ("M1", 2), ("M2", 2, "200", "A2"))
    line2 = dict(seq="2", material="M2", attach="A2", length="200")
    # 只有待分配的导入号：同一单据可分几次导入
    ok(run(api, xlsx(rec(pi, bill, uid("SA"), org)), w.admin))
    ok(run(api, xlsx(rec(pi, bill, uid("SB"), org, serial="000002")), w.admin))
    # 导入的号已分配：整张单据不能再导入（别的物料行也不行）
    w.allocate(pi, lines=[(bill, 0)])
    r = ok(check(api, xlsx(rec(pi, bill, uid("SC"), org, **line2)), w.admin))
    assert codes(r) == {2: ["BILL_HAS_SN"]} and issue(r, 2)["params"] == {"bill_no": bill, "count": 2}, str(r)
    # 生成的号（还待分配）挂在第 1 行：导入到第 2 行也不能
    _, pi2, bill2 = mk(w, ("M1", 2), ("M2", 2, "200", "A2"), org=org)
    p = "B" + uid("").replace("-", "")
    ok(w.bind_pi(pi2, w.rule(p, with_length=False, pi=pi2)))
    w.generate(pi2, lines=[(bill2, 1)])
    rej = run(api, xlsx(rec(pi2, bill2, uid("SD"), org, **line2)), w.admin)
    assert (rej.status, rej.code) == (400, "IMPORT_HAS_ISSUES"), str(rej)
    r = ok(check(api, xlsx(rec(pi2, bill2, uid("SD"), org, **line2)), w.admin))
    assert codes(r) == {2: ["BILL_HAS_SN"]} and issue(r, 2)["params"] == {"bill_no": bill2, "count": 2}, str(r)
    # 号删掉后单据没有号了：可以导入
    ok(w.delete(pi2, bills=[bill2]))
    ok(run(api, xlsx(rec(pi2, bill2, uid("SD"), org, **line2)), w.admin))


def test_import_registers_every_column_and_blocks_reimport(w, api, sql):
    org, pi, bill = mk(w, ("M1", 2), ("M2", 1, "0200", "A2"))
    a, b, c = uid("SA"), uid("SB"), uid("SC")
    content = xlsx(
        rec(pi, bill, a, org, serial="00A1Z", seq_len="5", base="36", unit="M"),
        rec(pi, bill, b, org, seq="2", material="M2", attach="A2", length="0200", serial="000002"),
        # 流水号、生产组织编号不填：流水号按十进制流水号换算（补前导 0），生产组织取单据的
        rec(pi, bill, c, "", serial="", dec="1295", seq_len="3", base="36", org_name=f"组织{org}"),
    )
    r = ok(check(api, content, w.admin))
    assert (r["total"], r["ok"], r["importable"], r["pis"], r["bills"]) == (3, 3, True, 1, 1), str(r)
    done = ok(run(api, content, w.admin, "旧号.xlsx"))
    assert (done["qty"], done["pis"], done["bills"]) == (3, 1, 1)
    rows = sql.all("SELECT * FROM sn_import_item WHERE import_no = %s ORDER BY row_no", done["import_no"])
    cols = (
        "sn",
        "pi_no",
        "bill_no",
        "bill_seq",
        "cur_bill_seq",
        "material_code",
        "attach_no",
        "length_text",
        "serial_text",
        "seq_base",
        "seq_len",
        "seq_dec",
        "unit",
        "customer_code",
        "factory_code",
        "row_no",
    )
    assert [tuple(x[k] for k in cols) for x in rows] == [
        (a, pi, bill, 1, 1, "M1", "1", "100", "00A1Z", 36, 5, 10 * 36**2 + 1 * 36 + 35, "M", "C1", org, 2),
        (b, pi, bill, 2, 2, "M2", "A2", "0200", "000002", 10, 6, 2, "", "C1", org, 3),
        (c, pi, bill, 1, 1, "M1", "1", "100", "0ZZ", 36, 3, 1295, "", "C1", org, 4),
    ]
    assert rows[0]["file_name"] == "旧号.xlsx"
    # 号池结构不变：单据序号 0、流水归属空串、十进制流水为导入记录 id；物料、附件项次、米数、客户照常存
    items = sql.all("SELECT * FROM sn_item WHERE sn IN (%s, %s) ORDER BY sn", a, b)
    assert sql.scalar("SELECT gen_org_code FROM sn_item WHERE sn = %s", c) == org
    assert {
        x["sn"]: (x["origin"], x["status"], x["bill_seq"], x["seq_pi_no"], x["seq_dec"], x["rule_version_id"])
        for x in items
    } == {
        a: ("IMPORT", "PENDING_ALLOC", 0, "", rows[0]["id"], 0),
        b: ("IMPORT", "PENDING_ALLOC", 0, "", rows[1]["id"], 0),
    }
    assert {
        x["sn"]: (x["material_code"], x["attach_no"], x["length_text"], x["customer_code"], x["gen_org_code"])
        for x in items
    } == {a: ("M1", "1", "100", "C1", org), b: ("M2", "A2", "0200", "C1", org)}
    au = sql.one(
        "SELECT * FROM sn_audit WHERE action = 'SN_IMPORT' AND result = 'OK' AND batch_no = %s", done["import_no"]
    )
    assert (au["pi_no"], au["bill_no"], au["factory_code"], au["qty"]) == (pi, bill, org, 3)
    # 列表：按 PI、SN 前缀、导入单号筛选；带导入时的列与当前挂靠
    lst = ok(api.get(f"/api/sn-import/items?pi={pi}", w.admin))
    assert [x["sn"] for x in lst["items"]] == [c, b, a] and lst["total"] == 3
    x = lst["items"][2]
    assert (x["bill_seq"], x["cur_bill_seq"], x["serial_text"], x["seq_base"], x["seq_len"], x["unit"]) == (
        1,
        1,
        "00A1Z",
        36,
        5,
        "M",
    )
    assert (x["material_code"], x["attach_no"], x["length"], x["customer_code"]) == ("M1", "1", "100", "C1")
    assert (x["status"], x["cur_pi"], x["cur_bill"]) == ("PENDING_ALLOC", pi, bill)
    assert ok(api.get(f"/api/sn-import/items?sn={a}", w.admin))["total"] == 1
    assert ok(api.get(f"/api/sn-import/items?import_no={done['import_no']}", w.admin))["total"] == 3
    # 再导入同一枚号：已导入（号也在号池里，只报「已导入」）
    again = ok(check(api, xlsx(rec(pi, bill, a, org)), w.admin))
    assert codes(again) == {2: ["IMPORTED"]}
    assert again["issues"][0]["issues"][0]["params"] == {"import_no": done["import_no"]}


def test_customer_code_comes_from_bill(w, api, sql):
    """模板没有客户编码列：导入记录与号池的客户编码取单据的；单据没有客户编码也能导入；
    旧模板里的「客户编码」列（带 * 或不带、填什么）不读，不影响检查与导入。"""
    org, pi, bill = mk(w, ("M1", 2), customer="")
    a, b = uid("SA"), uid("SB")
    ok(run(api, xlsx(rec(pi, bill, a, org)), w.admin))
    old = [*FULL, "*客户编码"]
    content = xlsx({**rec(pi, bill, b, org, serial="000002"), "*客户编码": "C9"}, headers=old)
    r = ok(check(api, content, w.admin))
    assert (r["total"], r["ok"], r["importable"]) == (1, 1, True), str(r)
    ok(run(api, content, w.admin))
    got = {
        x["sn"]: x["customer_code"]
        for x in sql.all("SELECT sn, customer_code FROM sn_import_item WHERE sn IN (%s, %s)", a, b)
    }
    assert got == {a: "", b: ""}, "取单据的客户编码（为空）"
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE sn IN (%s, %s) AND customer_code = ''", a, b) == 2
    org2, pi2, bill2 = mk(w, ("M1", 1), customer="C7")
    c = uid("SC")
    ok(run(api, xlsx({**rec(pi2, bill2, c, org2), "客户编码": "C9"}, headers=[*FULL, "客户编码"]), w.admin))
    assert sql.scalar("SELECT customer_code FROM sn_import_item WHERE sn = %s", c) == "C7"
    assert sql.scalar("SELECT customer_code FROM sn_item WHERE sn = %s", c) == "C7"


def test_generation_refuses_imported_sn(w, api, sql):
    """导入了将来规则会生成的号：生成整次回滚、不跳号；号池、最大号不变。"""
    org, pi, bill = mk(w, ("M1", 3))
    p = "J" + uid("").replace("-", "")
    ok(w.bind_pi(pi, w.rule(p, with_length=False, pi=pi)))
    taken = f"{p}000002"
    ok(run(api, xlsx(rec(pi, bill, taken, org, serial="000002")), w.admin))
    pv = ok(w.preview(pi))
    assert pv["generatable"], str(pv)
    r = api.post("/api/generate/run", {"token": pv["token"]}, w.admin)
    assert (r.status, r.code) == (409, "GEN_IMPORTED_SN"), str(r)
    assert r["params"] == {"sn": taken, "pi": pi}
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s AND origin = 'GEN'", pi) == 0


FIELDS = ("serial", "serialDec", "serialPi", "billSeq", "attachNo", "materialCode", "length", "customerCode")


def test_imported_sns_are_allocated_and_delivered_with_full_items(w, api, sql):
    """导入的号在分配页标为「导入」行（单据序号 0），不在生成页（不占可生成数量）；分配后照常推送，与生成的号分开成批，
    明细字段、顺序与生成的号相同，都有值（模拟 MES 按接口必填项校验后照常接收）；MES 查询拿到的相同。"""
    org, pi, bill = mk(w, ("M1", 2))
    w.enable_mes(org)
    a, b = uid("SA"), uid("SB")
    ok(run(api, xlsx(rec(pi, bill, a, org, serial="000007"), rec(pi, bill, b, org, serial="000008")), w.admin))
    assert sql.count("SELECT COUNT(*) FROM sn_bill_ledger WHERE bill_no = %s", bill) == 1, "与生成一样建立台账"
    # 生成页：不计导入的号，可生成数量不变
    ctx = ok(api.get(f"/api/generate/context?pi={pi}", w.admin))
    gb = next(x for x in ctx["bills"] if x["bill_no"] == bill)
    assert (gb["alloc_state"], gb["pending_alloc"], gb["quota"], gb["segments"]["use_count"]) == (None, 0, 2, 0)
    # 删号：按单据删没有号，按 SN 删视为不存在
    assert api.post("/api/generate/delete", {"pi_no": pi, "bills": [bill]}, w.admin).code == "DELETE_NOTHING"
    assert api.post("/api/generate/delete", {"pi_no": pi, "start_sn": a}, w.admin).code == "DELETE_SN_NOT_FOUND"
    # 分配页：一行「导入」（单据序号 0），分到单据的生产组织
    al = ok(api.get(f"/api/allocation/context?pi={pi}", w.admin))
    line = next(x for x in al["lines"] if x["bill_no"] == bill)
    assert (line["seq"], line["imported"], line["pending_alloc"], line["org_code"], line["material_code"]) == (
        0,
        True,
        2,
        org,
        "M1",
    )
    r = w.allocate(pi, lines=[(bill, 0)])
    assert (r["qty"], r["orgs"]) == (2, [org])
    # 同一 PI、同一组织另有生成的号：与导入的号分开成批
    p = "G" + uid("").replace("-", "")
    ok(w.bind_pi(pi, w.rule(p, with_length=False, pi=pi)))
    w.generate(pi)
    w.allocate(pi, lines=[(bill, 1)])
    gen = [x["sn"] for x in sql.all("SELECT sn FROM sn_item WHERE pi_no = %s AND origin = 'GEN'", pi)]
    w.push()
    sent = [x["batch"] for x in MES.received if x["batch"].get("pi") == pi and x["batch"].get("items")]
    assert all({i["sn"] in (a, b) for i in x["items"]} in ({True}, {False}) for x in sent), "导入的号单独成批"
    items = {i["sn"]: i for x in sent for i in x["items"]}
    assert list(items[a]) == list(items[gen[0]]), "明细字段与顺序同生成的号"
    assert {k: items[a][k] for k in FIELDS} == {
        "serial": "000007",
        "serialDec": "7",
        "serialPi": pi,
        "billSeq": 1,
        "attachNo": "1",
        "materialCode": "M1",
        "length": "100",
        "customerCode": "C1",
    }
    assert (items[b]["serial"], items[b]["serialDec"]) == ("000008", "8")
    assert {x["status"] for x in sql.all("SELECT status FROM sn_item WHERE pi_no = %s", pi)} == {"PUSHED"}
    assert MES.store[org][a]["billSeq"] == 1
    # MES 查询已交付的号（接口 ④）：同样的明细
    token, _ = w.mes_account(org)
    got = ok(api.post("/api/mes/v1/sn/delivered", m(schemaVersion="1.0", pi=pi), token))["items"]
    mine = {i["sn"]: i for i in got}
    assert {k: mine[a][k] for k in FIELDS} == {k: items[a][k] for k in FIELDS}
    # 查询：流水号、十进制流水、进制、流水位数、行号取导入记录，不算转入
    q = ok(api.get(f"/api/sn?sn={a}", w.admin))["items"][0]
    assert (q["seq_text"], q["seq_dec"], q["base"], q["seq_len"], q["bill_seq"], q["transferred_in"]) == (
        "000007",
        "7",
        10,
        6,
        1,
        False,
    )
    ex = xlsx_rows(get_bytes(api, f"/api/sn/export?pi={pi}", w.admin))
    row = dict(zip(ex[0], next(x for x in ex[1:] if x[1] == a), strict=True))
    assert (row["进制流水"], row["十进制流水"], row["ERP行号"]) == ("000007", "7", 1)


def test_mes_pull_gets_full_items_for_imported_sns(w, api, sql):
    """MES 主动取批（接口 ②）：导入的号的明细同样字段都有值，模拟 MES 照常接收。"""
    org, pi, bill = mk(w, ("M1", 1))
    w.enable_mes(org)
    a = uid("SA")
    ok(run(api, xlsx(rec(pi, bill, a, org, serial="000003")), w.admin))
    w.allocate(pi)
    token, _ = w.mes_account(org)
    batch = ok(w.mes_next(token, pi=pi))["batch"]
    (it,) = batch["items"]
    assert (it["sn"], it["serial"], it["serialDec"], it["serialPi"], it["billSeq"]) == (a, "000003", "3", pi, 1)
    assert MES.process(org, batch)["code"] == "OK"


def bill_body(pi, bill, to_bill, to_factory, scope="BILL", reason="转单", **kw):
    return m(
        pi_no=pi,
        bill_no=bill,
        scope=scope,
        to_pi=kw.pop("to_pi", pi),
        to_bill=to_bill,
        to_factory=to_factory,
        reason=reason,
        **kw,
    )


def test_imported_sns_transfer_like_generated(w, api, sql):
    """导入的号分配后与生成的号一样转移（同物料编码、同附件项次）：号池里单据序号仍为 0，
    所在物料行记到导入记录的当前行号；不计已生成数；MES 收到的改挂明细 billSeq 为新行号，转出批次为转出前的行号；
    转出号清单、转移明细、导出列出流水号。"""
    org, pi, bill = mk(w, ("M1", 2))
    org_b = w.org(uid("O")[:10])
    w.enable_mes(org)
    w.enable_mes(org_b)
    # 单据已有生成的号就不能导入：先导入，再生成
    a = uid("SA")
    imp = ok(run(api, xlsx(rec(pi, bill, a, org, serial="000009")), w.admin))
    p = "K" + uid("").replace("-", "")
    ok(w.bind_pi(pi, w.rule(p, with_length=False, pi=pi)))
    w.generate(pi)
    w.allocate(pi)
    w.push()
    assert MES.store[org][a]["billSeq"] == 1
    # 快速定位、来源列表：导入的号与生成的号一起
    loc = ok(api.get(f"/api/transfers/locate?q={a}", w.admin))
    assert (loc["by"], loc["bill_no"], loc["material_code"]) == ("SN", bill, "M1")
    src = ok(api.get(f"/api/transfers/sources?kind=BILL&pi={pi}", w.admin))["bills"]
    assert next(x for x in src if x["bill_no"] == bill)["qty"] == 3
    assert [r["seqs"] for r in next(x for x in src if x["bill_no"] == bill)["rows"]] == [[1]], (
        "导入的号按导入记录的行号"
    )
    # 转单到目标单据：M1 在第 2 行
    _, _, to_bill = mk(w, ("M9", 1), ("M1", 5), org=org)
    to_pi = sql.scalar("SELECT pi FROM sn_prd_mo WHERE bill_no = %s LIMIT 1", to_bill)
    counts = "SELECT bill_no, seq, gen_count FROM sn_line_count WHERE bill_no IN (%s, %s)"
    before = {(x["bill_no"], x["seq"]): x["gen_count"] for x in sql.all(counts, bill, to_bill)}
    body = bill_body(pi, bill, to_bill, org, to_pi=to_pi, to_material="M1")
    pre = ok(api.post("/api/transfers/preview", body, w.admin))
    assert (pre["total"], pre["transferable"], pre["blocked"]) == (3, 3, {}), str(pre)
    assert {(x["bill_no"], x["seq"], x["after"] - x["before"]) for x in pre["generated_changes"]} == {
        (bill, 1, -2),
        (to_bill, 2, 2),
    }, "只计生成的 2 枚"
    t = ok(api.post("/api/transfers", body, w.admin))
    assert t["kind"] == "BILL"
    x = sql.one("SELECT * FROM sn_item WHERE sn = %s", a)
    assert (x["pi_no"], x["bill_no"], x["bill_seq"], x["rev"], x["origin"]) == (to_pi, to_bill, 0, 2, "IMPORT")
    assert sql.one("SELECT bill_seq, cur_bill_seq FROM sn_import_item WHERE import_no = %s", imp["import_no"]) == {
        "bill_seq": 1,
        "cur_bill_seq": 2,
    }
    moved = ok(api.get(f"/api/transfers/sources?kind=BILL&pi={to_pi}", w.admin))["bills"]
    assert [r["seqs"] for r in next(x for x in moved if x["bill_no"] == to_bill)["rows"]] == [[2]], "转移后为当前行号"
    after = {(r["bill_no"], r["seq"]): r["gen_count"] for r in sql.all(counts, bill, to_bill)}
    assert (after[(bill, 1)], after[(to_bill, 2)]) == (before[(bill, 1)] - 2, before.get((to_bill, 2), 0) + 2)
    assert (to_bill, 0) not in after and (bill, 0) not in after, "导入的号不进物料行计数"
    w.push()
    assert (MES.store[org][a]["billNo"], MES.store[org][a]["billSeq"], MES.store[org][a]["rev"]) == (to_bill, 2, 2)
    # 分配页：目标单据的「导入」行
    al = ok(api.get(f"/api/allocation/context?pi={to_pi}", w.admin))
    assert any(y["bill_no"] == to_bill and y["seq"] == 0 and y["imported"] for y in al["lines"])
    # 再按号段转组织到 org_b：来源 MES 收到转出批次（billSeq 为转出前的行号 2），转入方收到新号
    body2 = m(
        factory_code=org,
        pi_no=to_pi,
        bill_no=to_bill,
        scope="MATERIAL",
        lines=[m(material_code="M1", attach_no="1", mode="RANGE", start_sn=a)],
        to_factory=org_b,
        to_pi=to_pi,
        to_bill=to_bill,
        reason="调拨",
    )
    t2 = ok(api.post("/api/transfers", body2, w.admin))
    assert t2["kind"] == "ORG"
    w.push()
    out = [
        i
        for y in MES.received
        if y["org"] == org and y["batch"]["batchType"] == "TRANSFER_OUT"
        for i in y["batch"]["items"]
        if i["sn"] == a
    ]
    assert [(i["billSeq"], i["serial"], i["serialPi"]) for i in out] == [(2, "000009", pi)]
    assert (MES.store[org_b][a]["billSeq"], MES.store[org_b][a]["serialPi"]) == (2, pi)
    tid = t2["doc"]["id"]
    rows = ok(api.get(f"/api/transfers/org/{tid}/items", w.admin))["items"]
    assert [(r["sn"], r["seq_text"], r["seq_dec"], r["seq_pi_no"], r["from_seq"], r["to_seq"]) for r in rows] == [
        (a, "000009", "9", pi, 2, 2)
    ]
    lst = xlsx_rows(get_bytes(api, f"/api/transfers/org/{tid}/out-list", w.admin))
    got = dict(zip(lst[0], lst[1], strict=True))
    assert (got["完整SN"], got["进制流水"], got["十进制流水"], got["流水归属PI"], got["转出前ERP行号"]) == (
        a,
        "000009",
        "9",
        pi,
        2,
    )
    # 已分配页签下载：流水位数、进制位数、流水号取导入记录
    ex = xlsx_rows(get_bytes(api, f"/api/export/file?factory_code={org_b}&pi={to_pi}&bill_no={to_bill}", w.admin))
    row = dict(zip(ex[0], next(y for y in ex[1:] if y[5] == a), strict=True))
    assert row["ERP行号"] == 2, "导入的号的 ERP行号取导入记录的当前行号（号池里为 0）"
    assert (row["单据编号"], row["十进制流水号"]) == (to_bill, "9"), "单据编号取当前挂靠，十进制流水号取导入记录"
    items = ok(api.get(f"/api/export/items?factory_code={org_b}&pi={to_pi}&bill_no={to_bill}", w.admin))["items"]
    assert next(x for x in items if x["sn"] == a)["bill_seq"] == 2, "已分配明细同下载"
    assert (row["流水位数"], row["进制位数"], row["流水号"], row["物料编码"], row["附件项次"]) == (
        6,
        10,
        "000009",
        "M1",
        "1",
    )


def test_import_without_matching_target_line_cannot_transfer(w, api, sql):
    """目标单据没有同物料编码、同附件项次的物料行：导入的号与生成的号一样拒绝，号不动。"""
    org, pi, bill = mk(w, ("M1", 1))
    a = uid("SA")
    ok(run(api, xlsx(rec(pi, bill, a, org)), w.admin))
    w.allocate(pi)
    _, _, to_bill = mk(w, ("M2", 1), org=org)
    to_pi = sql.scalar("SELECT pi FROM sn_prd_mo WHERE bill_no = %s LIMIT 1", to_bill)
    r = api.post("/api/transfers", bill_body(pi, bill, to_bill, org, to_pi=to_pi), w.admin)
    assert r.code == "TR_TARGET_LINE_NOT_MATCH", str(r)
    assert sql.one("SELECT bill_no FROM sn_item WHERE sn = %s", a)["bill_no"] == bill


def test_operator_only_imports_and_sees_authorized_orgs(w, api, sql):
    org_a, pi_a, bill_a = mk(w, ("M1", 1))
    org_b, pi_b, bill_b = mk(w, ("M1", 1))
    op = w.user(emp("OP"), "operator", None, [org_a])
    a, b = uid("SA"), uid("SB")
    r = ok(check(api, xlsx(rec(pi_a, bill_a, a, org_a), rec(pi_b, bill_b, b, org_b)), op))
    assert codes(r) == {3: ["FACTORY_FORBIDDEN"]}
    ok(run(api, xlsx(rec(pi_a, bill_a, a, org_a)), op))
    ok(run(api, xlsx(rec(pi_b, bill_b, b, org_b)), w.admin))
    assert ok(api.get(f"/api/sn-import/items?sn={a}", op))["total"] == 1
    assert ok(api.get(f"/api/sn-import/items?sn={b}", op))["total"] == 0
    aud = w.user(emp("AU"), "auditor", None, [org_a])
    assert api.get("/api/sn-import/items", aud).status == 403
    assert check(api, xlsx(rec(pi_a, bill_a, uid("SC"), org_a)), aud).status == 403


def test_bad_files_and_headers(w, api):
    assert check(api, b"not an xlsx", w.admin).code == "IMPORT_FILE_INVALID"
    assert check(api, b"", w.admin).code == "IMPORT_EMPTY"
    assert check(api, xlsx(), w.admin).code == "IMPORT_EMPTY"
    required = "PI、单据编号、附件项次、物料编码、ERP行号、完整 SN、流水位数、进制位数、十进制流水号、生产组织"
    optional = "流水号、米数、产品单位、生产组织编号"
    one = rec("P", "B", "S", "O")
    # 按列名认列：缺了必填的列（含改了列名的）列出缺哪几列
    for head, missing in (
        ([h for h in FULL if h != "ERP行号"], "ERP行号"),
        # 程序统一用「物料编码」：「料号」不是别名，按其他列不读
        (["料号" if h == "物料编码" else h for h in FULL], "物料编码"),
        ([h for h in FULL if h not in ("附件项次", "十进制流水号")], "附件项次、十进制流水号"),
        ([h for h in FULL if h != "生产组织"], "生产组织"),
        (["PI", "单据", *FULL[2:]], "单据编号"),
        (["备注"], required),
    ):
        r = check(api, xlsx(one, headers=head), w.admin)
        assert (r.code, r["params"]) == (
            "IMPORT_HEADER_INVALID",
            {"missing": missing, "required": required, "optional": optional},
        ), head
    # 同一列出现两次（含别名）
    for head, col in (
        ([*FULL, "PI"], "PI"),
        ([*FULL, "SN"], "完整 SN"),
        ([*FULL, "物料编号"], "物料编码"),
        ([*FULL, "十进制流水"], "十进制流水号"),
        ([*FULL, "行号"], "ERP行号"),
    ):
        r = check(api, xlsx(one, headers=head), w.admin)
        assert (r.code, r["params"]) == ("IMPORT_HEADER_DUPLICATE", {"column": col}), head
    # 选填列可以没有；换了顺序、夹着其他列、别名、大小写和空白不同都能读
    for head in (
        [h for h in FULL if h not in ("流水号", "米数", "产品单位", "生产组织编号")],
        list(reversed(FULL)),
        ["备注", *FULL, "说明"],
        [ALIASED.get(h, h) for h in FULL],
        ["单据行号" if h == "ERP行号" else h for h in FULL],
        [f"* {h}" if i % 2 else f"＊{h}" for i, h in enumerate(FULL)],
    ):
        r = check(api, xlsx(one, headers=head), w.admin)
        assert r.status == 200 and r["total"] == 1, (head, str(r))


ALIASED = {
    "完整 SN": "sn",
    "物料编码": "物料编号",
    "PI": " pi ",
    "单据编号": "单据 编号",
    "十进制流水号": "十进制流水",
    "ERP行号": "行号",
}


def test_columns_found_by_name_in_any_order(w, api, sql):
    """按列名取值（不按位置）；只在其他列有内容的行跳过；导入记录的行号为文件里的行号。"""
    org, pi, bill = mk(w, ("M1", 2))
    a, b = uid("SA"), uid("SB")
    head = ["备注", *reversed(FULL)]
    content = xlsx(
        rec(pi, bill, a, org, serial="000001") | {"备注": "说明 1"},
        {"备注": "只有备注"},
        rec(pi, bill, b, org, serial="000002", length=""),
        headers=head,
    )
    r = ok(check(api, content, w.admin))
    assert (r["total"], r["bad"]) == (2, 0), str(r)
    ok(run(api, content, w.admin))
    rows = sql.all(
        "SELECT sn, row_no, serial_text, length_text FROM sn_import_item WHERE sn IN (%s, %s) ORDER BY row_no", a, b
    )
    assert [(x["sn"], x["row_no"], x["serial_text"], x["length_text"]) for x in rows] == [
        (a, 2, "000001", "100"),
        (b, 4, "000002", None),
    ]
    assert sql.scalar("SELECT length_text FROM sn_item WHERE sn = %s", b) is None


def test_delete_whole_import_only_while_all_pending(w, api, sql):
    """整单删除：导入单的号全部待分配时才能删，号从号池移除、记录标为已删除，之后可重新导入同样的号；
    有一枚已分配就不能删；删过的再删报不存在；普通操作员只能删授权组织的导入单。"""
    org, pi, bill = mk(w, ("M1", 1))
    _, pi2, bill2 = mk(w, ("M1", 1), org=org)
    a, b, c = uid("SA"), uid("SB"), uid("SC")
    first = ok(run(api, xlsx(rec(pi, bill, a, org), rec(pi2, bill2, b, org)), w.admin))["import_no"]
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
    again = ok(run(api, xlsx(rec(pi, bill, a, org), rec(pi, bill, c, org)), w.admin))["import_no"]
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
