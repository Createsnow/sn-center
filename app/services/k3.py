"""金蝶 K3 Cloud WebAPI（只读，PRD 附录 D.1）。

- LoginByAppSecret 登录；批量查询用 ExecuteBillQuery 分页，单张用 View。
- 比对同步只取已审核（FDocumentStatus = 'C'）单据的单据编号与修改时间两列，比对在本地完成；
  变动的单据再按单据编号批量拉明细。全量重建按已审核条件完整拉取。
- 按单据编号同步、离开快照检测用 View，不带单据状态条件，只看单据状态。
- 不读业务状态、作废状态、需求单据号，不读写金蝶序列号字段；不返回单据内码、分录内码。
- 文本未填写时金蝶常返回一个空格：一律去首尾空格；基础资料未填写时为 null，视为空。
- 字段位置按《生产订单查询接口》：PO 为表头 F_HX_PO，修改日期为表头 FModifyDate（ModifyDate），
  附件项次为明细 FTERM，米数为明细 Flength，产品单位为明细 FProductUnit。
  字段名是接口约定，写在代码里，不放 .env。
- 另取几列只显示的明细字段（客户 P/N、客户描述、规格描述、物料规格型号、生产车间 = 生产部门），不参与比对、生成、
  变更提醒；金蝶不认这些列时本进程改用基本列（这几列留空），同步照常。
"""

from __future__ import annotations

import json
import re
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

import httpx
import structlog

from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError

FORM_PRD_MO = "PRD_MO"
FORM_ORG = "ORG_Organizations"
APPROVED = "C"
APPROVED_FILTER = "FDocumentStatus='C'"
#: 分录内码只用于稳定翻页，不返回
ORDER_BY = "FBillNo ASC, FTreeEntity_FEntryId ASC"
#: 明细查询列（顺序即 _row_of_query 的取值顺序）。前 12 列与《生产订单查询接口》的 FieldKeys 一致；
#: 之后是行号、修改日期（物料行标识、比对同步与订单页排序要用）和产品单位（SN 规则带单位时写入）
DETAIL_KEYS = (
    "FBillNo",
    "FDocumentStatus",
    "F_ora_kh.FNumber",
    "F_HX_PI",
    "F_HX_PO",
    "FPrdOrgId.FNumber",
    "FPrdOrgId.FName",
    "FTERM",
    "FMaterialId.FNumber",
    "FMaterialId.FName",
    "Flength",
    "FQty",
    "FTreeEntity_FSeq",
    "FModifyDate",
    "FProductUnit",
)
#: 只显示的明细列（接在 DETAIL_KEYS 之后，顺序即 Row 的 customer_pn … dept_name）；
#: 字段标识见 docs/k3/prd_mo_structure.md
DISPLAY_KEYS = (
    "FPN",
    "FCD",
    "FAdtek",
    "FMaterialId.FSpecification",
    "FWorkShopID.FNumber",
    "FWorkShopID.FName",
)
#: 带只显示列查询失败、改用基本列后，隔多久再试带只显示列（秒）
DISPLAY_RETRY_SECONDS = 3600
ORG_FIELDS = "FNumber,FName,FForbidStatus"
PAGE_SIZE = 2000
#: 按单据编号批量拉明细时每次的单据数
BILLS_PER_QUERY = 100
LCID_ZH = 2052
LOGIN_SVC = "Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc"
VIEW_SVC = "Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.View.common.kdsvc"
QUERY_SVC = "Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc"
REQUIRED = ("K3_SERVER_URL", "K3_ACCT_ID", "K3_APP_ID", "K3_APP_SECRET", "K3_USERNAME")


@dataclass(frozen=True)
class Row:
    """快照的一行（一条物料行）。"""

    bill_no: str
    doc_status: str
    customer_code: str
    pi: str
    po: str
    org_code: str
    org_name: str
    seq: int
    attach_no: str
    material_code: str
    material_name: str
    length_raw: str
    qty: Decimal
    modify_time: datetime | None
    unit: str = ""
    customer_pn: str = ""
    customer_desc: str = ""
    spec_desc: str = ""
    material_spec: str = ""
    dept_code: str = ""
    dept_name: str = ""


