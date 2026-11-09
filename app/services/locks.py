"""跨实例互斥：MySQL 命名锁（GET_LOCK），每把锁占一条独立连接，持锁连接断开时服务端自动释放。

- PI 操作锁（5.2.9 第 1 条）：同一 PI 的生成（含取消）、撤销生成、分配、转单、转组织确认（含直接转移）
  同一时刻只执行一个，后到的立即提示稍后再试（不排队）。后台生成把锁交给后台线程，结束时释放。
- 组织建批锁（F-12 取批规则第 6 条）：MES 取批时查未确认批次与建批在同一把锁内，并发取批不会各建一批。
- 订单同步锁（F-05 同步方式第 6 条）：各种同步共用；按单据同步、全量重建最多排队 30 秒，比对同步和进页同步直接跳过。
- 前缀锁（规则前缀不能被两张 PI 同时占用）：判断前缀能否用到事务结束，同一前缀的判断、占用串行；
  见 rules.check_prefix。
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterator
from contextlib import contextmanager, suppress

import pymysql

from app.core.errors import ErrorCode, biz
from app.db.session import db


def _name(kind: str, key: str) -> str:
    # 命名锁最长 64 字符：键做摘要
    return f"sn_{kind}_" + hashlib.md5(key.encode("utf-8")).hexdigest()


class Held:
    """已持有的一组命名锁（一条独立连接）；release 可重复调用。"""

    def __init__(self, conn: pymysql.connections.Connection, names: list[str]) -> None:
        self._conn = conn
        self._names = names
        self._released = False

    def release(self) -> None:
        if self._released:
            return
        self._released = True
        try:
            with self._conn.cursor() as c:
                for n in reversed(self._names):
                    c.execute("SELECT RELEASE_LOCK(%s)", (n,))
        except pymysql.err.Error:
            pass
        finally:
            with suppress(Exception):
                self._conn.close()


def try_acquire(kind: str, keys: list[str], timeout: int = 0) -> Held | None:
    """按键排序依次加锁（避免死锁）；任一把拿不到则全部释放并返回 None。"""
    names = [_name(kind, k) for k in sorted(set(keys))]
    conn = db.connect_raw()
    got: list[str] = []
    try:
        with conn.cursor() as c:
            for n in names:
                c.execute("SELECT GET_LOCK(%s, %s) AS ok", (n, timeout))
                if (c.fetchone() or {}).get("ok") != 1:
                    Held(conn, got).release()
                    return None
                got.append(n)
    except BaseException:
        Held(conn, got).release()
        raise
    return Held(conn, got)


def pi_lock(*pis: str) -> Held:
    """拿 PI 操作锁；拿不到抛 PI_BUSY。调用方负责 release（或用 with_pi_lock）。"""
    keys = [p for p in pis if p]
    h = try_acquire("pi", keys)
    if h is None:
        raise biz(ErrorCode.PI_BUSY, pi="、".join(sorted(set(keys))))
    return h


@contextmanager
def with_pi_lock(*pis: str) -> Iterator[None]:
    h = pi_lock(*pis)
    try:
        yield
    finally:
        h.release()


@contextmanager
def with_org_lock(org: str, timeout: int = 10) -> Iterator[None]:
    h = try_acquire("org", [org], timeout)
    if h is None:
        raise biz(ErrorCode.CONFLICT_RETRY)
    try:
        yield
    finally:
        h.release()


#: 前缀锁最长等待秒数（同一前缀正被另一笔生成 / 规则保存占用时，等它提交）
PREFIX_WAIT_SECONDS = 30


def hold_prefix(prefix: str) -> None:
    """前缀锁：同一前缀同一时刻只有一笔事务在判断、占用（新建 / 首次归属版本）；锁到当前事务结束。
    不在事务里时不加（只判断、不写，没有要保护的）。等不到报稍后重试。"""
    if not db.in_tx():
        return
    h = try_acquire("prefix", [prefix], PREFIX_WAIT_SECONDS)
    if h is None:
        raise biz(ErrorCode.CONFLICT_RETRY)
    db.after_tx(h.release)


#: 按单据同步、全量重建等同步锁的最长秒数：须明显短于页面请求超时（120 秒），否则页面已报超时、后台仍排着队，
#: 拿到锁后照样执行；排队期间每个请求还占一个工作线程（与 MES 接口共用）和一条数据库连接
SYNC_WAIT_SECONDS = 30


def sync_running() -> bool:
    """订单同步锁是否有人持有（任一实例）。"""
    return db.scalar("SELECT IS_USED_LOCK(%s)", _name("sync", "orders")) is not None


def sync_lock(wait: bool) -> Held | None:
    """订单同步锁：wait = True 时最多等 SYNC_WAIT_SECONDS（按单据同步、全量重建排队），否则拿不到返回 None。"""
    return try_acquire("sync", ["orders"], SYNC_WAIT_SECONDS if wait else 0)
