"""仓储层：唯一允许发 SQL 的入口。生成 / 分配 / 领取 / 转厂的事务 SQL 集中在 services 内，避免拆事务改语义。"""

from app.db.session import db, marks

__all__ = ["db", "marks"]