@dataclass(frozen=True)
class Head:
    """比对同步用：已审核单据的编号与修改时间。"""

    bill_no: str
    modify_time: datetime | None


@dataclass(frozen=True)
class BillView:
    """按单据编号查询的结果：found = False 为查无此单（已删除）。"""

    found: bool
    doc_status: str
    rows: list[Row]


@dataclass(frozen=True)
class Org:
    number: str
    name: str
    enabled: bool = True


@dataclass(frozen=True)
class _Config:
    server_url: str
    acct_id: str
    app_id: str
    app_secret: str
    username: str
    lcid: int
    login_timeout: int
    query_timeout: int
    view_timeout: int
    page_retries: int
    page_retry_seconds: float


def _config() -> _Config:
    missing = [k for k in REQUIRED if not settings.get(k, "")]
    if missing:
        raise biz(ErrorCode.K3_CONFIG_MISSING, missing=", ".join(missing))
    return _Config(
        settings.get("K3_SERVER_URL", ""),
        settings.get("K3_ACCT_ID", ""),
        settings.get("K3_APP_ID", ""),
        settings.get("K3_APP_SECRET", ""),
        settings.get("K3_USERNAME", ""),
        settings.get_int("K3_LCID", LCID_ZH),
        settings.get_int("K3_LOGIN_TIMEOUT", 30),
        settings.get_int("K3_QUERY_TIMEOUT", 120),
        settings.get_int("K3_VIEW_TIMEOUT", 60),
        max(0, settings.get_int("K3_PAGE_RETRIES", 3)),
        max(0.0, float(settings.get_int("K3_PAGE_RETRY_SECONDS", 10))),
    )


# ------------------------------------------------------------------ 字段归一


def _java_str(v: Any) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return repr(v)
    return str(v)


def text(v: Any) -> str:
    """文本：去首尾空格，None → 空。"""
    return "" if v is None else _java_str(v).strip()


def number_text(v: Any) -> str:
    """米数文本：金蝶返回数值时整数型浮点按整数写；文本原样（去首尾空格），生成时作为字符串写入 SN。"""
    if isinstance(v, float) and v == int(v):
        return str(int(v))
    return text(v)


def qty(v: Any) -> Decimal:
    if v is None or v == "":
        return Decimal(0)
    try:
        return Decimal(_java_str(v) if isinstance(v, (int, float)) else text(v))
    except (InvalidOperation, ValueError):
        return Decimal(0)


def seq_of(v: Any) -> int:
    try:
        return int(float(text(v)))
    except ValueError:
        return 0


def locale_name(v: Any) -> str:
    """多语言名称取简体（Key=2052）；已是字符串原样返回。"""
    if isinstance(v, str):
        return v.strip()
    if isinstance(v, list):
        for item in v:
            if isinstance(item, dict) and isinstance(item.get("Key"), (int, float)) and int(item["Key"]) == LCID_ZH:
                return text(item.get("Value"))
        return ""
    if isinstance(v, dict):
        if "Name" in v:
            return locale_name(v["Name"])
        return text(v.get("Value"))
    return text(v)


def number_of(v: Any) -> str:
    """基础资料的编码：对象为 null 或内码为 0 时视为空。"""
    if isinstance(v, dict):
        return text(v.get("Number"))
    return text(v)


_DATE_MS = re.compile(r"/Date\((-?\d+)")


def modify_time(v: Any) -> datetime | None:
    """金蝶修改时间：ISO 文本或 /Date(毫秒)/；只用于比对，取不到为 None（视为有变动）。"""
    t = text(v)
    if not t:
        return None
    m = _DATE_MS.search(t)
    if m:
        return datetime.fromtimestamp(int(m.group(1)) / 1000, UTC).replace(tzinfo=None, microsecond=0)
    try:
        return datetime.fromisoformat(t.replace("Z", "")[:19].replace(" ", "T"))
    except ValueError:
        return None


