"""MySQL 连接与事务入口。业务代码只从这里取 db 与错误判断工具。"""

from app.db.mysql import (
    Db,
    db,
    error_no,
    is_duplicate,
    is_integrity,
    is_lock_conflict,
    marks,
)

__all__ = ["Db", "db", "error_no", "is_duplicate", "is_integrity", "is_lock_conflict", "marks"]
