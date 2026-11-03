"""SN 导入：PI + 单据编号 + SN + 其他；先检查后确定导入，有一行不能导入整个文件不导入；
导入的号不能与号池、已导入的号重复，之后生成撞上已导入的号整次回滚；普通操作员只能导入、查看授权组织的单据。"""

from __future__ import annotations

import io

from conftest import emp, ok, uid
from openpyxl import Workbook


def xlsx(*rows, headers=("PI", "单据编号", "SN", "其他")) -> bytes:
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

    assert xlsx_rows(r.content)[0] == ["PI", "单据编号", "SN", "其他"]


def test_check_lists_every_bad_row_and_import_is_all_or_nothing(w, api, sql):
    org, pi, bill = mk(w, ("M1", 2))
    _, pi2, bill2 = mk(w, ("M1", 1))
    p = "I" + uid("").replace("-", "")
    ok(w.bind_pi(pi, w.rule(p, with_length=False, pi=pi)))
    w.generate(pi)
    pooled = sql.scalar("SELECT sn FROM sn_item WHERE pi_no = %s ORDER BY seq_dec LIMIT 1", pi)
    a, b = uid("SA"), uid("SB")
    content = xlsx(
        (pi, bill, a, "旧系统"),  # 2 可导入
        (pi, bill, a, ""),  # 3 文件内重复
        (pi, bill, pooled, ""),  # 4 号池已有
        (pi, "NO-SUCH-BILL", uid("SC"), ""),  # 5 单据不在快照
        (pi2, bill, uid("SD"), ""),  # 6 PI 与单据不符
        (pi, bill, None, "x"),  # 7 SN 为空
        (pi, bill, "A B", ""),  # 8 SN 含空白
        (None, None, None, None),  # 空行跳过
        (pi2, bill2, 123456789012, ""),  # 10 数字 SN 按文本
        (pi2, bill2, b, "x" * 501),  # 11 其他超长
    )
    r = ok(check(api, content, w.admin))
    assert (r["total"], r["ok"], r["bad"], r["importable"]) == (9, 2, 7, False)
    assert codes(r) == {
        3: ["FILE_DUP"],
        4: ["IN_POOL"],
        5: ["BILL_NOT_FOUND"],
        6: ["PI_MISMATCH"],
        7: ["SN_EMPTY"],
        8: ["SN_SPACE"],
        11: ["OTHER_TOO_LONG"],
    }
    dup = next(x for x in r["issues"] if x["row"] == 3)["issues"][0]["params"]
    assert dup == {"row": 2}
    rej = run(api, content, w.admin)
    assert (rej.status, rej.code) == (400, "IMPORT_HAS_ISSUES"), str(rej)
    assert sql.count("SELECT COUNT(*) FROM sn_import_item WHERE sn IN (%s, %s)", a, "123456789012") == 0
    assert sql.count(
        "SELECT COUNT(*) FROM sn_audit WHERE action = 'SN_IMPORT' AND result = 'FAIL' AND operator = 'admin' "
        "AND error_msg LIKE %s",
        "%7 行不能导入%",
    )


def test_import_registers_and_blocks_reimport(w, api, sql):
    org, pi, bill = mk(w, ("M1", 2), ("M2", 1))
    a, b = uid("SA"), uid("SB")
    content = xlsx((pi, bill, a, "第一批"), (pi, bill, b, ""), headers=("sn", " PI ", "单据", "备注"))
    # 表头按名字找列（顺序不限、去空白、不分大小写）：这里 sn 列放的是 PI，PI 列放的是单据……
    bad = ok(check(api, content, w.admin))
    assert bad["importable"] is False
    content = xlsx((bill, a, pi, "第一批"), (bill, b, pi, ""), headers=("单据", "sn", " PI ", "备注"))
    r = ok(check(api, content, w.admin))
    assert (r["total"], r["ok"], r["importable"], r["pis"], r["bills"]) == (2, 2, True, 1, 1)
    done = ok(run(api, content, w.admin, "旧号.xlsx"))
    assert (done["qty"], done["pis"], done["bills"]) == (2, 1, 1)
    rows = sql.all("SELECT * FROM sn_import_item WHERE import_no = %s ORDER BY row_no", done["import_no"])
    assert [(x["sn"], x["pi_no"], x["bill_no"], x["factory_code"], x["other"], x["row_no"]) for x in rows] == [
        (a, pi, bill, org, "第一批", 2),
        (b, pi, bill, org, "", 3),
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
    # 再导入同一枚号：已导入
    again = ok(check(api, xlsx((pi, bill, a, "")), w.admin))
    assert codes(again) == {2: ["IMPORTED"]}
    assert again["issues"][0]["issues"][0]["params"] == {"import_no": done["import_no"]}


def test_generation_refuses_imported_sn(w, api, sql):
    """导入了将来规则会生成的号：生成整次回滚、不跳号；号池、最大号不变。"""
    org, pi, bill = mk(w, ("M1", 3))
    p = "J" + uid("").replace("-", "")
    ok(w.bind_pi(pi, w.rule(p, with_length=False, pi=pi)))
    taken = f"{p}000002"
    ok(run(api, xlsx((pi, bill, taken, "")), w.admin))
    pv = ok(w.preview(pi))
    assert pv["generatable"], str(pv)
    r = api.post("/api/generate/run", {"token": pv["token"]}, w.admin)
    assert (r.status, r.code) == (409, "GEN_IMPORTED_SN"), str(r)
    assert r["params"] == {"sn": taken, "pi": pi}
    assert sql.count("SELECT COUNT(*) FROM sn_item WHERE pi_no = %s", pi) == 0
    assert (sql.scalar("SELECT last_seq_dec FROM sn_pi_counter WHERE pi_no = %s", pi) or 0) == 0


def test_operator_only_imports_and_sees_authorized_orgs(w, api, sql):
    org_a, pi_a, bill_a = mk(w, ("M1", 1))
    org_b, pi_b, bill_b = mk(w, ("M1", 1))
    op = w.user(emp("OP"), "operator", None, [org_a])
    a, b = uid("SA"), uid("SB")
    r = ok(check(api, xlsx((pi_a, bill_a, a, ""), (pi_b, bill_b, b, "")), op))
    assert codes(r) == {3: ["FACTORY_FORBIDDEN"]}
    ok(run(api, xlsx((pi_a, bill_a, a, "")), op))
    ok(run(api, xlsx((pi_b, bill_b, b, "")), w.admin))
    mine = ok(api.get(f"/api/sn-import/items?sn={a}", op))
    assert mine["total"] == 1
    assert ok(api.get(f"/api/sn-import/items?sn={b}", op))["total"] == 0
    aud = w.user(emp("AU"), "auditor", None, [org_a])
    assert api.get("/api/sn-import/items", aud).status == 403
    assert check(api, xlsx((pi_a, bill_a, uid("SC"), "")), aud).status == 403


def test_bad_files(w, api):
    assert check(api, b"not an xlsx", w.admin).code == "IMPORT_FILE_INVALID"
    assert check(api, b"", w.admin).code == "IMPORT_EMPTY"
    r = check(api, xlsx(("P", "B", "S"), headers=("PI", "SN", "其他")), w.admin)
    assert (r.code, r["params"]) == ("IMPORT_HEADER_MISSING", {"columns": "单据编号"})
    assert check(api, xlsx(headers=("PI", "单据编号", "SN")), w.admin).code == "IMPORT_EMPTY"
