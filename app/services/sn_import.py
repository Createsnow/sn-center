"""SN 导入：本系统之外已有的 SN 按「PI + 单据编号 + SN + 生产组织」导入号池，进入 SN 分配页（待分配），
只能分配；可另带物料编码、附件项次、米数、客户编码
（推送 / 拉取给 MES 的明细里对应 materialCode、attachNo、length、customerCode）。

- 文件：xlsx，首个非空行为表头，按列名认列（COLUMNS：列名固定，去空白、不分大小写比较；列的顺序不限）：
  「PI、单据编号、SN、生产组织」四列必须有（分配要用：号挂到哪张单据、分到哪个生产组织）；
  「物料编码（也可写作料号）、附件项次、米数、客户编码」有就读、没有按空（推送 / 拉取的明细与转移的
  「同料号、同附件项次」要用）；其他列名的列不读（可直接用带其他列的表格导入）。同一列出现两次（含别名）不能导入。
  之后每行一枚号；认得的列都为空的行跳过。
- 每行的条件：A～D 必填；SN 不超过 64 位、不含空白；单据须在订单快照中，PI、生产组织（填编码或名称）与之相同；
  普通操作员只能导入授权生产组织的单据；SN 在文件内不能重复，也不能与号池（已生成、已导入的号）重复。
  E～H 选填，填了按订单快照检查（与转移「同料号、同附件项次」一致）：物料编码须是该单据的物料；附件项次须是该单据
  （填了物料编码时为该物料）某物料行生效的附件项次（含生成页补填的）；客户编码须与单据的客户相同；米数原样保存，
  只查长度。
- 先检查（不落库，逐行列出不能导入的原因），再「确定导入」：有一行不能导入整个文件都不导入；
  确定导入时在 PI 操作锁与一个事务里重新检查一遍，全部写入，一次导入一个导入单号。
- 写入：导入记录 sn_import_item（导入后不变）+ 号池 sn_item（origin = IMPORT，待分配，挂在单据序号 0 上；
  没有规则与流水：seq_pi_no 为空串、seq_dec 为导入记录 id、rule_version_id 为 0；
  生产组织为文件里的生产组织（换成编码），物料、附件项次、米数、客户编码取文件里填的，没填的为空，不另外推导）。
  推送 / 拉取给 MES 的明细只带文件里有的：SN、PI、单据编号（生产组织在批次头）与填了的物料编码、附件项次、米数、
  客户编码，其余（流水、单据行号）为 null（见 delivery.batches.imported_item）。
  与生成一样建立已出号单据台账、按台账带上暂停标记（单据反审核等照常暂停交付）。
- 导入的号只能分配（分配后照常推送 / 拉取 / 下载、查询）；不出现在生成页（号段、单据分配状态、统计都不计），
  不能删号、转移，也不占订单的可生成数量。
- 完整 SN 全局唯一由号池的唯一键保证：导入撞上别人刚生成或导入的号整次不导入，生成撞上导入的号整次回滚。
- 整单删除（导入页）：一个导入单号的号全部还是待分配时，可整单删除——号从号池移除（之后可重新导入同样的号），
  导入记录保留并标为已删除；有一枚已分配（含待推送、已推送）就不能删。与导入、分配共用 PI 操作锁。
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
from app.services import audit, changes, generate, locks, orders

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
#: 生产组织可填编码或名称（名称最长 128）
ORG_MAX = 128


@dataclass(frozen=True)
class Column:
    #: Row 的字段名
    field: str
    #: 列名（模板里写这个）
    name: str
    #: 表头必须有这一列
    required: bool
    #: 也认的列名
    aliases: tuple[str, ...] = ()


#: 固定列：按列名认列，顺序不限（模板按这个顺序出）；required 的列表头必须有，其余有就读，其他列不读
COLUMNS = (
    Column("pi", "PI", True),
    Column("bill", "单据编号", True),
    Column("sn", "SN", True),
    Column("org", "生产组织", True),
    Column("material", "物料编码", False, ("料号",)),
    Column("attach", "附件项次", False),
    Column("length", "米数", False),
    Column("customer", "客户编码", False),
)
HEADERS = [c.name for c in COLUMNS]
REQUIRED = [c.name for c in COLUMNS if c.required]
OPTIONAL = [c.name for c in COLUMNS if not c.required]

# 行的问题（页面按代码翻译，params 为占位参数）
PI_EMPTY = "PI_EMPTY"
BILL_EMPTY = "BILL_EMPTY"
SN_EMPTY = "SN_EMPTY"
PI_TOO_LONG = "PI_TOO_LONG"
BILL_TOO_LONG = "BILL_TOO_LONG"
SN_TOO_LONG = "SN_TOO_LONG"
SN_SPACE = "SN_SPACE"
ORG_EMPTY = "ORG_EMPTY"
ORG_TOO_LONG = "ORG_TOO_LONG"
ORG_MISMATCH = "ORG_MISMATCH"
FILE_DUP = "FILE_DUP"
IN_POOL = "IN_POOL"
IMPORTED = "IMPORTED"
BILL_NOT_FOUND = "BILL_NOT_FOUND"
PI_MISMATCH = "PI_MISMATCH"
FACTORY_FORBIDDEN = "FACTORY_FORBIDDEN"
MATERIAL_TOO_LONG = "MATERIAL_TOO_LONG"
ATTACH_TOO_LONG = "ATTACH_TOO_LONG"
LENGTH_TOO_LONG = "LENGTH_TOO_LONG"
CUSTOMER_TOO_LONG = "CUSTOMER_TOO_LONG"
MATERIAL_NOT_IN_BILL = "MATERIAL_NOT_IN_BILL"
ATTACH_NOT_IN_BILL = "ATTACH_NOT_IN_BILL"
CUSTOMER_MISMATCH = "CUSTOMER_MISMATCH"


@dataclass
class Row:
    row_no: int
    pi: str
    bill: str
    sn: str
    #: 文件里填的生产组织（编码或名称）
    org: str
    #: 选填：物料编码、附件项次、米数、客户编码（空串 = 没填）
    material: str = ""
    attach: str = ""
    length: str = ""
    customer: str = ""
    #: 文件里的生产组织对应的编码（与单据在订单快照中的生产组织相同；登记、授权范围按它）
    factory: str = ""
    issues: list[tuple[str, dict]] = field(default_factory=list)


# ================================================================== 模板与解析


def template() -> Iterator[bytes]:
    """导入模板：一行表头（固定列，前 4 列必须有，其余选填；导入时按列名认列、顺序不限），各列设为文本格式
    （长串数字的 SN、带前导 0 的米数不会被 Excel 改写）。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "SN导入"
    ws.append(HEADERS)
    for col, width in zip("ABCDEFGH", (20, 22, 34, 24, 18, 14, 12, 14), strict=True):
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


