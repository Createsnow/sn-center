"""MySQL 访问：连接池 + 事务。

事务语义对齐 Spring：``db.tx()`` 已在事务中则加入（REQUIRED），否则新开；``db.new_tx()`` 总是用独立连接
新开一个事务（REQUIRES_NEW，失败痕迹、生成进度用）。当前事务的连接放在 contextvar 里，
同一请求 / 同一后台线程里的查询自动走这条连接。

连接池分两份名额：最外层取连接（请求、后台任务的第一条连接）占主名额（DB_POOL_SIZE）；已经拿着一条连接、
又要另一条的（事务里写失败痕迹、生成进度、取消检查等 ``new_tx``）占备用名额（同样 DB_POOL_SIZE 条）。
否则并发一高，主名额被「拿着一条、等第二条」的请求占满，谁也等不到第二条，要等满 30 秒取连接超时才散开
（100 户同时生成时中位耗时 32 秒即此）。所以一个实例最多同时占 2 × DB_POOL_SIZE 条池内连接，
另加命名锁（PI 操作锁等）各占的一条独立连接。
"""

from __future__ import annotations

import contextvars
import queue
import random
import threading
import time
from collections.abc import Callable, Iterator, Sequence
from contextlib import contextmanager, suppress
from typing import Any

import pymysql
from pymysql.cursors import DictCursor

from app.core.config import DbTarget

_current: contextvars.ContextVar[pymysql.connections.Connection | None] = contextvars.ContextVar(
    "sn_db_conn", default=None
)
#: 当前上下文已从池里拿着几条连接（> 0 时再取连接用备用名额）
_depth: contextvars.ContextVar[int] = contextvars.ContextVar("sn_db_depth", default=0)
#: 当前事务结束（提交或回滚）后要做的事（释放跟事务走的命名锁）
_after: contextvars.ContextVar[list[Callable[[], None]] | None] = contextvars.ContextVar("sn_db_after", default=None)
#: 当前事务提交前、在事务里要做的事（单据汇总标脏，见 services/bill_stats）：抛错则整笔回滚
_before: contextvars.ContextVar[list[Callable[[], None]] | None] = contextvars.ContextVar("sn_db_before", default=None)

#: MySQL 错误号
ER_DUP_ENTRY = 1062
ER_LOCK_WAIT_TIMEOUT = 1205
ER_LOCK_DEADLOCK = 1213
#: FOR UPDATE NOWAIT 拿不到锁
ER_LOCK_NOWAIT = 3572
#: 客户端错误号（CR_*，断线、读写超时等连接层故障）占 2000–2999；其余是服务端错误，连接仍可用
CR_ERRORS = range(2000, 3000)

#: 连接池满时等空闲连接的上限
CHECKOUT_TIMEOUT_SECONDS = 30.0

#: 死锁（1213）时整段事务自动重做的次数（含第一次）：死锁时 MySQL 已整笔回滚，按原样再做一遍即可
DEADLOCK_ATTEMPTS = 3


class PoolExhausted(pymysql.err.OperationalError):
    """等不到空闲连接。沿用锁等待超时的错误号，调用方仍按「稍后重试」处理。"""

    def __init__(self) -> None:
        super().__init__(ER_LOCK_WAIT_TIMEOUT, "connection pool exhausted")


def error_no(e: BaseException) -> int | None:
    args = getattr(e, "args", ())
    return args[0] if args and isinstance(args[0], int) else None


def is_duplicate(e: BaseException) -> bool:
    return isinstance(e, pymysql.err.IntegrityError) and error_no(e) == ER_DUP_ENTRY


def is_lock_conflict(e: BaseException) -> bool:
    return isinstance(e, pymysql.err.OperationalError) and error_no(e) in (ER_LOCK_WAIT_TIMEOUT, ER_LOCK_DEADLOCK)


def is_deadlock(e: BaseException) -> bool:
    return isinstance(e, pymysql.err.OperationalError) and error_no(e) == ER_LOCK_DEADLOCK


