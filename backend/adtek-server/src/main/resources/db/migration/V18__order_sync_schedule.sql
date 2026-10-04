-- 定时全量同步独立持久执行权；默认关闭，责任主体只能由已授权的设置用例写入。
ALTER TABLE sn_order_sync DROP CHECK ck_sn_sync_mode;
ALTER TABLE sn_order_sync ADD CONSTRAINT ck_sn_sync_mode CHECK(mode IN ('all','one','scheduled'));

CREATE TABLE sn_order_sync_schedule (
 id TINYINT NOT NULL PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 interval_minutes INT NOT NULL DEFAULT 1440,
 next_run_at DATETIME(6) NULL,
 owner_id BIGINT NULL,
 last_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 last_run_at DATETIME(6) NULL,
 lease_until DATETIME(6) NULL,
 last_state VARCHAR(10) NULL,
 last_error_code INT NULL,
 last_bills BIGINT NULL,
 last_rows BIGINT NULL,
 updated_at DATETIME(6) NULL,
 CONSTRAINT ck_sn_schedule_singleton CHECK(id=1),
 CONSTRAINT ck_sn_schedule_interval CHECK(interval_minutes BETWEEN 10 AND 10080),
 CONSTRAINT ck_sn_schedule_owner CHECK(enabled=FALSE OR (owner_id IS NOT NULL AND next_run_at IS NOT NULL)),
 CONSTRAINT ck_sn_schedule_state CHECK(last_state IS NULL OR last_state IN ('FETCHING','SUCCESS','FAILED'))
) ENGINE=InnoDB;
INSERT INTO sn_order_sync_schedule(id) VALUES(1);
