"""SN 导入：本系统之外已有的 SN 按导入模板导入号池，进入 SN 分配页（待分配）；
导入功能自成一套，记录只用 sn_import_item。

- 模板（template，列见 COLUMNS，与分配页「已分配」下载的列对应，另加单据编号、ERP行号、客户编码）：
  PI、单据编号、ERP行号（也认「行号」「单据行号」）、物料编码（也认「料号」「物料编号」）、完整 SN（也认「SN」）、
  流水位数、进制位数、流水号、十进制流水号（也认「十进制流水」）、附件项次、米数、产品单位、生产组织编号、生产组织、
  客户编码。流水号、米数、产品单位、生产组织编号选填，其余必填（生产组织即名称，必填）。
- 文件：xlsx，首个非空行为表头，按列名认列（去空白、不分大小写比较；顺序不限）：必填的列表头必须有，缺了列出缺哪几列；
  其他列名的列不读；同一列出现两次（含别名）不能导入。之后每行一枚号；认得的列都为空的行跳过。
- 每行的条件（逐行列出不能导入的原因）：必填的都要填，各列不超长；SN 不含空白，文件内不重复，也不能与号池
  （已生成、已导入的号）重复；单据须在订单快照中，PI、生产组织（名称）、客户编码与单据相同（生产组织编号填了也须相符）；
  普通操作员只能导入授权生产组织的单据；ERP行号须是该单据的金蝶明细行号，物料编码与该行相同，附件项次与该行生效的
  附件项次相同（金蝶与生成页都没有时不能导入，先在生成页补填），米数填了须与该行相同（转移按「同料号、同附件项次、
  米数相符」配对）；该单据在本系统已有生成的号（不论状态、哪个物料行）或已分配的号（含已分配的导入号）时不能导入
  （只有待分配的导入号时可以继续导入）；进制位数为 10 / 16 / 32 / 36，流水位数 1～12；十进制流水号为非负整数、
  不超出该进制该位数能表示的最大值；流水号没填时按十进制流水号换算（补前导 0），填了须长度等于流水位数、
  只用该进制默认字符表里的字符，且换算出的十进制与十进制流水号相同。
- 先检查（不落库），再「确定导入」：有一行不能导入整个文件都不导入；确定导入时在 PI 操作锁与一个事务里重新检查一遍，
  全部写入，一次导入一个导入单号。
- 写入：导入记录 sn_import_item（全部列；之后只有 cur_bill_seq 随转移改）+ 号池 sn_item（结构不变：origin = IMPORT，
  待分配，挂在单据序号 0 上，seq_pi_no 为空串、seq_dec 为导入记录 id、rule_version_id 为 0；物料编码、附件项次、
  米数、客户编码、生产组织照常存在号池，供分配、转移、推送用）。与生成一样建立已出号单据台账、带上暂停标记。
- 推送 / 拉取 / 转出给 MES 的明细与生成的号同一结构、字段都有值：billSeq 为当前行号、serial 为流水号、
  serialDec 为十进制流水、serialPi 为导入的 PI（见 import_details），其余取号池。导入的号与生成的号不进同一批次。
- 导入的号可分配，分配后可转移（与生成的号同一套规则，见 transfers）；不出现在生成页（号段、单据分配状态、
  已生成数都不计），不能删号，也不占订单的可生成数量。
- 完整 SN 全局唯一由号池的唯一键保证：导入撞上别人刚生成或导入的号整次不导入，生成撞上导入的号整次回滚。
- 整单删除（导入页）：一个导入单号的号全部还是待分配时，可整单删除——号从号池移除（之后可重新导入同样的号），
  导入记录保留并标为已删除；有一枚已分配（含待推送、已推送、已转移）就不能删。与导入、分配共用 PI 操作锁。
"""

from __future__ import annotations

import io
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass, field
from typing import Any

import pymysql
from openpyxl import Workbook, load_workbook
from openpyxl.comments import Comment
from openpyxl.styles import Font, PatternFill
from openpyxl.utils import get_column_letter

