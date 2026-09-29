"""登录防暴力破解：同一工号在统计窗口内连续失败达到上限后锁定一段时间，锁定期间密码正确也拒绝。

计数放在进程内存（单实例部署；重启即清零）。登录成功清零。窗口与锁定时长均为 SN_LOGIN_LOCK_MINUTES。
"""

from __future__ import annotations

import math
import threading
import time
from dataclasses import dataclass

from app.core.config import settings
from app.core.errors import ErrorCode, biz

#: 记录条数上限：超过时先清掉过期的，仍超过则丢弃最早的，防止用随机工号撑爆内存
MAX_ENTRIES = 10_000


@dataclass
class _Fails:
    count: int
    first: float
    locked_until: float = 0.0


_fails: dict[str, _Fails] = {}
_lock = threading.Lock()


def _window() -> float:
    return settings.login_lock_minutes * 60.0


def check(emp_no: str) -> None:
    """锁定中则抛 LOGIN_LOCKED（剩余分钟向上取整）。"""
    if settings.login_max_failures == 0:
        return
    now = time.monotonic()
    with _lock:
        f = _fails.get(emp_no)
        left = 0.0 if f is None else f.locked_until - now
    if left > 0:
        raise biz(ErrorCode.LOGIN_LOCKED, minutes=max(1, math.ceil(left / 60)))


def fail(emp_no: str) -> bool:
    """记一次失败；返回本次是否触发锁定。"""
    limit = settings.login_max_failures
    if limit == 0:
        return False
    now = time.monotonic()
    window = _window()
    with _lock:
        f = _fails.get(emp_no)
        if f is None or (now - f.first > window and f.locked_until <= now):
            f = _Fails(0, now)
            _fails[emp_no] = f
        f.count += 1
        locked = f.count >= limit and f.locked_until <= now
        if locked:
            f.locked_until = now + window
        if len(_fails) > MAX_ENTRIES:
            _prune(now, window)
        return locked


def success(emp_no: str) -> None:
    with _lock:
        _fails.pop(emp_no, None)


def reset() -> None:
    """清空全部计数（测试用）。"""
    with _lock:
        _fails.clear()


def _prune(now: float, window: float) -> None:
    for k in [k for k, f in _fails.items() if now - f.first > window and f.locked_until <= now]:
        del _fails[k]
    while len(_fails) > MAX_ENTRIES:
        del _fails[next(iter(_fails))]
