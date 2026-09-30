-- 基础设施只存主体引用，不读取或依赖 system 身份表；业务快照由所属模块维护。
CREATE TABLE sys_operation_control (id INT PRIMARY KEY COMMENT '提交容量协调行') ENGINE=InnoDB;
INSERT INTO sys_operation_control VALUES (1);
CREATE TABLE sys_operation (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 principal_type VARCHAR(16) NOT NULL COMMENT '当前仅USER，后续外部主体须新增可信装配',
 principal_id BIGINT NOT NULL,
 action VARCHAR(100) NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 normalization_version INT NOT NULL,
 input_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 scope_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 error_code INT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_operation_intent(principal_type,principal_id,action,operation_id),
 CONSTRAINT ck_operation_state CHECK(state IN ('PENDING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB;
CREATE TABLE sys_task (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT NOT NULL,
 task_type VARCHAR(64) NOT NULL,
 input_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_json JSON NOT NULL COMMENT '规范化输入，不含凭据',
 authorization_version BIGINT NOT NULL COMMENT '提交时版本；执行/重放/下载重新装配当前主体',
 state VARCHAR(16) NOT NULL,
 owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 execution_epoch BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(6) NULL,
 attempt INT NOT NULL DEFAULT 0,
 max_attempts INT NOT NULL,
 deadline DATETIME(6) NOT NULL,
 available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 result_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 error_code INT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_task_poll(state,available_at,lease_until,created_at),
 INDEX ix_task_owner(user_id,state),
 CONSTRAINT ck_task_state CHECK(state IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCESS','FAILED','CANCELLED'))
) ENGINE=InnoDB;
-- 无父行外键：独立进度事务不能等待执行事务持有的task控制行；进度不是业务提交事实。
CREATE TABLE sys_task_progress (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 execution_epoch BIGINT NOT NULL,
 processed BIGINT NOT NULL,
 phase VARCHAR(32) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE sys_file (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 state VARCHAR(16) NOT NULL,
 storage_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 byte_size BIGINT NULL,
 row_count BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_file_state CHECK(state IN ('PREPARING','READY','RECOVERING','FAILED'))
) ENGINE=InnoDB;
-- 系统模块持有固定导出成员和安全文本，不使用当前日志重建历史文件。
CREATE TABLE sys_audit_export_row (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ordinal INT NOT NULL,
 actor_dept_id BIGINT NULL,
 user_id BIGINT NULL,
 csv_line TEXT NOT NULL COMMENT '已转义的UTF8 CSV行，不含原始changes或凭据',
 PRIMARY KEY(task_id,ordinal)
) ENGINE=InnoDB;
INSERT INTO sys_menu(id,parent_id,menu_name,menu_type,permission_code,sort_order)
 VALUES (151,150,'menu.auditExport','F','system:audit:export',1);
INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1,151);
UPDATE sys_user SET security_version=security_version+1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id=1);
