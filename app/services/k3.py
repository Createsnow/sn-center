"""金蝶 K3 Cloud WebAPI（只读）：LoginByAppSecret 登录；全量用 ExecuteBillQuery 分页；单张用 View；
组织机构用 ExecuteBillQuery(ORG_Organizations)。只保留业务状态 1 计划、2 计划确认的明细行，不返回单据内码。
"""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, InvalidOperation
from typing import Any

import httpx

from app.core.config import settings
from app.core.errors import ErrorCode, biz
from app.core.exceptions import BizError

FORM_PRD_MO = "PRD_MO"
FORM_ORG = "ORG_Organizations"
FIELD_KEYS = (
    "F_ora_kh.FNumber,FBillNo,F_HX_PO,F_HX_PI,"
    "FMaterialId.FNumber,FMaterialId.FName,FQty,FStatus,FSaleOrderNo,FPrdOrgId.FName"
)
STATUS_FILTER = "(FStatus='1' OR FStatus='2')"
ORDER_BY = "FID ASC, FTreeEntity_FEntryId ASC"
ORG_FIELDS = "FNumber,FName,FForbidStatus"
KEEP_STATUS = frozenset(("1", "2"))
PAGE_SIZE = 2000
LCID_ZH = 2052
LOGIN_SVC = "Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc"
VIEW_SVC = "Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.View.common.kdsvc"
QUERY_SVC = "Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc"
REQUIRED = ("K3_SERVER_URL", "K3_ACCT_ID", "K3_APP_ID", "K3_APP_SECRET", "K3_USERNAME")


@dataclass(frozen=True)
class Row:
    """快照的一行（金蝶顺序即物料行顺序）。"""

    customer_number: str
    bill_no: str
    po: str
    pi: str
    material_number: str
    material_name: str
    qty: Decimal
    status: str
    demand_bill_no: str
    prd_org_name: str


@dataclass(frozen=True)
class Org:
    number: str
    name: str


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
    )


# ------------------------------------------------------------------ 字段归一


def _java_str(v: Any) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return repr(v)
    return str(v)


def text(v: Any) -> str:
    return "" if v is None else _java_str(v).strip()


def status(v: Any) -> str:
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        d = float(v)
        return str(int(d)) if d == int(d) else repr(d)
    t = text(v)
    return t[: t.index(".")] if re.fullmatch(r"\d+\.0+", t) else t


def qty(v: Any) -> Decimal:
    if v is None or v == "":
        return Decimal(0)
    try:
        return Decimal(_java_str(v) if isinstance(v, (int, float)) else text(v))
    except (InvalidOperation, ValueError):
        return Decimal(0)


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


def row(customer, bill_no, po, pi, material_no, material_name, q, st, demand, org) -> Row | None:
    code = status(st)
    if code not in KEEP_STATUS:
        return None
    return Row(
        text(customer),
        text(bill_no),
        text(po),
        text(pi),
        text(material_no),
        locale_name(material_name),
        qty(q),
        code,
        text(demand),
        locale_name(org),
    )


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


def rows_from_query(raw: list) -> list[Row]:
    out = []
    for o in raw:
        if not isinstance(o, list):
            continue
        p = [o[i] if i < len(o) else None for i in range(10)]
        r = row(*p)
        if r is not None:
            out.append(r)
    return out


def rows_from_view(doc: dict | None) -> list[Row]:
    """View 单据：表头客户在 F_ora_Base.Number，明细在 TreeEntity。"""
    out: list[Row] = []
    if not doc:
        return out
    base = doc.get("F_ora_Base")
    customer = base.get("Number") if isinstance(base, dict) else ""
    org = doc.get("PrdOrgId")
    lines = doc.get("TreeEntity")
    if isinstance(lines, list):
        for lm in lines:
            if not isinstance(lm, dict):
                continue
            material = lm.get("MaterialId") if isinstance(lm.get("MaterialId"), dict) else {}
            r = row(
                customer,
                doc.get("BillNo"),
                doc.get("F_HX_PO"),
                doc.get("F_HX_PI"),
                material.get("Number"),
                material.get("Name"),
                lm.get("Qty"),
                lm.get("Status"),
                lm.get("SaleOrderNo"),
                org,
            )
            if r is not None:
                out.append(r)
    return out


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


def fetch_all() -> list[Row]:
    """全量：分页直到本页不足 2000 行。任何一页失败即整体失败，调用方不写快照。"""
    c = _config()
    with _client() as http:
        _login(http, c)
        merged: list = []
        start = 0
        while True:
            page = _query(
                http,
                c,
                {
                    "FormId": FORM_PRD_MO,
                    "FieldKeys": FIELD_KEYS,
                    "FilterString": STATUS_FILTER,
                    "OrderString": ORDER_BY,
                    "TopRowCount": 0,
                    "StartRow": start,
                    "Limit": PAGE_SIZE,
                },
            )
            merged.extend(page)
            if len(page) < PAGE_SIZE:
                break
            start += PAGE_SIZE
    return rows_from_query(merged)


def fetch_bill(bill_no: str) -> list[Row]:
    """单张：View。单据不存在时金蝶返回失败或空，一律视为「该单已不在计划 / 计划确认」。"""
    c = _config()
    with _client() as http:
        _login(http, c)
        data = {"CreateOrgId": 0, "Number": bill_no, "Id": "", "IsSortBySeq": False}
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
        # MsgCode 10 = 未找到对应数据：单据已删除，按「从快照消失」处理
        code = st.get("MsgCode")
        if isinstance(code, (int, float)) and not isinstance(code, bool) and int(code) == 10:
            return []
        raise biz(ErrorCode.K3_QUERY_FAILED, message=error_of(body) or "View failed")
    doc = result.get("Result") if isinstance(result.get("Result"), dict) else {}
    return rows_from_view(doc)


def fetch_organizations() -> list[Org]:
    """未禁用（FForbidStatus=A）的组织机构。"""
    c = _config()
    with _client() as http:
        _login(http, c)
        raw = _query(
            http,
            c,
            {
                "FormId": FORM_ORG,
                "FieldKeys": ORG_FIELDS,
                "FilterString": "FForbidStatus='A'",
                "OrderString": "FNumber",
                "TopRowCount": 0,
                "StartRow": 0,
                "Limit": PAGE_SIZE,
            },
        )
    out: list[Org] = []
    seen: set[str] = set()
    for o in raw:
        if isinstance(o, list) and len(o) >= 2:
            number, name = text(o[0]), text(o[1])
            forbid = text(o[2]) if len(o) > 2 else "A"
            if number and name and forbid.upper() == "A" and name not in seen:
                seen.add(name)
                out.append(Org(number, name))
    return out