def retry_deadlock[T](fn: Callable[[], T], attempts: int = DEADLOCK_ATTEMPTS, independent: bool = False) -> T:
    """执行 fn（其中自己开、自己提交事务），遇死锁（1213）稍等后整段重做，最多 attempts 次。

    默认只在没有外层事务时重做：死锁回滚的是整笔事务，外层事务里的重做没有意义，原样抛给外层。
    independent = True：fn 里是独立事务（new_tx），死锁只回滚它自己，外层事务里也可重做。
    fn 须可整段重做（事务外不留副作用，或副作用可重复）。"""
    for i in range(attempts):
        try:
            return fn()
        except pymysql.err.OperationalError as e:
            if not is_deadlock(e) or i + 1 >= attempts or (not independent and _current.get() is not None):
                raise
            # 错开重做时间，免得两边又同时撞上
            time.sleep(0.05 * (i + 1) + random.random() * 0.2)
    raise AssertionError("unreachable")


def is_connection_lost(e: BaseException) -> bool:
    """连接层故障（断线、读写超时）。死锁、锁等待超时、NOWAIT 冲突是服务端错误，回滚后连接照常可用。"""
    no = error_no(e)
    return isinstance(e, pymysql.err.OperationalError) and (no is None or no in CR_ERRORS)


def is_integrity(e: BaseException) -> bool:
    """对应 Spring 的 DataIntegrityViolationException：约束冲突、数据超长等。"""
    return isinstance(e, (pymysql.err.IntegrityError, pymysql.err.DataError))


def marks(n: int) -> str:
    return ",".join(["%s"] * n)