def _row_of_query(o: list) -> Row | None:
    """一行明细，列顺序见 DETAIL_KEYS + DISPLAY_KEYS（只取基本列时只显示的几列为空）。"""
    p = [o[i] if i < len(o) else None for i in range(len(DETAIL_KEYS) + len(DISPLAY_KEYS))]
    bill = text(p[0])
    if not bill:
        return None
    return Row(
        bill,
        text(p[1]),
        text(p[2]),
        text(p[3]),
        text(p[4]),
        text(p[5]),
        locale_name(p[6]),
        seq_of(p[12]),
        text(p[7]),
        text(p[8]),
        locale_name(p[9]),
        number_text(p[10]),
        qty(p[11]),
        modify_time(p[13]),
        text(p[14]),
        text(p[15]),
        text(p[16]),
        text(p[17]),
        locale_name(p[18]),
        text(p[19]),
        locale_name(p[20]),
    )


def rows_from_view(doc: dict | None) -> list[Row]:
    """View 单据：表头客户在 F_ora_Base.Number，生产组织在 PrdOrgId，PI / PO 在 F_HX_PI / F_HX_PO；
    修改日期在 ModifyDate；明细在 TreeEntity，附件项次 FTERM、米数 Flength、产品单位 FProductUnit、数量 Qty；
    只显示的客户 P/N FPN、客户描述 FCD、规格描述 FAdtek、物料规格型号 MaterialId.Specification、生产车间 WorkShopID。"""
    out: list[Row] = []
    if not doc:
        return out
    customer = number_of(doc.get("F_ora_Base"))
    org = doc.get("PrdOrgId") if isinstance(doc.get("PrdOrgId"), dict) else {}
    head = {
        "bill_no": text(doc.get("BillNo")),
        "doc_status": text(doc.get("DocumentStatus")),
        "customer_code": customer,
        "pi": text(doc.get("F_HX_PI")),
        "po": text(doc.get("F_HX_PO")),
        "org_code": text(org.get("Number")),
        "org_name": locale_name(org.get("Name")),
        "modify_time": modify_time(doc.get("ModifyDate")),
    }
    lines = doc.get("TreeEntity")
    if isinstance(lines, list):
        for lm in lines:
            if not isinstance(lm, dict):
                continue
            material = lm.get("MaterialId") if isinstance(lm.get("MaterialId"), dict) else {}
            shop = lm.get("WorkShopID") if isinstance(lm.get("WorkShopID"), dict) else {}
            out.append(
                Row(
                    head["bill_no"],
                    head["doc_status"],
                    head["customer_code"],
                    head["pi"],
                    head["po"],
                    head["org_code"],
                    head["org_name"],
                    seq_of(lm.get("Seq")),
                    text(lm.get("FTERM")),
                    text(material.get("Number")),
                    locale_name(material.get("Name")),
                    number_text(lm.get("Flength")),
                    qty(lm.get("Qty")),
                    head["modify_time"],
                    text(lm.get("FProductUnit")),
                    text(lm.get("FPN")),
                    text(lm.get("FCD")),
                    text(lm.get("FAdtek")),
                    locale_name(material.get("Specification")),
                    text(shop.get("Number")),
                    locale_name(shop.get("Name")),
                )
            )
    return out


def error_of(result: Any) -> str | None:
    if isinstance(result, list) and result:
        return error_of(result[0])
    if not isinstance(result, dict):
        return None
    r = result.get("Result")
    st = r.get("ResponseStatus") if isinstance(r, dict) else None
    if isinstance(st, dict) and st.get("IsSuccess") is False:
        msgs = []
        errs = st.get("Errors")
        if isinstance(errs, list):
            msgs = [text(e["Message"]) for e in errs if isinstance(e, dict) and e.get("Message") is not None]
        return "; ".join(msgs) if msgs else "查询失败"
    return None


# ------------------------------------------------------------------ HTTP


def _url(c: _Config, svc: str) -> str:
    return re.sub(r"/+$", "", c.server_url) + "/" + svc


def _invoke(*params: Any) -> dict:
    return {
        "format": 1,
        "useragent": "ApiClient",
        "rid": str(uuid.uuid4()),
        "parameters": list(params),
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "v": "1.0",
    }


def _client() -> httpx.Client:
    # 直连金蝶内网地址，不走系统代理
    return httpx.Client(trust_env=False, follow_redirects=True)


