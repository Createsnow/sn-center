"""小工具：状态 / 角色常量、字符串、业务时间、单号、分页、JSON 取值。"""

from __future__ import annotations

import secrets
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Any

from app.core.config import settings

# ---------------------------------------------------------------- SN 状态 / 角色

PENDING_ALLOC = "PENDING_ALLOC"
TO_ACQUIRE = "TO_ACQUIRE"
TO_PRINT = "TO_PRINT"
PRINTED = "PRINTED"
APPLYING = "APPLYING"
SN_STATUSES = (PENDING_ALLOC, TO_ACQUIRE, TO_PRINT, PRINTED, APPLYING)
#: 可以转厂的状态
TRANSFERABLE = frozenset((TO_ACQUIRE, TO_PRINT, PRINTED))

ADMIN = "admin"
FACTORY = "factory_operator"
QUERY = "query"
ROLES = frozenset((ADMIN, FACTORY, QUERY))

# ---------------------------------------------------------------- 字符串

#: 编码类字段（PI、SN、工厂、客户、物料）的最大长度，与表结构 VARCHAR(64) 一致
CODE_MAX = 64


def trim(s: str | None) -> str:
    return "" if s is None else s.strip()


def trim_or_none(s: str | None) -> str | None:
    t = trim(s)
    return t or None


def blank(s: str | None) -> bool:
    return s is None or not s.strip()


def like_prefix(s: str) -> str:
    """LIKE 前缀匹配，转义通配符。"""
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


def like_any(s: str) -> str:
    return "%" + like_prefix(s)


def cut(s: str | None, n: int) -> str | None:
    if s is None or len(s) <= n:
        return s
    return s[:n]


# ---------------------------------------------------------------- 时间

TS = "%Y-%m-%d %H:%M:%S"


def now() -> datetime:
    """业务时间：配置时区（TZ，默认 Asia/Shanghai），精确到秒，不带时区。"""
    return datetime.now(settings.zone).replace(tzinfo=None, microsecond=0)


def now_millis() -> datetime:
    t = datetime.now(settings.zone).replace(tzinfo=None)
    return t.replace(microsecond=t.microsecond // 1000 * 1000)


def today() -> date:
    return datetime.now(settings.zone).date()


def month_of(t: datetime) -> int:
    """yyyymm，sn_item 的分区键。"""
    return t.year * 100 + t.month


def fmt(t: datetime | None) -> str | None:
    return None if t is None else t.strftime(TS)


# ---------------------------------------------------------------- 单号


def next_id(prefix: str) -> str:
    """业务单号：前缀 + 秒级时间 + 4 位随机数；唯一键兜底。"""
    return prefix + now().strftime("%Y%m%d%H%M%S") + f"{secrets.randbelow(10_000):04d}"


def token_hex() -> str:
    return secrets.token_hex(16)


# ---------------------------------------------------------------- 分页

MAX_PAGE_SIZE = 5000


@dataclass(frozen=True)
class Page:
    """页码从 1 开始；每页 1–5000 条。"""

    page: int
    size: int

    @staticmethod
    def of(page: int | None, size: int | None) -> Page:
        p = 1 if page is None or page < 1 else page
        s = 20 if size is None or size < 1 else min(size, MAX_PAGE_SIZE)
        return Page(p, s)

    @property
    def offset(self) -> int:
        return (self.page - 1) * self.size


def page_result(items: list, total: int, q: Page) -> dict:
    return {"items": items, "total": total, "page": q.page, "page_size": q.size, "total_capped": False}


def page_capped(items: list, total: int, q: Page, cap: int) -> dict:
    """total_capped = true 时 total 只是下限（大表计数封顶，避免全表 COUNT）。"""
    return {
        "items": items,
        "total": min(total, cap),
        "page": q.page,
        "page_size": q.size,
        "total_capped": total > cap,
    }


# ---------------------------------------------------------------- JSON


def jv(v: Any) -> Any:
    """库里取出的值 → 接口 JSON 值：时间 yyyy-MM-dd HH:mm:ss，DECIMAL → 数字。"""
    if isinstance(v, datetime):
        return v.strftime(TS)
    if isinstance(v, date):
        return v.isoformat()
    if isinstance(v, Decimal):
        return int(v) if v == v.to_integral_value() else float(v)
    return v


def jrow(row: dict | None, fields: tuple[str, ...], bools: tuple[str, ...] = ()) -> dict | None:
    """按实体字段顺序取列；TINYINT(1) 布尔列转 true / false。"""
    if row is None:
        return None
    out = {}
    for f in fields:
        v = row.get(f)
        out[f] = (None if v is None else bool(v)) if f in bools else jv(v)
    return out
