from app.db.migrate import migrate
from app.db.session import db


def init_db() -> int:
    """建表 / 升级（Flyway 兼容），返回本次执行的迁移脚本数。"""
    return migrate(db)


__all__ = ["db", "init_db", "migrate"]
