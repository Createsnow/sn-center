-- 旧创建者缺失时保留NULL，原字符串完整留在sn_legacy_row，不创建虚假用户或把迁移账号写成历史操作者。
ALTER TABLE sn_rule MODIFY created_by BIGINT NULL;
ALTER TABLE sn_rule_version MODIFY created_by BIGINT NULL;
CREATE TABLE sn_legacy_master_run (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 mapping_version VARCHAR(64) NOT NULL,
 source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 plan_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB COMMENT='主数据及全部停用身份原子导入证明，不等同SN/历史全量迁移或可启用证明';