from app.core import encoding as codec
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
#: 生产组织名称最长 128
ORG_MAX = 128
#: 十进制流水号最长 19 位（36 进制 12 位流水最大 4738381338321616895，在 BIGINT 内）
DEC_MAX = 19
#: 进制位数
BASES = tuple(sorted(codec.DEFAULT_CHARSETS))


@dataclass(frozen=True)
class Column:
    #: Row 的字段名
    field: str
    #: 列名（模板里写这个）
    name: str
    #: 必填：表头必须有这一列，每行都要填
    required: bool
    #: 也认的列名
    aliases: tuple[str, ...] = ()
    #: 最长字符数
    max: int = CODE_MAX
    #: 模板「填写说明」页的说明与示例
    hint: str = ""
    example: str = ""

    @property
    def title(self) -> str:
        """模板表头：必填列前加 *（导入时 * 不参与比较，填不填都认）。"""
        return f"{REQUIRED_MARK}{self.name}" if self.required else self.name


#: 模板表头里必填列的标记
REQUIRED_MARK = "*"


#: 固定列（模板按这个顺序出；导入时按列名认、顺序不限，其他列不读）
COLUMNS = (
    Column("pi", "PI", True, hint="生产订单的 PI，须与单据一致", example="000001"),
    Column("bill", "单据编号", True, hint="金蝶生产订单编号，须在生产订单中", example="VT2026090001"),
    Column(
        "seq_in",
        "ERP行号",
        True,
        ("行号", "单据行号"),
        hint="该单据在金蝶里的明细行号，正整数（列名也可写作「行号」「单据行号」）",
        example="1",
    ),
    Column(
        "material",
        "物料编码",
        True,
        ("料号", "物料编号"),
        hint="须与该行的物料编码相同（列名也可写作「料号」「物料编号」）",
        example="9148363",
    ),
    Column(
        "sn",
        "完整 SN",
        True,
        ("SN",),
        SN_MAX,
        hint=f"不含空格，最长 {SN_MAX} 位；不能与系统里已有的号、文件里其他行重复（列名也可写作「SN」）",
        example="HX100000001A",
    ),
    Column("seq_len_in", "流水位数", True, hint=f"1～{codec.MAX_SEQ_LEN}，等于流水号的位数", example="6"),
    Column("base_in", "进制位数", True, hint="10 / 16 / 32 / 36", example="10"),
    Column(
        "serial",
        "流水号",
        False,
        (),
        codec.MAX_SEQ_LEN,
        hint="选填；不填时按十进制流水号、进制位数、流水位数换算（补前导 0）。填了须按进制位数书写，位数等于流水位数，"
        "保留前导 0，字母大写，32 进制不用 I、L、O、U，且与十进制流水号换算的相同",
        example="000001",
    ),
    Column(
        "dec_in",
        "十进制流水号",
        True,
        ("十进制流水",),
        DEC_MAX,
        hint="非负整数（十进制）；须在进制位数、流水位数能表示的范围内，流水号按它换算",
        example="1",
    ),
    Column(
        "attach",
        "附件项次",
        True,
        hint="须与该单据该行的附件项次相同（该行没有附件项次时不能导入，可先在 SN 生成页补填）",
        example="A01",
    ),
    Column("length", "米数", False, hint="选填；填了须与该行的米数相同，原样保存", example="0100"),
    Column("unit", "产品单位", False, hint="选填", example="M"),
    Column("factory_in", "生产组织编号", False, hint="选填；填了须与单据的生产组织编码相同", example="110"),
    Column("org_name", "生产组织", True, (), ORG_MAX, hint="须与单据的生产组织名称相同", example="越南工厂"),
    Column("customer", "客户编码", True, hint="须与单据的客户相同", example="0001"),
)
HEADERS = [c.name for c in COLUMNS]
REQUIRED = [c.name for c in COLUMNS if c.required]
OPTIONAL = [c.name for c in COLUMNS if not c.required]
#: 只检查长度、不另外对照的数字列（行号、位数、进制另查是不是数）
_NUMERIC = {"seq_in", "seq_len_in", "base_in", "dec_in"}

