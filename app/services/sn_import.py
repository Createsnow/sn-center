"""SN 导入：本系统之外已有的 SN 按「PI + 单据编号 + SN + 其他」登记进来，之后生成不会再出这些号。

- 文件：xlsx，首行为表头（PI、单据编号、SN 必有，其他可选；列顺序不限），之后每行一枚号；空行跳过。
- 每行的条件：PI、单据编号、SN 必填；SN 不超过 64 位、不含空白；单据须在订单快照中且 PI 与之相同；
  普通操作员只能导入授权生产组织的单据；SN 在文件内不能重复，也不能与号池（已生成的号）、已导入的号重复。
- 先检查（不落库，逐行列出不能导入的原因），再「确定导入」：有一行不能导入整个文件都不导入；
  确定导入时在一个事务里重新检查一遍，全部写入，一次导入一个导入单号。
- 导入的号只登记在 sn_import_item，不进号池，不参与分配、交付、转移和删号。
- 与生成互斥：导入独占、生成共享 sn_seq 的 '#sn-import' 行锁（guard）。生成在写号前查已导入的号，撞上整次回滚；
  导入在锁内查号池。这样两边同时进行时，后提交的一方一定能看到先提交的号，不会各自都以为没有重复。
"""

from __future__ import annotations

import io
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass, field
from typing import Any

import pymysql
from openpyxl import Workbook, load_workbook

from app.core import utils as util
from app.core.errors import ErrorCode, biz
from app.core.security import CurrentUser
from app.db.session import db, is_duplicate, marks
from app.services import audit

#: 单个文件上限
MAX_MB = 20
MAX_BYTES = MAX_MB * 1024 * 1024
#: 单次最多导入的数据行
MAX_ROWS = 50_000
#: 检查结果里最多列出的问题行（合计照常给全）
ISSUE_LIST_MAX = 500
CHUNK = 1000
SN_MAX = 64
CODE_MAX = 64
OTHER_MAX = 500
#: 导入与生成互斥用的 sn_seq 行（# 开头不会与单号序列重名）
GUARD = "#sn-import"

#: 模板表头（列顺序）
HEADERS = ["PI", "单据编号", "SN", "其他"]
#: 表头别名（去空白、转小写后比较）→ 字段
_ALIASES = {
    "pi": "pi",
    "pi号": "pi",
    "pino": "pi",
    "pi_no": "pi",
    "单据编号": "bill",
    "单据": "bill",
    "单据号": "bill",
    "生产订单": "bill",
    "生产订单号": "bill",
    "billno": "bill",
    "bill_no": "bill",
    "sn": "sn",
    "完整sn": "sn",
    "sn号": "sn",
    "其他": "other",
    "其它": "other",
    "备注": "other",
    "other": "other",
}
_REQUIRED = {"pi": "PI", "bill": "单据编号", "sn": "SN"}

# 行的问题（页面按代码翻译，params 为占位参数）
PI_EMPTY = "PI_EMPTY"
BILL_EMPTY = "BILL_EMPTY"
SN_EMPTY = "SN_EMPTY"
PI_TOO_LONG = "PI_TOO_LONG"
BILL_TOO_LONG = "BILL_TOO_LONG"
SN_TOO_LONG = "SN_TOO_LONG"
SN_SPACE = "SN_SPACE"
OTHER_TOO_LONG = "OTHER_TOO_LONG"
FILE_DUP = "FILE_DUP"
IN_POOL = "IN_POOL"
IMPORTED = "IMPORTED"
BILL_NOT_FOUND = "BILL_NOT_FOUND"
PI_MISMATCH = "PI_MISMATCH"
FACTORY_FORBIDDEN = "FACTORY_FORBIDDEN"


@dataclass
class Row:
    row_no: int
    pi: str
    bill: str
    sn: str
    other: str
    factory: str = ""
    issues: list[tuple[str, dict]] = field(default_factory=list)


# ================================================================== 模板与解析


