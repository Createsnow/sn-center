-- S5增量升级，不回填猜测历史领取/打印事实；V1—V8保持冻结。
ALTER TABLE sys_user ADD principal_type VARCHAR(8) NOT NULL DEFAULT 'USER',
 ADD CONSTRAINT ck_sys_principal_type CHECK(principal_type IN ('USER','SERVICE'));
CREATE TABLE sys_service_client (
 user_id BIGINT PRIMARY KEY, client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 secret_hash VARCHAR(255) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 rotated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), CONSTRAINT fk_service_user FOREIGN KEY(user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB COMMENT='稳定主体与可轮换独立凭据；没有默认服务账号';
CREATE TABLE sys_service_permission (
 user_id BIGINT NOT NULL, permission_code VARCHAR(128) NOT NULL, PRIMARY KEY(user_id,permission_code),
 CONSTRAINT fk_service_permission FOREIGN KEY(user_id) REFERENCES sys_service_client(user_id)
) ENGINE=InnoDB;

ALTER TABLE sn_item ADD current_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD current_print_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD acquired_at DATETIME(6) NULL,
 ADD acquire_exception_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD INDEX ix_sn_current_batch(current_batch_id,id), ADD INDEX ix_sn_scope_cursor(factory_id,pi,gen_month,id);

-- S6转厂必须同事务写此固定成员凭据并设置明细指针；空表不放宽任何普通号资格。
CREATE TABLE sn_acquire_exception (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 gen_month DATE NOT NULL, item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 factory_id BIGINT NOT NULL, pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 transfer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(id,gen_month,item_id)
) ENGINE=InnoDB;

CREATE TABLE sn_acquire_work (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, kind VARCHAR(16) NOT NULL,
 factory_id BIGINT NOT NULL, pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 actor_id BIGINT NOT NULL, origin VARCHAR(8) NOT NULL, operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload JSON NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 quantity BIGINT NOT NULL DEFAULT 0, eligible BIGINT NOT NULL DEFAULT 0, blocked BIGINT NOT NULL DEFAULT 0,
 updated_qty BIGINT NOT NULL DEFAULT 0, already_qty BIGINT NOT NULL DEFAULT 0,
 batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, error_code INT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 CONSTRAINT ck_acquire_kind CHECK(kind IN ('TAKE','CALLBACK')),
 CONSTRAINT ck_acquire_work_state CHECK(state IN ('PENDING','SUCCESS','FAILED','CANCELLED')),
 INDEX ix_acquire_work(factory_id,pi,created_at,task_id)
) ENGINE=InnoDB;
CREATE TABLE sn_acquire_batch (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, factory_id BIGINT NOT NULL,
 factory_code VARCHAR(64) NOT NULL, factory_name VARCHAR(128) NOT NULL,
 pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, quantity BIGINT NOT NULL,
 actor_id BIGINT NOT NULL, origin VARCHAR(8) NOT NULL, operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_acquire_nonempty CHECK(quantity>0), INDEX ix_acquire_batch_history(factory_id,pi,created_at,id)
) ENGINE=InnoDB;
-- snapshot仅存服务器字段，永久保存领取时展示；当前状态通过复合主键另读，不能覆盖此JSON。
CREATE TABLE sn_acquire_member (
 batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, ordinal BIGINT NOT NULL,
 gen_month DATE NOT NULL, item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sn VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL, seq_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NULL, seq_dec BIGINT NULL,
 snapshot JSON NOT NULL, PRIMARY KEY(batch_id,ordinal), UNIQUE KEY uk_acquire_member(batch_id,gen_month,item_id),
 UNIQUE KEY uk_acquire_sn(batch_id,sn), INDEX ix_acquire_range(batch_id,seq_pi,seq_dec)
) ENGINE=InnoDB;
CREATE TABLE sn_acquire_result (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, ordinal BIGINT NOT NULL,
 sn VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL, outcome VARCHAR(32) NOT NULL,
 PRIMARY KEY(task_id,ordinal)
) ENGINE=InnoDB COMMENT='零可领及逐项阻止/回调结果持久化，不因重试新增成员';
CREATE TABLE sn_print (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 factory_id BIGINT NOT NULL, factory_code VARCHAR(64) NOT NULL, factory_name VARCHAR(128) NOT NULL,
 pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, quantity BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 csv_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_print_nonempty CHECK(quantity>0), INDEX ix_print_history(factory_id,pi,created_at,id)
) ENGINE=InnoDB;
CREATE TABLE sn_print_member (
 print_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, ordinal BIGINT NOT NULL,
 gen_month DATE NOT NULL, item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, snapshot JSON NOT NULL,
 PRIMARY KEY(print_id,ordinal), UNIQUE KEY uk_print_member(print_id,gen_month,item_id)
) ENGINE=InnoDB;
ALTER TABLE sys_file ADD filename VARCHAR(128) NULL, ADD media_type VARCHAR(128) NULL;

INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,permission_code,route_path,component_key,sort_order) VALUES
 (260,200,'C','menu.acquire','sn:acquire:view','/acquire','sn.acquire',60),
 (261,260,'F','permission.acquire','sn:acquire:take',NULL,NULL,0),
 (262,260,'F','permission.print','sn:print:execute',NULL,NULL,0),
 (263,260,'F','permission.printDownload','sn:print:download',NULL,NULL,0),
 (264,260,'F','permission.callback','sn:acquire:callback',NULL,NULL,0),
 (265,260,'F','permission.itemsRead','sn:items:read',NULL,NULL,0),
 (266,260,'F','permission.serviceClients','system:service-client:manage',NULL,NULL,0);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 2,id FROM sys_menu WHERE id BETWEEN 260 AND 265;
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 3,id FROM sys_menu WHERE id BETWEEN 260 AND 265;
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 4,id FROM sys_menu WHERE id IN (260,263,265);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 5,id FROM sys_menu WHERE id IN (260,263,265);
INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,266);
UPDATE sys_user SET security_version=security_version+1;