# 行的问题（页面按代码翻译，params 为占位参数；column 为 Row 字段名，页面换成列名）
EMPTY = "EMPTY"
TOO_LONG = "TOO_LONG"
SN_SPACE = "SN_SPACE"
FILE_DUP = "FILE_DUP"
IN_POOL = "IN_POOL"
IMPORTED = "IMPORTED"
BILL_NOT_FOUND = "BILL_NOT_FOUND"
PI_MISMATCH = "PI_MISMATCH"
ORG_MISMATCH = "ORG_MISMATCH"
ORG_NAME_MISMATCH = "ORG_NAME_MISMATCH"
CUSTOMER_MISMATCH = "CUSTOMER_MISMATCH"
FACTORY_FORBIDDEN = "FACTORY_FORBIDDEN"
SEQ_INVALID = "SEQ_INVALID"
LINE_NOT_FOUND = "LINE_NOT_FOUND"
MATERIAL_MISMATCH = "MATERIAL_MISMATCH"
ATTACH_MISMATCH = "ATTACH_MISMATCH"
LENGTH_MISMATCH = "LENGTH_MISMATCH"
BASE_INVALID = "BASE_INVALID"
SEQ_LEN_INVALID = "SEQ_LEN_INVALID"
SERIAL_LEN_MISMATCH = "SERIAL_LEN_MISMATCH"
SERIAL_CHAR_INVALID = "SERIAL_CHAR_INVALID"
DEC_INVALID = "DEC_INVALID"
DEC_OVERFLOW = "DEC_OVERFLOW"
SERIAL_DEC_MISMATCH = "SERIAL_DEC_MISMATCH"
LINE_NO_ATTACH = "LINE_NO_ATTACH"
BILL_HAS_SN = "BILL_HAS_SN"


@dataclass
class Row:
    """文件里的一行（各列为去首尾空白的字符串，空串 = 没填）及检查时算出的值。"""

    row_no: int
    pi: str = ""
    bill: str = ""
    seq_in: str = ""
    material: str = ""
    sn: str = ""
    seq_len_in: str = ""
    base_in: str = ""
    serial: str = ""
    dec_in: str = ""
    attach: str = ""
    length: str = ""
    unit: str = ""
    factory_in: str = ""
    org_name: str = ""
    customer: str = ""
    #: 行号、流水位数、进制位数、十进制流水（检查通过才有值；流水号没填时按十进制流水换算后写回 serial）
    seq: int | None = None
    seq_len: int | None = None
    base: int | None = None
    seq_dec: int | None = None
    #: 单据在订单快照中的生产组织编码（文件里的生产组织编号选填，填了须与它相同；登记、授权范围按它）
    factory: str = ""
    issues: list[tuple[str, dict]] = field(default_factory=list)


# ================================================================== 模板与解析


