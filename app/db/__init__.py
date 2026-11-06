from app.db.migrate import ensure_added_tables, migrate
from app.db.session import db


def init_db() -> int:
    """建表 / 升级（Flyway 兼容），再补建 V1 之后加入的表；返回本次执行的迁移脚本数。"""
    applied = migrate(db)
    ensure_added_tables(db)
    return applied


__all__ = ["db", "init_db", "migrate"]