#: 列名（含别名）→ 固定列
_BY_KEY = {_key(n): c for c in COLUMNS for n in (c.name, *c.aliases)}


def _header_error(missing: list[str]):
    return biz(
        ErrorCode.IMPORT_HEADER_INVALID,
        missing="、".join(missing),
        required="、".join(REQUIRED),
        optional="、".join(OPTIONAL),
    )


def _check_header(cells: list) -> dict[str, int]:
    """按列名认列：返回 Row 字段 → 列下标。必须有的列缺了报 IMPORT_HEADER_INVALID（列出缺的列）；
    同一列出现两次（含别名，如「物料编码」和「料号」）报 IMPORT_HEADER_DUPLICATE；其他列名的列不读。"""
    at: dict[str, int] = {}
    for i, v in enumerate(cells):
        c = _BY_KEY.get(_key(v))
        if c is None:
            continue
        if c.field in at:
            raise biz(ErrorCode.IMPORT_HEADER_DUPLICATE, column=c.name)
        at[c.field] = i
    missing = [c.name for c in COLUMNS if c.required and c.field not in at]
    if missing:
        raise _header_error(missing)
    return at


def _cell(cells: list, i: int) -> str:
    return _text(cells[i]) if i < len(cells) else ""


def parse(content: bytes) -> list[Row]:
    """读 xlsx 第一张表：首个非空行为表头（按列名认列，见 _check_header），之后按认出的列读数据；返回数据行。
    表头没有的选填列按空处理；认不出的列不读；认得的列都为空的行跳过（只在其他列有内容的行也跳过）。"""
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
        at: dict[str, int] | None = None
        out: list[Row] = []
        for no, values in enumerate(ws.iter_rows(values_only=True), start=1):
            cells = list(values or ())
            if at is None:
                if any(_text(v) for v in cells):
                    at = _check_header(cells)
                continue
            vals = {f: _cell(cells, i) for f, i in at.items()}
            if not any(vals.values()):
                continue
            out.append(Row(no, **vals))
            if len(out) > MAX_ROWS:
                raise biz(ErrorCode.IMPORT_TOO_MANY, max=MAX_ROWS, rows=f"{MAX_ROWS}+")
        if at is None:
            raise _header_error(REQUIRED)
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
    """逐行检查，问题记到 row.issues。lock = True（确定导入时）查号池用加锁读，读到最新提交的号。"""
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
        if not r.org:
            r.issues.append((ORG_EMPTY, {}))
        elif len(r.org) > ORG_MAX:
            r.issues.append((ORG_TOO_LONG, {"max": ORG_MAX}))
        for v, code in (
            (r.material, MATERIAL_TOO_LONG),
            (r.attach, ATTACH_TOO_LONG),
            (r.length, LENGTH_TOO_LONG),
            (r.customer, CUSTOMER_TOO_LONG),
        ):
            if len(v) > CODE_MAX:
                r.issues.append((code, {"max": CODE_MAX}))
        if r.sn:
            if r.sn in first:
                r.issues.append((FILE_DUP, {"row": first[r.sn]}))
            else:
                first[r.sn] = r.row_no
    # 单据：须在订单快照中，PI、生产组织相同；普通操作员只能导入授权组织的单据
    bills = sorted({r.bill for r in rows if r.bill and len(r.bill) <= CODE_MAX})
    snap: dict[str, dict] = {}
    for part in _chunks(bills):
        for b in db.all(
            "SELECT m.bill_no, MIN(m.pi) pi, MIN(m.org_code) org_code, MIN(m.org_name) org_name, "
            "MIN(m.customer_code) customer_code, MIN(f.factory_name) factory_name "
            "FROM sn_prd_mo m LEFT JOIN sn_factory f ON f.factory_code = m.org_code "
            f"WHERE m.bill_no IN ({marks(len(part))}) GROUP BY m.bill_no",
            *part,
        ):
            snap[b["bill_no"]] = b
    # 填了物料编码或附件项次的单据：取物料行（带生效的附件项次，含生成页补填的），按「同料号、同附件项次」对照
    need = sorted({r.bill for r in rows if r.bill in snap and (r.material or r.attach)})
    mats: dict[str, set[str]] = {}
    attaches: dict[str, set[tuple[str, str]]] = {}
    for part in _chunks(need):
        ls = db.all(
            f"SELECT * FROM sn_prd_mo WHERE bill_no IN ({marks(len(part))}) ORDER BY bill_no, seq",
            *part,
        )
        for x in orders.effective_lines(ls):
            mats.setdefault(x["bill_no"], set()).add(x["material_code"] or "")
            attaches.setdefault(x["bill_no"], set()).add((x["material_code"] or "", x["eff_attach"] or ""))
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
        # 生产组织填编码或名称（订单快照里的名称、生产组织列表里的名称）都可以
        if r.org and len(r.org) <= ORG_MAX and r.org not in {r.factory, b["org_name"], b["factory_name"]} - {None, ""}:
            name = b["factory_name"] or b["org_name"] or ""
            r.issues.append((ORG_MISMATCH, {"bill_no": r.bill, "factory": f"{r.factory} {name}".strip()}))
        if not cu.is_admin and r.factory not in cu.factories:
            r.issues.append((FACTORY_FORBIDDEN, {"factory": r.factory}))
        # 选填列：填了就须与订单快照对得上（超长的已报过，不再对照）
        material_ok = True
        if r.material and len(r.material) <= CODE_MAX and r.material not in mats.get(r.bill, set()):
            material_ok = False
            r.issues.append((MATERIAL_NOT_IN_BILL, {"bill_no": r.bill, "material": r.material}))
        if r.attach and len(r.attach) <= CODE_MAX and material_ok:
            pairs = attaches.get(r.bill, set())
            if not any(a == r.attach and (not r.material or m == r.material) for m, a in pairs):
                r.issues.append(
                    (ATTACH_NOT_IN_BILL, {"bill_no": r.bill, "material": r.material or "—", "attach": r.attach})
                )
        if r.customer and len(r.customer) <= CODE_MAX and r.customer != (b["customer_code"] or ""):
            r.issues.append((CUSTOMER_MISMATCH, {"bill_no": r.bill, "customer": b["customer_code"] or "—"}))
    # 与号池、已导入的号重复
    sns = sorted(first)
    pool: dict[str, str] = {}
    done: dict[str, str] = {}
    share = " LOCK IN SHARE MODE" if lock else ""
    for part in _chunks(sns):
        for x in db.all(f"SELECT sn, pi_no FROM sn_item WHERE sn IN ({marks(len(part))}){share}", *part):
            pool[x["sn"]] = x["pi_no"]
        for x in db.all(
            f"SELECT sn, import_no FROM sn_import_item WHERE sn IN ({marks(len(part))}) AND deleted_at IS NULL{share}",
            *part,
        ):
            done[x["sn"]] = x["import_no"]
    for r in rows:
        # 导入的号也在号池里：导入过的报「已导入」，其余在号池里的报「号池已有」
        if r.sn in done:
            r.issues.append((IMPORTED, {"import_no": done[r.sn]}))
        elif r.sn in pool:
            r.issues.append((IN_POOL, {"pi": pool[r.sn]}))


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


