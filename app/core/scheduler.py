"""极简定时任务：按 Spring 风格 6 段 cron（秒 分 时 日 月 周）在后台线程里执行。

用于每日分区维护 / 痕迹归档（SN_PARTITION_CRON，默认 0 10 2 * * *）；
另有固定周期的轮询（start_every），用于生产订单定时全量同步是否到期。
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from datetime import datetime, timedelta

import structlog

from app.core.config import settings

log = structlog.get_logger()

_DOW = {"SUN": 0, "MON": 1, "TUE": 2, "WED": 3, "THU": 4, "FRI": 5, "SAT": 6}
_MON = {
    m: i + 1 for i, m in enumerate(("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"))
}


def _field(expr: str, lo: int, hi: int, names: dict[str, int] | None = None) -> set[int]:
    def num(x: str) -> int:
        return names[x] if names and x in names else int(x)

    out: set[int] = set()
    for part in expr.upper().split(","):
        step = 1
        if "/" in part:
            part, s = part.split("/", 1)
            step = int(s)
        if part in ("*", "?"):
            a, b = lo, hi
        elif "-" in part:
            x, y = part.split("-", 1)
            a, b = num(x), num(y)
        else:
            a = num(part)
            b = hi if step > 1 else a
        out.update(range(a, b + 1, step))
    return out


class Cron:
    def __init__(self, expr: str) -> None:
        parts = expr.split()
        if len(parts) == 5:
            parts = ["0"] + parts
        if len(parts) != 6:
            raise ValueError(f"bad cron: {expr}")
        self.sec = _field(parts[0], 0, 59)
        self.min = _field(parts[1], 0, 59)
        self.hour = _field(parts[2], 0, 23)
        self.dom = _field(parts[3], 1, 31)
        self.mon = _field(parts[4], 1, 12, _MON)
        dow = _field(parts[5], 0, 7, _DOW)
        self.dow = {d % 7 for d in dow}
        self.any_dom = parts[3] in ("*", "?")
        self.any_dow = parts[5] in ("*", "?")

    def _day_ok(self, t: datetime) -> bool:
        dom_ok = t.day in self.dom
        dow_ok = (t.isoweekday() % 7) in self.dow
        if self.any_dom:
            return dow_ok
        if self.any_dow:
            return dom_ok
        return dom_ok and dow_ok

    def next_after(self, t: datetime) -> datetime:
        t = t.replace(microsecond=0) + timedelta(seconds=1)
        limit = t + timedelta(days=366 * 2)
        while t < limit:
            if t.month not in self.mon or not self._day_ok(t):
                t = (t + timedelta(days=1)).replace(hour=0, minute=0, second=0)
                continue
            if t.hour not in self.hour:
                t = (t + timedelta(hours=1)).replace(minute=0, second=0)
                continue
            if t.minute not in self.min:
                t = (t + timedelta(minutes=1)).replace(second=0)
                continue
            if t.second not in self.sec:
                t += timedelta(seconds=1)
                continue
            return t
        raise ValueError("cron never fires")


class Job:
    """next_after(now) 给出下次执行时间；每次执行完再算下一次。"""

    def __init__(
        self, next_after: Callable[[datetime], datetime], fn: Callable[[], None], name: str = "sn-scheduler"
    ) -> None:
        self._next_after = next_after
        self._fn = fn
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, name=name, daemon=True)

    def start(self) -> Job:
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            now = datetime.now(settings.zone).replace(tzinfo=None)
            nxt = self._next_after(now)
            if self._stop.wait((nxt - now).total_seconds()):
                return
            try:
                self._fn()
            except Exception:  # noqa: BLE001
                log.exception("scheduled_job_failed")


def start_daily(expr: str, fn: Callable[[], None]) -> Job:
    try:
        cron = Cron(expr)
    except ValueError:
        log.warning("bad_partition_cron", value=expr, fallback="0 10 2 * * *")
        cron = Cron("0 10 2 * * *")
    return Job(cron.next_after, fn).start()


def start_every(seconds: int, fn: Callable[[], None], name: str) -> Job:
    """每隔 seconds 秒执行一次（首次在启动 seconds 秒后）。"""
    return Job(lambda t: t + timedelta(seconds=seconds), fn, name).start()