def _post(http: httpx.Client, url: str, payload: Any, timeout: int, on_error: ErrorCode) -> Any:
    try:
        resp = http.post(
            url,
            content=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json; charset=utf-8"},
            timeout=max(1, timeout),
        )
        if resp.status_code >= 400:
            raise biz(on_error, message=f"HTTP {resp.status_code}")
        body = json.loads(resp.content.decode("utf-8"))
        # 部分环境把 JSON 再包一层字符串
        if isinstance(body, str) and (body.startswith("[") or body.startswith("{")):
            body = json.loads(body)
        return body
    except BizError:
        raise
    except Exception as e:  # noqa: BLE001
        raise biz(on_error, message=str(e) or type(e).__name__) from None


def _login(http: httpx.Client, c: _Config) -> None:
    result = _post(
        http,
        _url(c, LOGIN_SVC),
        _invoke(c.acct_id, c.username, c.app_id, c.app_secret, c.lcid),
        c.login_timeout,
        ErrorCode.K3_LOGIN_FAILED,
    )
    lt = result.get("LoginResultType") if isinstance(result, dict) else None
    if not isinstance(lt, (int, float)) or isinstance(lt, bool) or int(lt) != 1:
        msg = text(result.get("Message")) if isinstance(result, dict) else ""
        raise biz(ErrorCode.K3_LOGIN_FAILED, message=msg or "LoginResultType != 1")
    sid = result.get("KDSVCSessionId")
    if sid is not None and text(sid):
        host = httpx.URL(_url(c, "")).host
        http.cookies.set("kdservice-sessionid", text(sid), domain=host, path="/")


def _query(http: httpx.Client, c: _Config, data: dict) -> list:
    page = _post(
        http,
        _url(c, QUERY_SVC),
        _invoke(json.dumps(data, ensure_ascii=False)),
        c.query_timeout,
        ErrorCode.K3_QUERY_FAILED,
    )
    err = error_of(page)
    if err is not None:
        raise biz(ErrorCode.K3_QUERY_FAILED, message=err)
    if not isinstance(page, list):
        raise biz(ErrorCode.K3_QUERY_FAILED, message="unexpected response")
    return page


def _query_retry(http: httpx.Client, c: _Config, data: dict) -> list:
    """一页查询：失败时间隔重试（默认 10 秒、3 次），仍失败抛出（调用方本周期不改快照）。"""
    attempt = 0
    while True:
        try:
            return _query(http, c, data)
        except BizError:
            if attempt >= c.page_retries:
                raise
            attempt += 1
            time.sleep(c.page_retry_seconds)


def _paged(http: httpx.Client, c: _Config, field_keys: str, filter_string: str, order: str, retry: bool = True) -> list:
    """分页直到本页不足一页。任何一页（重试后）失败即整体失败；retry = False 时不重试。"""
    merged: list = []
    start = 0
    while True:
        page = (_query_retry if retry else _query)(
            http,
            c,
            {
                "FormId": FORM_PRD_MO,
                "FieldKeys": field_keys,
                "FilterString": filter_string,
                "OrderString": order,
                "TopRowCount": 0,
                "StartRow": start,
                "Limit": PAGE_SIZE,
            },
        )
        merged.extend(page)
        if len(page) < PAGE_SIZE:
            return merged
        start += PAGE_SIZE


#: 金蝶不认只显示列时记下时间：此后 DISPLAY_RETRY_SECONDS 内只取基本列
_display_failed_at: float | None = None


def _detail_paged(http: httpx.Client, c: _Config, filter_string: str) -> list:
    """明细查询：先带只显示列；失败（不重试）再按基本列查询（照常重试，仍失败抛出）。
    基本列成功说明金蝶不认只显示列：记下时间，一段时间内只取基本列，快照里只显示的几列留空。"""
    global _display_failed_at
    base = ",".join(DETAIL_KEYS)
    if _display_failed_at is None or time.monotonic() - _display_failed_at >= DISPLAY_RETRY_SECONDS:
        try:
            raw = _paged(http, c, ",".join(DETAIL_KEYS + DISPLAY_KEYS), filter_string, ORDER_BY, retry=False)
            _display_failed_at = None
            return raw
        except BizError as e:
            raw = _paged(http, c, base, filter_string, ORDER_BY)
            _display_failed_at = time.monotonic()
            structlog.get_logger().warning("k3_display_fields_skipped", reason=e.message)
            return raw
    return _paged(http, c, base, filter_string, ORDER_BY)


