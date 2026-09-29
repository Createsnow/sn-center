"""请求体基类与查询参数类型。"""

from __future__ import annotations

from datetime import date
from typing import Annotated, Any

from pydantic import BaseModel, BeforeValidator, ConfigDict


class In(BaseModel):
    """请求体：snake_case；多余字段忽略；数字可写成字符串、字符串字段收到数字也接受。"""

    model_config = ConfigDict(extra="ignore", coerce_numbers_to_str=True)


def _empty_none(v: Any) -> Any:
    """查询参数空串视为未传。"""
    return None if isinstance(v, str) and not v.strip() else v


OptInt = Annotated[int | None, BeforeValidator(_empty_none)]
OptDate = Annotated[date | None, BeforeValidator(_empty_none)]
Flag = Annotated[bool | None, BeforeValidator(_empty_none)]


def blank_to_none(s: str | None) -> str | None:
    return None if s is None or not s.strip() else s.strip()
