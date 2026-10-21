"""表映射说明：MySQL 8 表结构见 app/db/migration/V1__init.sql（Flyway 兼容）。

请求路径不用 ORM：生成 / 分配 / 推送与拉取 / 转厂靠手写 SQL 的 FOR UPDATE 行锁与唯一键保证防重语义。
"""
