"""MES 接口（/api/mes/v1）：② 取下一批、③ 提交回执、④⑤ 只读核对。

- 认证：MES 接口账户每次请求带 Authorization: Bearer <工号>:<密钥>，不换令牌（见 deps.mes_user）。

- 一律 POST + JSON 请求体，地址里不带参数。请求体和应答都支持 gzip，只有 ③ 回执不支持（报文很小）。
- 字段名英文小驼峰；请求和应答都带 schemaVersion（首版 1.0），不认识的字段忽略。
- 应答信封统一为 {"schemaVersion", "code", "message", "batchNo", "timestamp", ...}：成功为 HTTP 200 且 code = OK；
  出错不返回 200，另带 details。时间一律 UTC+8，格式 2026-10-26 11:47:05.303。
- ②③ 只有 MES 接口账户能调（只限所绑生产组织）；④⑤ 另可由页面账户用登录令牌按授权范围调用。
- 限流：同一生产组织每分钟 SN_MES_PULL_RATE_PER_MINUTE 次（默认 60），超出 429 RATE_LIMITED + Retry-After。
"""

from __future__ import annotations

import gzip
import json

from fastapi import APIRouter, Depends, Request

from app.api.deps import mes_user, run_sync
from app.core.exceptions import MesError
from app.core.security import CurrentUser
from app.services import delivery

router = APIRouter(prefix="/mes/v1", tags=["open"])


async def _json(request: Request, gzip_ok: bool = True) -> dict:
    raw = await request.body()
    if request.headers.get("content-encoding", "").lower() == "gzip":
        if not gzip_ok:
            raise MesError(400, "INVALID_REQUEST", "本接口不支持 gzip，请发原始 JSON")
        try:
            raw = gzip.decompress(raw)
        except OSError:
            raise MesError(400, "INVALID_REQUEST", "请求体不是合法的 gzip") from None
    try:
        body = json.loads(raw.decode("utf-8")) if raw else {}
    except (ValueError, UnicodeDecodeError):
        raise MesError(400, "INVALID_REQUEST", "请求体无法解析") from None
    if not isinstance(body, dict):
        raise MesError(400, "INVALID_REQUEST", "请求体须为 JSON 对象")
    return body


def _bad(field: str, reason: str) -> MesError:
    return MesError(400, "INVALID_REQUEST", f"参数不合法：{field}", details=[{"field": field, "reason": reason}])


def _str(body: dict, field: str, required: bool = False) -> str | None:
    v = body.get(field)
    if v is None or (required and isinstance(v, str) and not v.strip()):
        if required:
            raise _bad(field, "必填")
        return None
    if not isinstance(v, str):
        raise _bad(field, "须为字符串")
    return v


def _int(body: dict, field: str) -> int | None:
    v = body.get(field)
    if v is None:
        return None
    if not isinstance(v, int) or isinstance(v, bool):
        raise _bad(field, "须为整数")
    return v


def _only_mes(cu: CurrentUser) -> None:
    if not cu.is_mes:
        raise MesError(403, "FORBIDDEN", "只有 MES 接口账户能调用")


@router.post(
    "/batches/next",
    summary="② 取下一批：先给范围内最早的未确认批次（推送卡住的、取走后没回执的、转出批次），没有才新建；"
    "带 batchNo 时只读取回该批次",
)
async def next_batch(request: Request, cu: CurrentUser = Depends(mes_user)) -> dict:
    _only_mes(cu)
    body = await _json(request)
    return await run_sync(delivery.next_batch, cu, body.get("pi"), body.get("billNo"), body.get("batchNo"))


@router.post("/receipts", summary="③ 提交回执（或整批拒收）：批次号在请求体；按批次号幂等；不支持 gzip")
async def receipt(request: Request, cu: CurrentUser = Depends(mes_user)) -> dict:
    _only_mes(cu)
    body = await _json(request, gzip_ok=False)
    return await run_sync(delivery.submit_receipt, cu, _str(body, "batchNo", True), body)


@router.post("/sn/delivered", summary="④ 查询已交付给本组织的号（只读，只用于核对，不得据此入库、打标或删除）")
async def delivered(request: Request, cu: CurrentUser = Depends(mes_user)) -> dict:
    body = await _json(request)
    args = (_str(body, "orgCode"), _str(body, "pi"), _str(body, "cursor"), _int(body, "pageSize"))
    return await run_sync(delivery.read_delivered, cu, *args)


@router.post("/batches/items", summary="⑤ 查询某个批次里仍在该批次中的号（只读）")
async def batch_items(request: Request, cu: CurrentUser = Depends(mes_user)) -> dict:
    body = await _json(request)
    args = (_str(body, "batchNo", True), _str(body, "cursor"), _int(body, "pageSize"))
    return await run_sync(delivery.read_batch_items, cu, *args)
