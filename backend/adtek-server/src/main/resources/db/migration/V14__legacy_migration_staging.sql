-- S7离线迁移证据与断点；不进入Web映射器，不覆盖任何既有身份、SN或历史业务表。
CREATE TABLE sn_legacy_migration_run (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 manifest_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 mapping_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_schema VARCHAR(64) NOT NULL, source_server_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_zone VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
 source_manifest JSON NOT NULL COMMENT '仅结构/摘要/问题类别，不含原行口令哈希',
 result JSON NULL, error_type VARCHAR(80) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_legacy_run_state CHECK(state IN ('STAGING','STAGED','FAILED','PROMOTING','SUCCEEDED'))
) ENGINE=InnoDB;
CREATE TABLE sn_legacy_migration_table (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 table_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_rows BIGINT NOT NULL, last_ordinal BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(12) NOT NULL DEFAULT 'PENDING',
 PRIMARY KEY(run_id,table_name),
 CONSTRAINT ck_legacy_table_state CHECK(state IN ('PENDING','STAGING','STAGED')),
 CONSTRAINT ck_legacy_checkpoint CHECK(last_ordinal>=0 AND last_ordinal<=source_rows)
) ENGINE=InnoDB;
CREATE TABLE sn_legacy_row (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 table_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ordinal BIGINT NOT NULL,
 row_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '旧复合主键JSON的SHA256，不以显示序号重建身份',
 payload JSON NOT NULL COMMENT '完整原始字段，可能含旧口令哈希；只供受控离线迁移进程',
 payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(run_id,table_name,ordinal), UNIQUE KEY uk_legacy_source_key(run_id,table_name,row_key),
 CONSTRAINT ck_legacy_ordinal CHECK(ordinal>0)
) ENGINE=InnoDB;
CREATE TABLE sn_legacy_id_map (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_key JSON NOT NULL, evidence_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(run_id,source_table,source_key,target_table)
) ENGINE=InnoDB;
