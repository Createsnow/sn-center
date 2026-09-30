-- 旧IMPORT库存没有可证明的新系统任务；显式关联迁移运行，不能编造成功业务任务或操作者。
ALTER TABLE sn_item MODIFY task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD legacy_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD CONSTRAINT ck_sn_item_origin CHECK(
   (task_id IS NOT NULL AND legacy_run_id IS NULL) OR
   (task_id IS NULL AND legacy_run_id IS NOT NULL AND source='IMPORT'));
CREATE TABLE sn_legacy_inventory_run (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 mapping_version VARCHAR(64) NOT NULL,
 source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 master_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 plan_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB COMMENT='已核验IMPORT库存/水位证明，未完成历史转换时仍不能启用账号';
