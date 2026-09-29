"""MySQL 仓储原语：连接池 + 事务（REQUIRED / REQUIRES_NEW）。"""

from app.db.session import Db, db, marks

__all__ = ["Db", "db", "marks"]