_INSERT = (
    "INSERT INTO sn_import_item(import_no, sn, pi_no, bill_no, factory_code, file_name, row_no, created_by, "
    "created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)"
)
#: 号池：待分配，挂在单据序号 0；只填文件里有的 SN、PI、单据、生产组织与选填的客户编码、物料编码、米数、附件项次
#: （没填的为空串，米数没填为 NULL，与规则不带米数时相同）；没有规则与流水（seq_pi_no 空串、seq_dec = 导入记录 id、
#: 规则版本 0）
_INSERT_ITEM = (
    "INSERT INTO sn_item(sn, origin, pi_no, customer_code, bill_no, bill_seq, material_code, gen_org_code, "
    "gen_bill_no, gen_bill_seq, seq_pi_no, seq_dec, length_text, attach_no, rule_version_id, gen_batch, status, "
    "created_at, updated_at) "
    "VALUES (%s,'IMPORT',%s,%s,%s,0,%s,%s,%s,0,'',%s,%s,%s,0,%s,'PENDING_ALLOC',%s,%s)"
)


def run(cu: CurrentUser, content: bytes, file_name_in: str | None) -> dict:
    """确定导入：PI 操作锁 + 事务内重新检查，有问题行整个文件不导入；否则写入导入记录与号池（待分配）。
    返回导入单号与枚数。"""
    if not (cu.is_admin or cu.is_operator):
        raise biz(ErrorCode.FORBIDDEN)
    rows = parse(content)
    file_name = util.cut(util.trim(file_name_in), 255) or ""
    # 与生成、删号、分配、转移共用 PI 操作锁（PI 正在生成时拿不到，提示稍后再试）
    with locks.with_pi_lock(*sorted({r.pi for r in rows if r.pi})), db.tx():
        _validate(cu, rows, lock=True)
        bad = sum(1 for r in rows if r.issues)
        if bad:
            raise biz(ErrorCode.IMPORT_HAS_ISSUES, count=bad)
        now = util.now()
        no = util.next_id("IM")
        for i in range(0, len(rows), CHUNK):
            part = rows[i : i + CHUNK]
            sns = [r.sn for r in part]
            try:
                db.exec_many(
                    _INSERT,
                    [(no, r.sn, r.pi, r.bill, r.factory, file_name, r.row_no, cu.emp_no, now) for r in part],
                )
                ids = {
                    x["sn"]: x["id"]
                    for x in db.all(
                        f"SELECT id, sn FROM sn_import_item WHERE import_no = %s AND sn IN ({marks(len(sns))})",
                        no,
                        *sns,
                    )
                }
                db.exec_many(
                    _INSERT_ITEM,
                    [
                        (
                            r.sn,
                            r.pi,
                            r.customer,
                            r.bill,
                            r.material,
                            r.factory,
                            r.bill,
                            ids[r.sn],
                            r.length or None,
                            r.attach,
                            no,
                            now,
                            now,
                        )
                        for r in part
                    ],
                )
                # 导入的号以前在本系统生成、删过（可能已交付 MES）：rev 接着删前的往上加
                generate.continue_deleted_rev(sns)
            except pymysql.err.IntegrityError as ex:
                if not is_duplicate(ex):
                    raise
                # 检查之后别人刚生成或导入了同样的号
                hit = db.one(f"SELECT sn FROM sn_item WHERE sn IN ({marks(len(sns))}) LIMIT 1", *sns)
                raise biz(ErrorCode.IMPORT_CONFLICT, sn=hit["sn"] if hit else part[0].sn) from None
        bills = sorted({r.bill for r in rows})
        for b in bills:
            changes.ensure_ledger(b)
        changes.refresh_items(bills)
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
                .status(None, util.PENDING_ALLOC)
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


