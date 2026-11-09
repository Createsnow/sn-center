from app.db.migrate import check_tables, migrate
from app.db.session import db


def init_db() -> int:
    """建表 / 升级（Flyway 兼容），再检查建表脚本里的表都在；返回本次执行的迁移脚本数。"""
    applied = migrate(db)
    check_tables(db)
    return applied


__all__ = ["db", "init_db", "migrate"]
