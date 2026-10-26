"""MES 接口（/api/mes/v1）：③ 取下一批、④ 提交回执、⑤⑥ 只读核对。

- 认证：MES 接口账户每次请求带 Authorization: Bearer <工号>:<密钥>，不换令牌（见 deps.mes_user）。

- 请求和应答都支持 gzip；字段名英文小驼峰；请求和应答都带 schemaVersion（首版 1.0），不认识的字段忽略。
- 应答信封统一为 {"schemaVersion", "code", "message", "batchNo", "timestamp", ...}：成功为 HTTP 200 且 code = OK；
  出错不返回 200，另带 details。时间一律 UTC+8，格式 2026-10-26 11:47:05.303。
- ③④ 只有 MES 接口账户能调（只限所绑生产组织）；⑤⑥ 另可由有可看范围的账户（查询员等）调用。
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


async def _json(request: Request) -> dict:
    raw = await request.body()
    if request.headers.get("content-encoding", "").lower() == "gzip":
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


def _only_mes(cu: CurrentUser) -> None:
    if not cu.is_mes:
        raise MesError(403, "FORBIDDEN", "只有 MES 接口账户能调用")


@router.post(
    "/batches/next",
    summary="③ 取下一批：先给范围内最早的未确认批次（推送卡住的、取走后没回执的、转出批次），没有才新建；"
    "带 batchNo 时只读取回该批次",
)
async def next_batch(request: Request, cu: CurrentUser = Depends(mes_user)) -> dict:
    _only_mes(cu)
    body = await _json(request)
    return await run_sync(delivery.next_batch, cu, body.get("pi"), body.get("billNo"), body.get("batchNo"))


@router.post("/batches/{batch_no}/receipt", summary="④ 提交回执（或整批拒收）；按批次号幂等")
async def receipt(batch_no: str, request: Request, cu: CurrentUser = Depends(mes_user)) -> dict:
    _only_mes(cu)
    body = await _json(request)
    return await run_sync(delivery.submit_receipt, cu, batch_no, body)


@router.get("/sn/delivered", summary="⑤ 查询已交付给本组织的号（只读，只用于核对，不得据此入库、打标或删除）")
def delivered(
    cu: CurrentUser = Depends(mes_user),
    pi: str | None = None,
    orgCode: str | None = None,  # noqa: N803
    cursor: str | None = None,
    pageSize: int | None = None,  # noqa: N803
) -> dict:
    return delivery.read_delivered(cu, orgCode, pi, cursor, pageSize)


@router.get("/batches/{batch_no}/items", summary="⑥ 查询某个批次里仍在该批次中的号（只读）")
def batch_items(
    batch_no: str,
    cu: CurrentUser = Depends(mes_user),
    cursor: str | None = None,
    pageSize: int | None = None,  # noqa: N803
) -> dict:
    return delivery.read_batch_items(cu, batch_no, cursor, pageSize)