_FIELDS = (
    "id",
    "import_no",
    "sn",
    "pi_no",
    "bill_no",
    "factory_code",
    "file_name",
    "row_no",
    "created_by",
    "deleted_by",
)
_COLS = ", ".join(f"s.{k}" for k in _FIELDS)


def items(cu: CurrentUser, f: Filter, q: util.Page) -> dict:
    """已导入的号（新的在前）：按 PI、单据编号、SN（前缀）、导入单号筛选；普通操作员只看授权组织的。
    另带号在号池中的当前状态（待分配 / 待推送 / 已推送 / 已分配）与生产组织，及导入时填的物料编码、附件项次、米数、
    客户编码（存在号池里，导入的号不能转移、之后不变）；整单删除过的带删除人与时间（号已移出号池，这几项为空）。"""
    where = ["1 = 1"]
    args: list[Any] = []
    facs = cu.read_factories(None)
    if facs is not None:
        where.append(f"s.factory_code IN ({marks(len(facs))})")
        args += facs
    for col, v in (("pi_no", f.pi_no), ("bill_no", f.bill_no), ("import_no", f.import_no)):
        v = util.trim(v)
        if v:
            where.append(f"s.{col} = %s")
            args.append(v)
    sn = util.trim(f.sn)
    if sn:
        where.append("s.sn LIKE %s")
        args.append(util.like_prefix(sn))
    w = " AND ".join(where)
    total = db.count(f"SELECT COUNT(*) FROM sn_import_item s WHERE {w}", *args)
    rows = db.all(
        f"SELECT {_COLS}, s.created_at, s.deleted_at, i.status, i.factory_code cur_factory, i.material_code, "
        "i.attach_no, i.length_text, i.customer_code FROM sn_import_item s "
        "LEFT JOIN sn_item i ON i.sn = s.sn AND i.origin = 'IMPORT' AND i.gen_batch = s.import_no "
        f"WHERE {w} ORDER BY s.id DESC LIMIT %s OFFSET %s",
        *args,
        q.size,
        q.offset,
    )
    return util.page_result(
        [
            {
                **{k: r[k] for k in _FIELDS},
                "created_at": util.fmt(r["created_at"]),
                "deleted_at": util.fmt(r["deleted_at"]),
                "status": r["status"],
                "allocated_to": r["cur_factory"],
                "material_code": r["material_code"] or "",
                "attach_no": r["attach_no"] or "",
                "length": r["length_text"] or "",
                "customer_code": r["customer_code"] or "",
            }
            for r in rows
        ],
        total,
        q,
    )