def template() -> Iterator[bytes]:
    """导入模板：第一张表「SN导入」只有一行表头（固定列 COLUMNS；必填列前加 * 并标红，每个列名带批注说明），
    各列设为文本格式（长串数字的 SN、带前导 0 的流水号和米数不会被 Excel 改写）；
    第二张表「填写说明」逐列列出是否必填、填写要求与示例（导入只读第一张表）。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "SN导入"
    ws.append([c.title for c in COLUMNS])
    for i, c in enumerate(COLUMNS):
        col = get_column_letter(i + 1)
        ws.column_dimensions[col].width = 34 if c.field == "sn" else 12 if c.field in _NUMERIC else 16
        ws.column_dimensions[col].number_format = "@"
        cell = ws.cell(row=1, column=i + 1)
        cell.font = Font(bold=True, color=_RED if c.required else _GREY)
        cell.fill = PatternFill("solid", fgColor=_REQUIRED_FILL if c.required else _OPTIONAL_FILL)
        cell.comment = Comment(f"{'必填' if c.required else '选填'}：{c.hint}\n示例：{c.example}", "SN 中台")
    ws.freeze_panes = "A2"
    guide = wb.create_sheet("填写说明")
    guide.append(["列名", "是否必填", "填写要求", "示例"])
    for c in COLUMNS:
        guide.append([c.name, "必填" if c.required else "选填", c.hint, c.example])
        if c.required:
            guide.cell(row=guide.max_row, column=2).font = Font(bold=True, color=_RED)
    guide.append([])
    for line in _GUIDE_NOTES:
        guide.append([line])
        guide.merge_cells(start_row=guide.max_row, start_column=1, end_row=guide.max_row, end_column=4)
    for cell in guide[1]:
        cell.font = Font(bold=True)
    for col, width in zip("ABCD", (16, 10, 70, 18), strict=True):
        guide.column_dimensions[col].width = width
    out = io.BytesIO()
    wb.save(out)
    yield out.getvalue()


_RED = "C00000"
_GREY = "595959"
_REQUIRED_FILL = "FDE9E7"
_OPTIONAL_FILL = "F2F2F2"
#: 「填写说明」页末尾的通用说明
_GUIDE_NOTES = (
    "说明：",
    "1. 在「SN导入」表填写，一行一枚号；表头列名不要改（列名前的 * 表示必填，保留或去掉都可以），列的顺序可以调整，"
    "多出的其他列不读。",
    "2. 标 * 的列必须有，且每行都要填；选填的列可以整列不要。",
    "3. 各列已设为文本格式，流水号、米数的前导 0 会原样保留，请不要改成数字格式（十进制流水号较长时也请保持文本，"
    "Excel 数字格式超过 15 位会丢精度）。",
    "4. 流水号可以不填，系统按十进制流水号、进制位数、流水位数换算；填了须与十进制流水号换算的一致。",
    "5. PI、单据编号、ERP行号、物料编码、附件项次、生产组织须与该单据（及该行）一致；"
    "该单据在系统中已有生成或已分配的 SN 时不能导入（只有导入的、还待分配的号时可以继续导入）。",
    "6. 导入时先检查，逐行列出不能导入的原因；有一行不能导入，整个文件都不导入。",
)


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
    """表头比较用：去掉空白与必填标记 *（含全角＊），不分大小写。"""
    return "".join(_text(v).split()).lower().lstrip(REQUIRED_MARK + "＊").rstrip(REQUIRED_MARK + "＊")


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


def _int(v: str) -> int | None:
    """正整数（Excel 里的数值读出来是 "3"）；不是返回 None。"""
    return int(v) if v.isdigit() and int(v) > 0 else None


def _check_values(r: Row, first: dict[str, int]) -> None:
    """不查库的检查：必填、超长、SN 空白与文件内重复、行号、进制、流水位数、流水号（通过的算出数值）。"""
    for c in COLUMNS:
        v = getattr(r, c.field)
        if not v:
            if c.required:
                r.issues.append((EMPTY, {"column": c.field}))
        elif len(v) > c.max:
            r.issues.append((TOO_LONG, {"column": c.field, "max": c.max}))
    if r.sn and len(r.sn) <= SN_MAX:
        if any(ch.isspace() for ch in r.sn):
            r.issues.append((SN_SPACE, {}))
        if r.sn in first:
            r.issues.append((FILE_DUP, {"row": first[r.sn]}))
        else:
            first[r.sn] = r.row_no
    if r.seq_in:
        r.seq = _int(r.seq_in)
        if r.seq is None:
            r.issues.append((SEQ_INVALID, {}))
    if r.base_in:
        r.base = _int(r.base_in)
        if r.base not in BASES:
            r.base = None
            r.issues.append((BASE_INVALID, {"bases": " / ".join(map(str, BASES))}))
    if r.seq_len_in:
        r.seq_len = _int(r.seq_len_in)
        if r.seq_len is None or r.seq_len > codec.MAX_SEQ_LEN:
            r.seq_len = None
            r.issues.append((SEQ_LEN_INVALID, {"max": codec.MAX_SEQ_LEN}))
    # 十进制流水号（必填）：非负整数；流水号（选填）没填按它换算，填了须与它换算的相同
    dec: int | None = None
    if r.dec_in and len(r.dec_in) <= DEC_MAX:
        if r.dec_in.isascii() and r.dec_in.isdigit():
            dec = int(r.dec_in)
        else:
            r.issues.append((DEC_INVALID, {}))
    if r.serial:
        if r.seq_len is not None and len(r.serial) != r.seq_len:
            r.issues.append((SERIAL_LEN_MISMATCH, {"len": r.seq_len}))
        elif r.base is not None and r.seq_len is not None:
            chars = codec.DEFAULT_CHARSETS[r.base]
            if any(ch not in chars for ch in r.serial):
                r.issues.append((SERIAL_CHAR_INVALID, {"base": r.base, "chars": chars}))
            elif dec is not None:
                v = sum(chars.index(ch) * r.base**i for i, ch in enumerate(reversed(r.serial)))
                if v != dec:
                    r.issues.append((SERIAL_DEC_MISMATCH, {"serial": r.serial, "dec": v}))
                else:
                    r.seq_dec = dec
    elif dec is not None and r.base is not None and r.seq_len is not None:
        top = r.base**r.seq_len - 1
        if dec > top:
            r.issues.append((DEC_OVERFLOW, {"base": r.base, "len": r.seq_len, "max": top}))
        else:
            r.seq_dec = dec
            r.serial = _encode(dec, r.base, r.seq_len)


def _encode(dec: int, base: int, n: int) -> str:
    """十进制流水 → 按进制默认字符表写成 n 位（左侧补 0）；调用方保证不超出 n 位。"""
    chars = codec.DEFAULT_CHARSETS[base]
    out = []
    for _ in range(n):
        dec, d = divmod(dec, base)
        out.append(chars[d])
    return "".join(reversed(out))


def _snapshot(bills: list[str]) -> tuple[dict[str, dict], dict[tuple[str, int], dict]]:
    """单据（PI、生产组织、客户）与其物料行（带生效的附件项次、米数，含生成页补填的），按单据编号、（单据, 行号）。"""
    heads: dict[str, dict] = {}
    lines: dict[tuple[str, int], dict] = {}
    for part in _chunks(bills):
        for b in db.all(
            "SELECT m.bill_no, MIN(m.pi) pi, MIN(m.org_code) org_code, MIN(m.org_name) org_name, "
            "MIN(m.customer_code) customer_code, MIN(f.factory_name) factory_name "
            "FROM sn_prd_mo m LEFT JOIN sn_factory f ON f.factory_code = m.org_code "
            f"WHERE m.bill_no IN ({marks(len(part))}) GROUP BY m.bill_no",
            *part,
        ):
            heads[b["bill_no"]] = b
        ls = db.all(f"SELECT * FROM sn_prd_mo WHERE bill_no IN ({marks(len(part))}) ORDER BY bill_no, seq", *part)
        for x in orders.effective_lines(ls):
            lines[(x["bill_no"], int(x["seq"]))] = x
    return heads, lines


def _used_bills(bills: list[str]) -> dict[str, int]:
    """单据 → 本系统里挂在该单据上、已生成或已分配的号的枚数（按号的当前挂靠，不分物料行）：
    生成的号（origin = GEN，不论状态）+ 已分配的导入号（待推送、已推送、已分配）。
    只有待分配的导入号不算（同一单据可分几次导入）。"""
    out: dict[str, int] = {}
    for part in _chunks(bills):
        for x in db.all(
            f"SELECT bill_no, COUNT(*) n FROM sn_item WHERE bill_no IN ({marks(len(part))}) "
            "AND (origin = 'GEN' OR status <> 'PENDING_ALLOC') GROUP BY bill_no",
            *part,
        ):
            out[x["bill_no"]] = int(x["n"])
    return out


def _check_order(cu: CurrentUser, r: Row, b: dict, line: dict | None, used: dict[str, int]) -> None:
    """与订单快照对照：PI、生产组织（名称必填、编号选填）、客户，行号对应的物料行的物料编码、附件项次（须与该行
    生效的附件项次相同，该行没有附件项次不能导入）、米数；授权范围；该单据在本系统已有生成或已分配的号不能导入。"""
    r.factory = b["org_code"] or ""
    if r.pi and b["pi"] != r.pi:
        r.issues.append((PI_MISMATCH, {"bill_no": r.bill, "pi": b["pi"]}))
    name = b["factory_name"] or b["org_name"] or ""
    org = {"bill_no": r.bill, "factory": f"{r.factory} {name}".strip()}
    if r.factory_in and r.factory_in != r.factory:
        r.issues.append((ORG_MISMATCH, org))
    elif r.org_name and r.org_name not in {b["org_name"], b["factory_name"]} - {None, ""}:
        # 编号填了且相符：只是名称不对；没填编号：生产组织（按名称）与单据不符
        if r.factory_in:
            r.issues.append((ORG_NAME_MISMATCH, {"factory": r.factory, "name": name or "—"}))
        else:
            r.issues.append((ORG_MISMATCH, org))
    if r.customer and r.customer != (b["customer_code"] or ""):
        r.issues.append((CUSTOMER_MISMATCH, {"bill_no": r.bill, "customer": b["customer_code"] or "—"}))
    if not cu.is_admin and r.factory not in cu.factories:
        r.issues.append((FACTORY_FORBIDDEN, {"factory": r.factory}))
    n = used.get(r.bill, 0)
    if n:
        r.issues.append((BILL_HAS_SN, {"bill_no": r.bill, "count": n}))
    if r.seq is None:
        return
    if line is None:
        r.issues.append((LINE_NOT_FOUND, {"bill_no": r.bill, "seq": r.seq}))
        return
    at = {"bill_no": r.bill, "seq": r.seq}
    if r.material and r.material != (line["material_code"] or ""):
        r.issues.append((MATERIAL_MISMATCH, {**at, "material": line["material_code"] or "—"}))
    eff_attach = line["eff_attach"] or ""
    if r.attach and r.attach != eff_attach:
        if eff_attach:
            r.issues.append((ATTACH_MISMATCH, {**at, "attach": eff_attach}))
        else:
            r.issues.append((LINE_NO_ATTACH, at))
    eff_length = line["eff_length"] or ""
    if r.length and r.length != eff_length:
        r.issues.append((LENGTH_MISMATCH, {**at, "length": eff_length or "—"}))


def _validate(cu: CurrentUser, rows: list[Row], lock: bool = False) -> None:
    """逐行检查，问题记到 row.issues。lock = True（确定导入时）查号池用加锁读，读到最新提交的号。"""
    first: dict[str, int] = {}
    for r in rows:
        _check_values(r, first)
    bills = sorted({r.bill for r in rows if r.bill and len(r.bill) <= CODE_MAX})
    heads, lines = _snapshot(bills)
    # 确定导入时已持有这些 PI 的操作锁（生成、分配、转移同一把锁），读到的已生成 / 已分配不会在导入中途变
    used = _used_bills(sorted(heads))
    for r in rows:
        if not r.bill or len(r.bill) > CODE_MAX:
            continue
        b = heads.get(r.bill)
        if b is None:
            r.issues.append((BILL_NOT_FOUND, {"bill_no": r.bill}))
            continue
        _check_order(cu, r, b, None if r.seq is None else lines.get((r.bill, r.seq)), used)
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


#: 导入记录：文件里的全部列（当前行号 cur_bill_seq 先同行号，号转移时随号改）
_INSERT = (
    "INSERT INTO sn_import_item(import_no, sn, pi_no, bill_no, bill_seq, cur_bill_seq, material_code, attach_no, "
    "length_text, serial_text, seq_base, seq_len, seq_dec, unit, customer_code, factory_code, file_name, row_no, "
    "created_by, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
)
#: 号池（结构不变）：待分配，挂在单据序号 0；SN、PI、单据、生产组织、客户编码、物料编码、附件项次、米数（没填为 NULL，
#: 与规则不带米数时相同）取文件里的；没有规则与流水（seq_pi_no 空串、seq_dec = 导入记录 id、规则版本 0），
#: 行号、流水号等在导入记录里
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
                    [
                        (
                            no,
                            r.sn,
                            r.pi,
                            r.bill,
                            r.seq,
                            r.seq,
                            r.material,
                            r.attach,
                            r.length or None,
                            r.serial,
                            r.base,
                            r.seq_len,
                            r.seq_dec,
                            r.unit,
                            r.customer,
                            r.factory,
                            file_name,
                            r.row_no,
                            cu.emp_no,
                            now,
                        )
                        for r in part
                    ],
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
    "bill_seq",
    "cur_bill_seq",
    "material_code",
    "attach_no",
    "serial_text",
    "seq_base",
    "seq_len",
    "unit",
    "customer_code",
    "factory_code",
    "file_name",
    "row_no",
    "created_by",
    "deleted_by",
)
_COLS = ", ".join(f"s.{k}" for k in _FIELDS)
#: 导入记录 → 号池里的这枚号（号池的 seq_dec 即导入记录 id，走流水唯一键）
IMPORT_JOIN = "i.seq_pi_no = '' AND i.seq_dec = s.id AND i.origin = 'IMPORT'"


def items(cu: CurrentUser, f: Filter, q: util.Page) -> dict:
    """已导入的号（新的在前）：按 PI、单据编号、SN（前缀）、导入单号筛选；普通操作员只看授权组织的（按导入时的组织）。
    带导入时的各列，及号在号池中的当前挂靠（PI、单据、行号、生产组织；转移后与导入时不同）与状态；
    整单删除过的带删除人与时间（号已移出号池，当前挂靠为空）。"""
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
        f"SELECT {_COLS}, s.length_text, s.created_at, s.deleted_at, i.status, i.factory_code cur_factory, "
        "i.pi_no cur_pi, i.bill_no cur_bill FROM sn_import_item s "
        f"LEFT JOIN sn_item i ON {IMPORT_JOIN} "
        f"WHERE {w} ORDER BY s.id DESC LIMIT %s OFFSET %s",
        *args,
        q.size,
        q.offset,
    )
    return util.page_result(
        [
            {
                **{k: r[k] for k in _FIELDS},
                "length": r["length_text"] or "",
                "created_at": util.fmt(r["created_at"]),
                "deleted_at": util.fmt(r["deleted_at"]),
                "status": r["status"],
                "allocated_to": r["cur_factory"],
                "cur_pi": r["cur_pi"],
                "cur_bill": r["cur_bill"],
            }
            for r in rows
        ],
        total,
        q,
    )


def with_details(rows: list[dict]) -> list[dict]:
    """rows（号池的行，带 seq_pi_no、seq_dec）里 SN 导入的号带上导入记录 r["import"]（见 details），导出、查询用；
    生成的号不变，没有导入的号时不查库。"""
    imp = [r for r in rows if not r["seq_pi_no"]]
    if imp:
        d = details([r["seq_dec"] for r in imp])
        for r in imp:
            r["import"] = d.get(int(r["seq_dec"]))
    return rows


def details(ids: list[int]) -> dict[int, dict]:
    """导入记录 id（号池里导入的号的 seq_dec）→ 行号、当前行号、流水号、进制、流水位数、十进制流水、产品单位、PI。
    推送 / 拉取 / 转出的明细与导出、查询用；没有导入的号时不查库。"""
    out: dict[int, dict] = {}
    uniq = sorted({int(x) for x in ids})
    for i in range(0, len(uniq), CHUNK):
        part = uniq[i : i + CHUNK]
        for r in db.all(
            "SELECT id, pi_no, bill_seq, cur_bill_seq, serial_text, seq_base, seq_len, seq_dec, unit "
            f"FROM sn_import_item WHERE id IN ({marks(len(part))})",
            *part,
        ):
            out[int(r["id"])] = r
    return out


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