def fetch_approved_heads() -> list[Head]:
    """比对同步：已审核单据的编号与修改时间（按明细返回多行时本地去重）。"""
    c = _config()
    with _client() as http:
        _login(http, c)
        raw = _paged(http, c, "FBillNo,FModifyDate", APPROVED_FILTER, "FBillNo ASC")
    out: dict[str, Head] = {}
    for o in raw:
        if isinstance(o, list) and o:
            no = text(o[0])
            if no and no not in out:
                out[no] = Head(no, modify_time(o[1] if len(o) > 1 else None))
    return list(out.values())


def _quote(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def fetch_bills(bill_nos: list[str]) -> list[Row]:
    """按单据编号批量拉已审核单据的全部物料行（不按业务状态过滤）。"""
    if not bill_nos:
        return []
    c = _config()
    rows: list[Row] = []
    with _client() as http:
        _login(http, c)
        for i in range(0, len(bill_nos), BILLS_PER_QUERY):
            part = bill_nos[i : i + BILLS_PER_QUERY]
            flt = f"{APPROVED_FILTER} AND FBillNo IN ({','.join(_quote(b) for b in part)})"
            for o in _detail_paged(http, c, flt):
                r = _row_of_query(o) if isinstance(o, list) else None
                if r is not None:
                    rows.append(r)
    return rows


def fetch_all() -> list[Row]:
    """全量重建：按已审核条件完整拉取。任何一页失败即整体失败，调用方不写快照。"""
    c = _config()
    with _client() as http:
        _login(http, c)
        raw = _detail_paged(http, c, APPROVED_FILTER)
    return [r for r in (_row_of_query(o) for o in raw if isinstance(o, list)) if r is not None]


def view_bill(bill_no: str) -> BillView:
    """按单据编号查询（不带单据状态条件）。查无此单返回 found = False；调用失败抛出。"""
    c = _config()
    with _client() as http:
        _login(http, c)
        data = {"CreateOrgId": 0, "Number": bill_no, "Id": "", "IsSortBySeq": True}
        body = _post(
            http,
            _url(c, VIEW_SVC),
            _invoke(FORM_PRD_MO, json.dumps(data, ensure_ascii=False)),
            c.view_timeout,
            ErrorCode.K3_QUERY_FAILED,
        )
    result = body.get("Result") if isinstance(body, dict) and isinstance(body.get("Result"), dict) else {}
    st = result.get("ResponseStatus") if isinstance(result.get("ResponseStatus"), dict) else {}
    if st.get("IsSuccess") is not True:
        # MsgCode 10 = 未找到对应数据：单据已删除
        code = st.get("MsgCode")
        if isinstance(code, (int, float)) and not isinstance(code, bool) and int(code) == 10:
            return BillView(False, "", [])
        raise biz(ErrorCode.K3_QUERY_FAILED, message=error_of(body) or "View failed")
    doc = result.get("Result") if isinstance(result.get("Result"), dict) else {}
    rows = [r for r in rows_from_view(doc) if r.bill_no == bill_no]
    return BillView(True, text(doc.get("DocumentStatus")), rows)


def fetch_organizations() -> list[Org]:
    """组织机构（含已禁用的：FForbidStatus 不是 A 即禁用）。"""
    c = _config()
    with _client() as http:
        _login(http, c)
        raw = []
        start = 0
        while True:
            page = _query(
                http,
                c,
                {
                    "FormId": FORM_ORG,
                    "FieldKeys": ORG_FIELDS,
                    "FilterString": "",
                    "OrderString": "FNumber",
                    "TopRowCount": 0,
                    "StartRow": start,
                    "Limit": PAGE_SIZE,
                },
            )
            raw.extend(page)
            if len(page) < PAGE_SIZE:
                break
            start += PAGE_SIZE
    out: list[Org] = []
    seen: set[str] = set()
    for o in raw:
        if isinstance(o, list) and len(o) >= 2:
            number, name = text(o[0]), locale_name(o[1])
            forbid = text(o[2]) if len(o) > 2 else "A"
            if number and number not in seen:
                seen.add(number)
                out.append(Org(number, name or number, forbid.upper() == "A"))
    return out
