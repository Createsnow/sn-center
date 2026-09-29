"""表映射说明：MySQL 8 表结构见 app/db/migration/V1__init.sql（Flyway 兼容）。

请求路径不用 ORM：生成 / 分配 / 领取 / 转厂靠手写 SQL 的 FOR UPDATE 行锁与唯一键保证防重语义。
"""

TABLES = (
    "sn_factory",
    "sn_user",
    "sn_rule",
    "sn_rule_version",
    "sn_prd_mo",
    "sn_pi_counter",
    "sn_bill_gen",
    "sn_gen_preview",
    "sn_gen_job",
    "sn_item",
    "sn_key",
    "sn_allocation",
    "sn_acquire_batch",
    "sn_print",
    "sn_transfer",
    "sn_transfer_item",
    "sn_pi_import",
    "sn_audit",
    "sn_audit_archive",
)