# ================================================================== 整单删除


def _records(cu: CurrentUser, no_in: str | None) -> tuple[str, list[dict]]:
    """导入单的未删除记录；不存在或已删除报 IMPORT_NOT_FOUND；普通操作员须有导入单涉及的全部生产组织的权限。"""
    no = util.trim(no_in)
    recs = (
        db.all("SELECT * FROM sn_import_item WHERE import_no = %s AND deleted_at IS NULL ORDER BY id", no) if no else []
    )
    if not recs:
        raise biz(ErrorCode.IMPORT_NOT_FOUND, import_no=no)
    cu.check_write(*{r["factory_code"] for r in recs})
    return no, recs


def _pool(no: str, sns: list[str], lock: bool = False) -> list[dict]:
    """导入单在号池里的号（按 SN 走唯一索引，不扫全表）。"""
    out: list[dict] = []
    for part in _chunks(sns):
        out += db.all(
            f"SELECT id, sn, status, pi_no, bill_no, factory_code, gen_org_code FROM sn_item WHERE sn IN "
            f"({marks(len(part))}) AND origin = 'IMPORT' AND gen_batch = %s" + (" FOR UPDATE" if lock else ""),
            *part,
            no,
        )
    return out


def summary(cu: CurrentUser, no_in: str | None) -> dict:
    """整单删除确认：导入单的枚数、还待分配的枚数、已分配的枚数、涉及的 PI 与单据；deletable = 全部待分配。"""
    no, recs = _records(cu, no_in)
    items = _pool(no, [r["sn"] for r in recs])
    pending = sum(1 for x in items if x["status"] == util.PENDING_ALLOC)
    return {
        "import_no": no,
        "qty": len(recs),
        "pending": pending,
        "allocated": len(items) - pending,
        "pis": sorted({r["pi_no"] for r in recs}),
        "bills": sorted({r["bill_no"] for r in recs}),
        "deletable": len(items) - pending == 0,
    }


