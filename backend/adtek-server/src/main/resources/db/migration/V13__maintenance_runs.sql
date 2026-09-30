-- 只供独立维护CLI使用；run_id+输入摘要防止错号重放。DDL中断可恢复，不宣称跨DDL事务。
CREATE TABLE sn_maintenance_run (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 action VARCHAR(32) NOT NULL, request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL, result JSON NULL, error_type VARCHAR(80) NULL,
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 attempts INT NOT NULL DEFAULT 1,
 CONSTRAINT ck_sn_maintenance_state CHECK(state IN ('RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB;