class Db:
    def __init__(self) -> None:
        self._target: DbTarget | None = None
        self._pool: queue.LifoQueue[pymysql.connections.Connection] = queue.LifoQueue()
        self._slots: threading.BoundedSemaphore | None = None
        self._spare: threading.BoundedSemaphore | None = None

    # ------------------------------------------------------------------ 连接

    def configure(self, target: DbTarget, size: int) -> None:
        self.close()
        self._target = target
        self._slots = threading.BoundedSemaphore(size)
        self._spare = threading.BoundedSemaphore(size)

    @property
    def target(self) -> DbTarget:
        if self._target is None:
            raise RuntimeError("database not configured")
        return self._target

    def connect_raw(self) -> pymysql.connections.Connection:
        t = self.target
        return pymysql.connect(
            host=t.host,
            port=t.port,
            user=t.user,
            password=t.password,
            database=t.database,
            charset="utf8mb4",
            autocommit=True,
            cursorclass=DictCursor,
            connect_timeout=30,
            # GROUP_CONCAT 默认只拼 1024 字节，超出静默截断（物料清单、批次号清单会少）
            init_command="SET SESSION group_concat_max_len = 1048576",
        )

    def _checkout(self, nested: bool = False) -> pymysql.connections.Connection:
        """取一条连接。nested：当前上下文已拿着池内连接（用备用名额，见模块说明）。"""
        slots = self._spare if nested else self._slots
        assert slots is not None, "database not configured"
        if not slots.acquire(timeout=CHECKOUT_TIMEOUT_SECONDS):
            raise PoolExhausted()
        try:
            while True:
                try:
                    conn = self._pool.get_nowait()
                except queue.Empty:
                    return self.connect_raw()
                try:
                    conn.ping(reconnect=False)
                    return conn
                except pymysql.err.Error:
                    self._discard(conn)
        except BaseException:
            slots.release()
            raise

    def _release(self, conn: pymysql.connections.Connection, broken: bool = False, nested: bool = False) -> None:
        try:
            if broken or not conn.open:
                self._discard(conn)
            else:
                self._pool.put(conn)
        finally:
            slots = self._spare if nested else self._slots
            assert slots is not None
            slots.release()

    @staticmethod
    def _discard(conn: pymysql.connections.Connection) -> None:
        with suppress(Exception):
            conn.close()

    def close(self) -> None:
        while True:
            try:
                self._discard(self._pool.get_nowait())
            except queue.Empty:
                break

    @contextmanager
    def connection(self) -> Iterator[pymysql.connections.Connection]:
        """一条独立的自动提交连接（分区 DDL、命名锁）。"""
        depth = _depth.get()
        conn = self._checkout(depth > 0)
        token = _depth.set(depth + 1)
        broken = False
        try:
            yield conn
        except pymysql.err.OperationalError as e:
            broken = is_connection_lost(e)
            raise
        finally:
            _depth.reset(token)
            self._release(conn, broken, depth > 0)

    # ------------------------------------------------------------------ 事务

    @contextmanager
    def tx(self) -> Iterator[pymysql.connections.Connection]:
        cur = _current.get()
        if cur is not None:
            yield cur
            return
        with self._begin() as conn:
            yield conn

    @staticmethod
    def in_tx() -> bool:
        return _current.get() is not None

    @staticmethod
    def after_tx(fn: Callable[[], None]) -> None:
        """当前事务结束（提交或回滚）后执行 fn；不在事务里时马上执行。"""
        pending = _after.get()
        if pending is None:
            fn()
        else:
            pending.append(fn)

    @staticmethod
    def before_commit(fn: Callable[[], None]) -> bool:
        """当前事务提交前（仍在事务里）执行 fn，返回 True；不在事务里时不登记、返回 False（由调用方马上执行）。
        同一事务登记多次的由调用方自行合并。"""
        pending = _before.get()
        if pending is None:
            return False
        pending.append(fn)
        return True

    @contextmanager
    def new_tx(self) -> Iterator[pymysql.connections.Connection]:
        with self._begin() as conn:
            yield conn

    @contextmanager
    def _begin(self) -> Iterator[pymysql.connections.Connection]:
        depth = _depth.get()
        conn = self._checkout(depth > 0)
        token = _current.set(conn)
        dtoken = _depth.set(depth + 1)
        atoken = _after.set([])
        btoken = _before.set([])
        broken = False
        try:
            conn.begin()
            try:
                yield conn
                for fn in _before.get() or []:
                    fn()
            except BaseException:
                try:
                    conn.rollback()
                except pymysql.err.Error:
                    broken = True
                raise
            conn.commit()
        except pymysql.err.OperationalError as e:
            broken = broken or is_connection_lost(e)
            raise
        finally:
            pending = _after.get() or []
            _before.reset(btoken)
            _after.reset(atoken)
            _depth.reset(dtoken)
            _current.reset(token)
            self._release(conn, broken, depth > 0)
            for fn in pending:
                with suppress(Exception):
                    fn()

    # ------------------------------------------------------------------ 执行

    @contextmanager
    def _conn(self) -> Iterator[pymysql.connections.Connection]:
        cur = _current.get()
        if cur is not None:
            yield cur
            return
        with self.connection() as conn:
            yield conn

    def all(self, sql: str, *args: Any) -> list[dict]:
        with self._conn() as conn, conn.cursor() as c:
            c.execute(sql, args or None)
            return list(c.fetchall())

    def one(self, sql: str, *args: Any) -> dict | None:
        rows = self.all(sql, *args)
        return rows[0] if rows else None

    def scalar(self, sql: str, *args: Any) -> Any:
        row = self.one(sql, *args)
        return None if row is None else next(iter(row.values()))

    def count(self, sql: str, *args: Any) -> int:
        v = self.scalar(sql, *args)
        return 0 if v is None else int(v)

    def exists(self, sql: str, *args: Any) -> bool:
        return bool(self.scalar(sql, *args))

    def column(self, sql: str, *args: Any) -> list:
        return [next(iter(r.values())) for r in self.all(sql, *args)]

    def exec(self, sql: str, *args: Any) -> int:
        with self._conn() as conn, conn.cursor() as c:
            return c.execute(sql, args or None)

    def insert(self, sql: str, *args: Any) -> int:
        """INSERT，返回自增主键。"""
        with self._conn() as conn, conn.cursor() as c:
            c.execute(sql, args or None)
            return c.lastrowid

    def exec_many(self, sql: str, rows: Sequence[Sequence[Any]]) -> None:
        """批量写：VALUES 全用占位符时 PyMySQL 会合并成多行 INSERT。"""
        if not rows:
            return
        with self._conn() as conn, conn.cursor() as c:
            c.executemany(sql, rows)


db = Db()
