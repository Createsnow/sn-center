"""表映射说明：MySQL 8 表结构见 app/db/migration/V1__init.sql（Flyway 兼容）。

请求路径不用 ORM：生成 / 分配 / 领取 / 转厂靠手写 SQL 的 FOR UPDATE 行锁与唯一键保证防重语义。
"""

TABLES = (
    "sn_factory",
    "sn_user",
    "sn_user_factory",
    "sn_rule",
    "sn_rule_version",
    "sn_prd_mo",
    "sn_order_sync_schedule",
    "sn_pi_counter",
    "sn_bill_gen",
    "sn_gen_preview",
    "sn_gen_job",
    "sn_item",
    "sn_batch",
    "sn_transfer",
    "sn_transfer_item",
    "sn_audit",
    "sn_audit_archive",
)
