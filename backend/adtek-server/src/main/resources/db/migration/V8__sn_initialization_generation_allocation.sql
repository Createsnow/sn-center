-- PI水位与活跃意图由MySQL持久控制；Redis不参与唯一性和最终状态。
CREATE TABLE sn_pi_counter (
 pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL PRIMARY KEY,
 last_seq BIGINT NOT NULL DEFAULT 0, generated_qty BIGINT NOT NULL DEFAULT 0, imported_qty BIGINT NOT NULL DEFAULT 0,
 start_seq BIGINT NULL, start_locked BOOLEAN NOT NULL DEFAULT FALSE, locked_by BIGINT NULL, locked_at DATETIME(6) NULL,
 active_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 CONSTRAINT ck_sn_pi_counter CHECK(last_seq>=0 AND generated_qty>=0 AND imported_qty>=0 AND (start_seq IS NULL OR start_seq>0))
) ENGINE=InnoDB;

CREATE TABLE sn_preview (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, actor_id BIGINT NOT NULL,
 pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, bill_id BIGINT NOT NULL,
 payload JSON NOT NULL, expires_at DATETIME(6) NOT NULL,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX ix_sn_preview_expiry(expires_at)
) ENGINE=InnoDB COMMENT='纯预演固定条件；绑定后不因失败恢复为可用';

CREATE TABLE sn_work (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 kind VARCHAR(16) NOT NULL, pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 bill_id BIGINT NULL, factory_id BIGINT NULL, actor_id BIGINT NOT NULL,
 payload JSON NOT NULL COMMENT '服务器校验后的固定输入，不接收客户端SQL或文件路径',
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', quantity BIGINT NOT NULL,
 start_seq BIGINT NULL, end_seq BIGINT NULL, decoded_qty BIGINT NULL, max_seq BIGINT NULL, last_seq BIGINT NULL,
 first_sn VARCHAR(64) COLLATE utf8mb4_0900_bin NULL, last_sn VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
 file_name VARCHAR(255) NOT NULL DEFAULT '', error_code INT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 INDEX ix_sn_work_pi(pi,created_at,task_id), INDEX ix_sn_work_bill(bill_id,created_at,task_id),
 CONSTRAINT ck_sn_work_kind CHECK(kind IN ('PI_INIT','GENERATE','ALLOCATE')),
 CONSTRAINT ck_sn_work_state CHECK(state IN ('PENDING','SUCCESS','FAILED','CANCELLED')),
 CONSTRAINT ck_sn_work_qty CHECK(quantity>=0)
) ENGINE=InnoDB;

CREATE TABLE sn_item (
 gen_month DATE NOT NULL, id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sn VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL, pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 seq_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NULL, seq_dec BIGINT NULL,
 customer VARCHAR(64) NOT NULL, material VARCHAR(128) NOT NULL, factory_id BIGINT NULL,
 source VARCHAR(8) NOT NULL, status VARCHAR(24) NOT NULL, rule_version_id BIGINT NULL,
 source_system VARCHAR(16) NULL, source_account VARCHAR(64) NULL, source_bill_id VARCHAR(64) NULL, source_entry_id VARCHAR(64) NULL,
 bill_id BIGINT NULL, bill_no VARCHAR(64) NOT NULL, source_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 source_org_id VARCHAR(64) NULL, source_factory_id BIGINT NULL,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 allocation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), printed_at DATETIME(6) NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(gen_month,id), INDEX ix_sn_item_pending(bill_id,status,seq_dec,id), INDEX ix_sn_item_scope(factory_id,pi,status),
 CONSTRAINT ck_sn_item_seq CHECK((seq_dec IS NULL AND seq_pi IS NULL) OR (seq_dec>=0 AND seq_pi IS NOT NULL)),
 CONSTRAINT ck_sn_item_source CHECK(source IN ('GEN','IMPORT'))
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(gen_month) (PARTITION p_history VALUES LESS THAN ('2026-10-01'),PARTITION p_future VALUES LESS THAN (MAXVALUE));

-- 不分区护栏定位到真实分区成员；当前PI与流水归属PI分别唯一，不能用客户或状态削弱。
CREATE TABLE sn_key (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, gen_month DATE NOT NULL,
 pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, sn VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
 seq_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NULL, seq_dec BIGINT NULL,
 UNIQUE KEY uk_sn_full(pi,sn), UNIQUE KEY uk_sn_sequence(seq_pi,seq_dec),
 CONSTRAINT ck_sn_key_seq CHECK((seq_dec IS NULL AND seq_pi IS NULL) OR (seq_dec>=0 AND seq_pi IS NOT NULL))
) ENGINE=InnoDB;

INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,permission_code,route_path,component_key,sort_order) VALUES
 (240,200,'C','menu.piInit','sn:pi-init:view','/pi-init','sn.pi-init',40),
 (241,240,'F','permission.piInit','sn:pi-init:execute',NULL,NULL,0),
 (250,200,'C','menu.generate','sn:generation:view','/generate','sn.generate',50),
 (251,250,'F','permission.generationPreview','sn:generation:preview',NULL,NULL,0),
 (252,250,'F','permission.generate','sn:generation:execute',NULL,NULL,0),
 (253,250,'F','permission.allocate','sn:allocation:execute',NULL,NULL,0);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 2,id FROM sys_menu WHERE id BETWEEN 240 AND 253;
UPDATE sys_user SET security_version=security_version+1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id=2);
