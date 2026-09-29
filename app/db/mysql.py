"""MySQL 访问：连接池 + 事务。

事务语义对齐 Spring：``db.tx()`` 已在事务中则加入（REQUIRED），否则新开；``db.new_tx()`` 总是用独立连接
新开一个事务（REQUIRES_NEW，失败痕迹、生成进度用）。当前事务的连接放在 contextvar 里，
同一请求 / 同一后台线程里的查询自动走这条连接。
"""

from __future__ import annotations

import contextvars
import queue
import threading
from collections.abc import Iterator, Sequence
from contextlib import contextmanager, suppress
from typing import Any

import pymysql
from pymysql.cursors import DictCursor

from app.core.config import DbTarget

_current: contextvars.ContextVar[pymysql.connections.Connection | None] = contextvars.ContextVar(
    "sn_db_conn", default=None
)

#: MySQL 错误号
ER_DUP_ENTRY = 1062
ER_LOCK_WAIT_TIMEOUT = 1205
ER_LOCK_DEADLOCK = 1213


def error_no(e: BaseException) -> int | None:
    args = getattr(e, "args", ())
    return args[0] if args and isinstance(args[0], int) else None


def is_duplicate(e: BaseException) -> bool:
    return isinstance(e, pymysql.err.IntegrityError) and error_no(e) == ER_DUP_ENTRY


def is_lock_conflict(e: BaseException) -> bool:
    return isinstance(e, pymysql.err.OperationalError) and error_no(e) in (ER_LOCK_WAIT_TIMEOUT, ER_LOCK_DEADLOCK)


def is_integrity(e: BaseException) -> bool:
    """对应 Spring 的 DataIntegrityViolationException：约束冲突、数据超长等。"""
    return isinstance(e, (pymysql.err.IntegrityError, pymysql.err.DataError))


def marks(n: int) -> str:
    return ",".join(["%s"] * n)


class Db:
    def __init__(self) -> None:
        self._target: DbTarget | None = None
        self._pool: queue.LifoQueue = queue.LifoQueue()
        self._size = 20
        self._created = 0
        self._lock = threading.Lock()
        self._slots: threading.BoundedSemaphore | None = None

    # ------------------------------------------------------------------ 连接

    def configure(self, target: DbTarget, size: int) -> None:
        self.close()
        self._target = target
        self._size = size
        self._slots = threading.BoundedSemaphore(size)

    @property
    def target(self) -> DbTarget:
        if self._target is None:
            raise RuntimeError("database not configured")
        return self._target

    def connect_raw(self, database: str | None = "") -> pymysql.connections.Connection:
        t = self.target
        return pymysql.connect(
            host=t.host,
            port=t.port,
            user=t.user,
            password=t.password,
            database=t.database if database == "" else database,
            charset="utf8mb4",
            autocommit=True,
            cursorclass=DictCursor,
            connect_timeout=30,
        )

    def _checkout(self) -> pymysql.connections.Connection:
        assert self._slots is not None, "database not configured"
        if not self._slots.acquire(timeout=30):
            raise pymysql.err.OperationalError(ER_LOCK_WAIT_TIMEOUT, "connection pool exhausted")
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
            self._slots.release()
            raise

    def _release(self, conn: pymysql.connections.Connection, broken: bool = False) -> None:
        try:
            if broken or not conn.open:
                self._discard(conn)
            else:
                self._pool.put(conn)
        finally:
            assert self._slots is not None
            self._slots.release()

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
        conn = self._checkout()
        broken = False
        try:
            yield conn
        except pymysql.err.OperationalError:
            broken = True
            raise
        finally:
            self._release(conn, broken)

    # ------------------------------------------------------------------ 事务

    @contextmanager
    def tx(self) -> Iterator[pymysql.connections.Connection]:
        cur = _current.get()
        if cur is not None:
            yield cur
            return
        with self._begin() as conn:
            yield conn

    @contextmanager
    def new_tx(self) -> Iterator[pymysql.connections.Connection]:
        with self._begin() as conn:
            yield conn

    @contextmanager
    def _begin(self) -> Iterator[pymysql.connections.Connection]:
        conn = self._checkout()
        token = _current.set(conn)
        broken = False
        try:
            conn.begin()
            try:
                yield conn
            except BaseException:
                try:
                    conn.rollback()
                except pymysql.err.Error:
                    broken = True
                raise
            conn.commit()
        except pymysql.err.OperationalError:
            broken = True
            raise
        finally:
            _current.reset(token)
            self._release(conn, broken)

    @staticmethod
    def in_tx() -> bool:
        return _current.get() is not None

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
