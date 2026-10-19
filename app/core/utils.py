"""小工具：状态 / 角色常量、字符串、业务时间、单号、分页、JSON 取值。"""

from __future__ import annotations

import secrets
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import Any

from app.core.config import settings

# ---------------------------------------------------------------- SN 状态 / 角色

PENDING_ALLOC = "PENDING_ALLOC"
TO_PUSH = "TO_PUSH"
PUSHED = "PUSHED"
TO_ACQUIRE = "TO_ACQUIRE"
ACQUIRED = "ACQUIRED"
APPLYING = "APPLYING"
SN_STATUSES = (PENDING_ALLOC, TO_PUSH, PUSHED, TO_ACQUIRE, ACQUIRED, APPLYING)
#: 未交付（暂停交付只作用于这些状态；TO_PUSH 另须未入批）
UNDELIVERED = (PENDING_ALLOC, TO_PUSH, TO_ACQUIRE)
#: 交付方式
MODE_PUSH = "PUSH"
MODE_PULL = "PULL"
MODE_PAGE = "PAGE"
#: 推送类型
PUSH_NEW = "NEW"
PUSH_REASSIGN = "REASSIGN"
PUSH_TRANSFER_OUT = "TRANSFER_OUT"
#: 暂停交付
SUSPEND_REVERSAL = "REVERSAL"
SUSPEND_CHANGE = "CHANGE"

ADMIN = "admin"
FACTORY = "factory_operator"
QUERY = "query"
MES = "mes"
#: 页面角色
PAGE_ROLES = frozenset((ADMIN, FACTORY, QUERY))
ROLES = frozenset((ADMIN, FACTORY, QUERY, MES))


def is_super_admin(emp_no: str | None, role: str | None) -> bool:
    """内置管理员（工号 = SN_INIT_ADMIN_EMP_NO，默认 admin）：只有它能管理账户（建、改、停用、启用、重置密码）。"""
    return role == ADMIN and emp_no == settings.init_admin_emp_no


# ---------------------------------------------------------------- 字符串

#: 编码类字段（PI、SN、生产组织、客户、物料）的最大长度，与表结构 VARCHAR(64) 一致
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
# 库内一律存 UTC（DATETIME 不带时区）；接口输出 ISO 8601（带 Z），页面按账户时区显示。

TS = "%Y-%m-%dT%H:%M:%SZ"
#: 账户不绑或绑多个生产组织时的显示时区（UTC+8）
DEFAULT_TZ_OFFSET_MIN = 480


def now() -> datetime:
    """当前 UTC 时间，精确到秒，不带时区。"""
    return datetime.now(UTC).replace(tzinfo=None, microsecond=0)


def now_millis() -> datetime:
    t = datetime.now(UTC).replace(tzinfo=None)
    return t.replace(microsecond=t.microsecond // 1000 * 1000)


def local_day_start(offset_min: int, t: datetime | None = None) -> datetime:
    """按给定时区（相对 UTC 的分钟数）的当天 0 点，换算回 UTC。"""
    base = (t or now()) + timedelta(minutes=offset_min)
    return datetime.combine(base.date(), datetime.min.time()) - timedelta(minutes=offset_min)


def today() -> date:
    """业务日（按 TZ，默认 Asia/Shanghai；维护任务、按天汇总用）。"""
    return datetime.now(settings.zone).date()


def month_of(t: datetime) -> int:
    """yyyymm。"""
    return t.year * 100 + t.month


def fmt(t: datetime | None) -> str | None:
    return None if t is None else t.strftime(TS)


def parse_time(s: str | None) -> datetime | None:
    """接口传入的时间：ISO 8601（带 Z 或偏移）或 yyyy-MM-dd HH:mm:ss（按 UTC）→ UTC naive。"""
    if s is None or not s.strip():
        return None
    v = s.strip().replace(" ", "T")
    if v.endswith("Z"):
        v = v[:-1] + "+00:00"
    try:
        t = datetime.fromisoformat(v)
    except ValueError:
        return None
    if t.tzinfo is not None:
        t = t.astimezone(UTC).replace(tzinfo=None)
    return t


# ---------------------------------------------------------------- 单号


def next_id(prefix: str) -> str:
    """业务单号：前缀 + 秒级时间（UTC）+ 4 位随机数；唯一键兜底。"""
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