def delete(cu: CurrentUser, no_in: str | None) -> dict:
    """整单删除：导入单的号全部待分配时，从号池移除（可重新导入），导入记录标为已删除；按单据留 SN_IMPORT_DELETE 痕迹。
    有一枚已分配就整单不删（IMPORT_DELETE_ALLOCATED）。"""
    no, recs = _records(cu, no_in)
    sns = [r["sn"] for r in recs]
    pis = sorted({x["pi_no"] for x in _pool(no, sns)} | {r["pi_no"] for r in recs})
    with locks.with_pi_lock(*pis), db.tx():
        no, recs = _records(cu, no)
        items = _pool(no, [r["sn"] for r in recs], lock=True)
        if not {x["pi_no"] for x in items} <= set(pis):
            raise biz(ErrorCode.CONFLICT_RETRY)
        allocated = sum(1 for x in items if x["status"] != util.PENDING_ALLOC)
        if allocated:
            raise biz(ErrorCode.IMPORT_DELETE_ALLOCATED, import_no=no, count=allocated)
        ids = [x["id"] for x in items]
        for i in range(0, len(ids), CHUNK):
            part = ids[i : i + CHUNK]
            db.exec(f"DELETE FROM sn_item WHERE id IN ({marks(len(part))})", *part)
        now = util.now()
        db.exec(
            "UPDATE sn_import_item SET deleted_by = %s, deleted_at = %s WHERE import_no = %s AND deleted_at IS NULL",
            cu.emp_no,
            now,
            no,
        )
        by_bill: dict[tuple[str, str], list[dict]] = {}
        for x in items:
            by_bill.setdefault((x["pi_no"], x["bill_no"]), []).append(x)
        for (pi, bill), xs in sorted(by_bill.items()):
            ss = sorted(x["sn"] for x in xs)
            audit.record(
                cu,
                audit.entry(audit.SN_IMPORT_DELETE)
                .factory(xs[0]["gen_org_code"] or None)
                .pi(pi)
                .bill(bill)
                .range(ss[0], ss[-1])
                .count(len(xs))
                .status(util.PENDING_ALLOC, None)
                .batch(no),
            )
    return {"import_no": no, "qty": len(items), "bills": len(by_bill)}