def template() -> Iterator[bytes]:
    """导入模板：一行表头，各列设为文本格式（长串数字的 SN 不会被 Excel 改成科学计数）。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "SN导入"
    ws.append(HEADERS)
    for col, width in zip("ABCD", (20, 22, 34, 40), strict=True):
        ws.column_dimensions[col].width = width
        ws.column_dimensions[col].number_format = "@"
    ws.freeze_panes = "A2"
    out = io.BytesIO()
    wb.save(out)
    yield out.getvalue()


def _text(v: Any) -> str:
    """单元格 → 去首尾空白的字符串；整数值的浮点（Excel 把数字 SN 存成数值）去掉 .0。"""
    if v is None:
        return ""
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v).strip()


def _key(v: Any) -> str:
    return "".join(_text(v).split()).lower()


def _cell(cells: list, cols: dict[str, int], f: str) -> str:
    i = cols.get(f)
    return _text(cells[i]) if i is not None and i < len(cells) else ""


def parse(content: bytes) -> list[Row]:
    """读 xlsx 第一张表：首个非空行为表头，按表头名找列；返回数据行（空行跳过）。"""
    if not content:
        raise biz(ErrorCode.IMPORT_EMPTY)
    if len(content) > MAX_BYTES:
        raise biz(ErrorCode.IMPORT_FILE_TOO_LARGE, max=MAX_MB)
    try:
        wb = load_workbook(io.BytesIO(content), read_only=True, data_only=True)
    except (zipfile.BadZipFile, KeyError, ValueError, OSError, TypeError):
        raise biz(ErrorCode.IMPORT_FILE_INVALID) from None
    try:
        ws = wb.worksheets[0] if wb.worksheets else None
        if ws is None:
            raise biz(ErrorCode.IMPORT_EMPTY)
        cols: dict[str, int] | None = None
        out: list[Row] = []
        for no, values in enumerate(ws.iter_rows(values_only=True), start=1):
            cells = list(values or ())
            if not any(_text(v) for v in cells):
                continue
            if cols is None:
                cols = {}
                for i, v in enumerate(cells):
                    f = _ALIASES.get(_key(v))
                    if f is not None and f not in cols:
                        cols[f] = i
                missing = [name for f, name in _REQUIRED.items() if f not in cols]
                if missing:
                    raise biz(ErrorCode.IMPORT_HEADER_MISSING, columns="、".join(missing))
                continue
            out.append(Row(no, *(_cell(cells, cols, f) for f in ("pi", "bill", "sn", "other"))))
            if len(out) > MAX_ROWS:
                raise biz(ErrorCode.IMPORT_TOO_MANY, max=MAX_ROWS, rows=f"{MAX_ROWS}+")
        if cols is None:
            raise biz(ErrorCode.IMPORT_HEADER_MISSING, columns="、".join(_REQUIRED.values()))
        if not out:
            raise biz(ErrorCode.IMPORT_EMPTY)
        return out
    finally:
        wb.close()


# ================================================================== 检查


def _chunks(xs: list[str]) -> Iterator[list[str]]:
    for i in range(0, len(xs), CHUNK):
        yield xs[i : i + CHUNK]


def _validate(cu: CurrentUser, rows: list[Row], lock: bool = False) -> None:
    """逐行检查，问题记到 row.issues。lock = True（确定导入、已持 guard）时查号池用加锁读，读到最新提交的号。"""
    first: dict[str, int] = {}
    for r in rows:
        if not r.pi:
            r.issues.append((PI_EMPTY, {}))
        elif len(r.pi) > CODE_MAX:
            r.issues.append((PI_TOO_LONG, {"max": CODE_MAX}))
        if not r.bill:
            r.issues.append((BILL_EMPTY, {}))
        elif len(r.bill) > CODE_MAX:
            r.issues.append((BILL_TOO_LONG, {"max": CODE_MAX}))
        if not r.sn:
            r.issues.append((SN_EMPTY, {}))
        elif len(r.sn) > SN_MAX:
            r.issues.append((SN_TOO_LONG, {"max": SN_MAX}))
        elif any(c.isspace() for c in r.sn):
            r.issues.append((SN_SPACE, {}))
        if len(r.other) > OTHER_MAX:
            r.issues.append((OTHER_TOO_LONG, {"max": OTHER_MAX}))
        if r.sn:
            if r.sn in first:
                r.issues.append((FILE_DUP, {"row": first[r.sn]}))
            else:
                first[r.sn] = r.row_no
    # 单据：须在订单快照中，PI 相同；普通操作员只能导入授权组织的单据
    bills = sorted({r.bill for r in rows if r.bill and len(r.bill) <= CODE_MAX})
    snap: dict[str, dict] = {}
    for part in _chunks(bills):
        for b in db.all(
            f"SELECT bill_no, MIN(pi) pi, MIN(org_code) org_code FROM sn_prd_mo WHERE bill_no IN ({marks(len(part))}) "
            "GROUP BY bill_no",
            *part,
        ):
            snap[b["bill_no"]] = b
    for r in rows:
        if not r.bill or len(r.bill) > CODE_MAX:
            continue
        b = snap.get(r.bill)
        if b is None:
            r.issues.append((BILL_NOT_FOUND, {"bill_no": r.bill}))
            continue
        r.factory = b["org_code"] or ""
        if r.pi and b["pi"] != r.pi:
            r.issues.append((PI_MISMATCH, {"bill_no": r.bill, "pi": b["pi"]}))
        if not cu.is_admin and r.factory not in cu.factories:
            r.issues.append((FACTORY_FORBIDDEN, {"factory": r.factory}))
    # 与号池、已导入的号重复
    sns = sorted(first)
    pool: dict[str, str] = {}
    done: dict[str, str] = {}
    share = " LOCK IN SHARE MODE" if lock else ""
    for part in _chunks(sns):
        for x in db.all(f"SELECT sn, pi_no FROM sn_item WHERE sn IN ({marks(len(part))}){share}", *part):
            pool[x["sn"]] = x["pi_no"]
        for x in db.all(f"SELECT sn, import_no FROM sn_import_item WHERE sn IN ({marks(len(part))}){share}", *part):
            done[x["sn"]] = x["import_no"]
    for r in rows:
        if r.sn in pool:
            r.issues.append((IN_POOL, {"pi": pool[r.sn]}))
        if r.sn in done:
            r.issues.append((IMPORTED, {"import_no": done[r.sn]}))


def _issue_rows(rows: list[Row]) -> list[dict]:
    return [
        {
            "row": r.row_no,
            "pi": r.pi,
            "bill_no": r.bill,
            "sn": r.sn,
            "issues": [{"code": c, "params": p} for c, p in r.issues],
        }
        for r in rows
        if r.issues
    ][:ISSUE_LIST_MAX]


def check(cu: CurrentUser, content: bytes) -> dict:
    """检查（不落库）：总行数、可导入行数、问题行数与问题行（最多列出 ISSUE_LIST_MAX 行）、涉及的 PI / 单据数。"""
    rows = parse(content)
    _validate(cu, rows)
    bad = sum(1 for r in rows if r.issues)
    return {
        "total": len(rows),
        "ok": len(rows) - bad,
        "bad": bad,
        "issues": _issue_rows(rows),
        "pis": len({r.pi for r in rows if r.pi}),
        "bills": len({r.bill for r in rows if r.bill}),
        "importable": bad == 0,
    }


# ================================================================== 导入


def guard(exclusive: bool) -> None:
    """导入（exclusive）与生成（共享）互斥：在当前事务里锁 sn_seq 的 GUARD 行（建表脚本写入）。

    行万一不在：在本事务里补建（本事务已持该处的间隙锁，换连接去建会等自己）。"""
    sql = "SELECT val FROM sn_seq WHERE name = %s " + ("FOR UPDATE" if exclusive else "LOCK IN SHARE MODE")
    if db.one(sql, GUARD) is None:
        db.exec("INSERT IGNORE INTO sn_seq(name, val) VALUES (%s, 0)", GUARD)
        db.one(sql, GUARD)


def imported_hit(sns: list[str]) -> dict | None:
    """生成写号前：这些号里第一枚已导入的号（加锁读，读最新提交）；调用方须已持共享 guard。"""
    if not sns:
        return None
    return db.one(
        f"SELECT sn, pi_no FROM sn_import_item WHERE sn IN ({marks(len(sns))}) LIMIT 1 LOCK IN SHARE MODE", *sns
    )


_INSERT = (
    "INSERT INTO sn_import_item(import_no, sn, pi_no, bill_no, factory_code, other, file_name, row_no, created_by, "
    "created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
)


def run(cu: CurrentUser, content: bytes, file_name_in: str | None) -> dict:
    """确定导入：事务内重新检查，有问题行整个文件不导入；否则全部写入。返回导入单号与枚数。"""
    if not (cu.is_admin or cu.is_operator):
        raise biz(ErrorCode.FORBIDDEN)
    rows = parse(content)
    file_name = util.cut(util.trim(file_name_in), 255) or ""
    with db.tx():
        guard(exclusive=True)
        _validate(cu, rows, lock=True)
        bad = sum(1 for r in rows if r.issues)
        if bad:
            raise biz(ErrorCode.IMPORT_HAS_ISSUES, count=bad)
        now = util.now()
        no = util.next_id("IM")
        for i in range(0, len(rows), CHUNK):
            part = rows[i : i + CHUNK]
            try:
                db.exec_many(
                    _INSERT,
                    [(no, r.sn, r.pi, r.bill, r.factory, r.other, file_name, r.row_no, cu.emp_no, now) for r in part],
                )
            except pymysql.err.IntegrityError as ex:
                if not is_duplicate(ex):
                    raise
                hit = db.one(
                    f"SELECT sn FROM sn_import_item WHERE sn IN ({marks(len(part))}) LIMIT 1", *[r.sn for r in part]
                )
                raise biz(ErrorCode.IMPORT_CONFLICT, sn=hit["sn"] if hit else part[0].sn) from None
        by_bill: dict[tuple[str, str], list[Row]] = {}
        for r in rows:
            by_bill.setdefault((r.pi, r.bill), []).append(r)
        for (pi, bill), rs in sorted(by_bill.items()):
            ss = sorted(r.sn for r in rs)
            audit.record(
                cu,
                audit.entry(audit.SN_IMPORT)
                .factory(rs[0].factory or None)
                .pi(pi)
                .bill(bill)
                .range(ss[0], ss[-1])
                .count(len(rs))
                .batch(no)
                .info(f"file={file_name} rows={rs[0].row_no}..{rs[-1].row_no}"),
            )
    return {
        "import_no": no,
        "qty": len(rows),
        "pis": len({r.pi for r in rows}),
        "bills": len(by_bill),
    }


# ================================================================== 列表


@dataclass
class Filter:
    pi_no: str | None = None
    bill_no: str | None = None
    sn: str | None = None
    import_no: str | None = None


_FIELDS = ("id", "import_no", "sn", "pi_no", "bill_no", "factory_code", "other", "file_name", "row_no", "created_by")


def items(cu: CurrentUser, f: Filter, q: util.Page) -> dict:
    """已导入的号（新的在前）：按 PI、单据编号、SN（前缀）、导入单号筛选；普通操作员只看授权组织的。"""
    where = ["1 = 1"]
    args: list[Any] = []
    facs = cu.read_factories(None)
    if facs is not None:
        where.append(f"factory_code IN ({marks(len(facs))})")
        args += facs
    for col, v in (("pi_no", f.pi_no), ("bill_no", f.bill_no), ("import_no", f.import_no)):
        v = util.trim(v)
        if v:
            where.append(f"{col} = %s")
            args.append(v)
    sn = util.trim(f.sn)
    if sn:
        where.append("sn LIKE %s")
        args.append(util.like_prefix(sn))
    w = " AND ".join(where)
    total = db.count(f"SELECT COUNT(*) FROM sn_import_item WHERE {w}", *args)
    rows = db.all(
        f"SELECT {', '.join(_FIELDS)}, created_at FROM sn_import_item WHERE {w} ORDER BY id DESC LIMIT %s OFFSET %s",
        *args,
        q.size,
        q.offset,
    )
    return util.page_result(
        [{**{k: r[k] for k in _FIELDS}, "created_at": util.fmt(r["created_at"])} for r in rows], total, q
    )
