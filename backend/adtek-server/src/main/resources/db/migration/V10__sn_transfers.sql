-- S6转厂固定事实；不修改V1—V9，不推断补写旧历史。
ALTER TABLE sn_item ADD current_transfer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD INDEX ix_sn_current_transfer(current_transfer_id,gen_month,id);
CREATE TABLE sn_transfer (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 mode VARCHAR(8) NOT NULL, status VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 from_factory_id BIGINT NOT NULL, from_factory_code VARCHAR(64) NOT NULL, from_factory_name VARCHAR(128) NOT NULL,
 from_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, scope VARCHAR(16) NOT NULL,
 material VARCHAR(128) COLLATE utf8mb4_0900_bin NULL, sn VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
 to_factory_id BIGINT NOT NULL, to_factory_code VARCHAR(64) NOT NULL, to_factory_name VARCHAR(128) NOT NULL,
 to_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, to_customer VARCHAR(64) NOT NULL, to_material VARCHAR(128) NOT NULL,
 target_source VARCHAR(16) NOT NULL, reason VARCHAR(500) NOT NULL, quantity BIGINT NOT NULL,
 actor_id BIGINT NOT NULL, decided_by BIGINT NULL, note VARCHAR(500) NOT NULL DEFAULT '',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), decided_at DATETIME(6) NULL,
 CONSTRAINT ck_transfer_mode CHECK(mode IN ('APPLY','DIRECT')),
 CONSTRAINT ck_transfer_status CHECK(status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
 CONSTRAINT ck_transfer_scope CHECK(scope IN ('PI','MATERIAL','SINGLE')),
 CONSTRAINT ck_transfer_quantity CHECK(quantity>0),
 INDEX ix_transfer_from(from_factory_id,from_pi,created_at,id), INDEX ix_transfer_to(to_factory_id,to_pi,created_at,id),
 INDEX ix_transfer_pending(status,created_at,id)
) ENGINE=InnoDB;
CREATE TABLE sn_transfer_member (
 transfer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, ordinal BIGINT NOT NULL,
 gen_month DATE NOT NULL, item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 from_status VARCHAR(16) NOT NULL, from_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 from_print_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, snapshot JSON NOT NULL,
 PRIMARY KEY(transfer_id,ordinal), UNIQUE KEY uk_transfer_member(transfer_id,gen_month,item_id),
 CONSTRAINT ck_transfer_from_status CHECK(from_status IN ('TO_ACQUIRE','TO_PRINT','PRINTED'))
) ENGINE=InnoDB;
CREATE TABLE sn_transfer_work (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, kind VARCHAR(16) NOT NULL,
 transfer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 from_factory_id BIGINT NOT NULL, from_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 to_pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, payload JSON NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', error_code INT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 CONSTRAINT ck_transfer_work_kind CHECK(kind IN ('APPLY','DIRECT','APPROVE','REJECT','WITHDRAW')),
 CONSTRAINT ck_transfer_work_state CHECK(state IN ('PENDING','SUCCESS','FAILED','CANCELLED'))
) ENGINE=InnoDB;
INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,permission_code,route_path,component_key,sort_order) VALUES
 (270,200,'C','menu.transfer','sn:transfer:view','/transfer','sn.transfer',70),
 (271,270,'F','permission.transferApply','sn:transfer:apply',NULL,NULL,0),
 (272,270,'F','permission.transferWithdraw','sn:transfer:withdraw',NULL,NULL,0),
 (273,270,'F','permission.transferApprove','sn:transfer:approve',NULL,NULL,0),
 (274,270,'F','permission.transferReject','sn:transfer:reject',NULL,NULL,0),
 (275,270,'F','permission.transferDirect','sn:transfer:direct',NULL,NULL,0),
 (276,270,'F','permission.transferTargets','sn:transfer:targets',NULL,NULL,0);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 2,id FROM sys_menu WHERE id BETWEEN 270 AND 276;
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 3,id FROM sys_menu WHERE id IN (270,271,272,276);
UPDATE sys_user SET security_version=security_version+1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id IN (2,3));
