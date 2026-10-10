-- S3增量：旧工厂关系复制后由SN端口拥有；不删除旧表/账号/卷，不伪造旧ERP来源身份。
CREATE TABLE sn_user_factory LIKE sys_user_factory;
INSERT INTO sn_user_factory(user_id,factory_id) SELECT user_id,factory_id FROM sys_user_factory;

CREATE TABLE sn_factory (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
 source VARCHAR(10) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_sn_factory_code(code), UNIQUE KEY uk_sn_factory_name(name),
 CONSTRAINT ck_sn_factory_source CHECK(source IN ('MANUAL','K3'))
) ENGINE=InnoDB;

CREATE TABLE sn_org_mapping (
 source_system VARCHAR(16) NOT NULL, source_account VARCHAR(64) NOT NULL,
 org_id VARCHAR(64) NOT NULL COMMENT 'ERP稳定组织内码，不以名称/行序代替',
 org_code VARCHAR(64) NOT NULL, org_name VARCHAR(128) NOT NULL,
 factory_id BIGINT NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(source_system,source_account,org_id),
 UNIQUE KEY uk_sn_org_code(source_system,source_account,org_code),
 UNIQUE KEY uk_sn_org_factory(source_system,source_account,factory_id)
) ENGINE=InnoDB;

CREATE TABLE sn_snapshot_control (
 id TINYINT NOT NULL PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT INTO sn_snapshot_control(id) VALUES(1);

CREATE TABLE sn_order_bill (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source_system VARCHAR(16) NOT NULL, source_account VARCHAR(64) NOT NULL,
 source_bill_id VARCHAR(64) NOT NULL, bill_no VARCHAR(64) NOT NULL,
 customer VARCHAR(64) NOT NULL, po VARCHAR(128) NOT NULL, pi VARCHAR(128) NOT NULL,
 org_id VARCHAR(64) NOT NULL, org_code VARCHAR(64) NOT NULL, org_name VARCHAR(128) NOT NULL,
 factory_id BIGINT NULL COMMENT '显式组织映射；未映射只允许全部工厂范围查看',
 factory_code VARCHAR(64) NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
 total_qty BIGINT NOT NULL, line_count BIGINT NOT NULL, statuses VARCHAR(8) NOT NULL,
 snapshot_version BIGINT NOT NULL, synced_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_sn_source_bill(source_system,source_account,source_bill_id),
 INDEX ix_sn_bill_scope(factory_id,active,bill_no,id), INDEX ix_sn_bill_pi(pi,active),
 CONSTRAINT ck_sn_bill_qty CHECK(total_qty>=0 AND line_count>=0)
) ENGINE=InnoDB;

CREATE TABLE sn_order_entry (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, bill_id BIGINT NOT NULL,
 source_entry_id VARCHAR(64) NOT NULL COMMENT 'ERP稳定分录内码，ordinal仅显示/分配顺序',
 ordinal BIGINT NOT NULL, material VARCHAR(128) NOT NULL, material_name VARCHAR(255) NOT NULL,
 qty BIGINT NOT NULL, status VARCHAR(1) NOT NULL, demand_bill_no VARCHAR(128) NOT NULL,
 factory_id BIGINT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, snapshot_version BIGINT NOT NULL,
 UNIQUE KEY uk_sn_source_entry(bill_id,source_entry_id), INDEX ix_sn_entry_scope(factory_id,bill_id,active,ordinal),
 CONSTRAINT ck_sn_entry_qty CHECK(qty>=0), CONSTRAINT ck_sn_entry_status CHECK(status IN ('1','2'))
) ENGINE=InnoDB;

CREATE TABLE sn_source_entry_quota (
 bill_id BIGINT NOT NULL, source_entry_id VARCHAR(64) NOT NULL,
 generated_qty BIGINT NOT NULL DEFAULT 0, allocated_qty BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(bill_id,source_entry_id),
 CONSTRAINT ck_sn_quota CHECK(generated_qty>=0 AND allocated_qty>=0 AND allocated_qty<=generated_qty)
) ENGINE=InnoDB COMMENT='经bill_id关联稳定来源四元组；快照消失不删历史累计，生成只能同事务递增';

CREATE TABLE sn_order_sync (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 actor_id BIGINT NOT NULL, mode VARCHAR(10) NOT NULL, bill_no VARCHAR(64) NOT NULL,
 state VARCHAR(10) NOT NULL, base_version BIGINT NOT NULL, published_version BIGINT NULL,
 rows_count BIGINT NOT NULL DEFAULT 0, bills_count BIGINT NOT NULL DEFAULT 0,
 error_code INT NULL, org_sync_failed BOOLEAN NOT NULL DEFAULT FALSE,
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 CONSTRAINT ck_sn_sync_state CHECK(state IN ('FETCHING','SUCCESS','FAILED')),
 CONSTRAINT ck_sn_sync_mode CHECK(mode IN ('all','one')), INDEX ix_sn_sync_time(started_at,id)
) ENGINE=InnoDB;

CREATE TABLE sn_rule (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
 bind_scope VARCHAR(10) NOT NULL, bind_value VARCHAR(128) NOT NULL,
 current_version BIGINT NOT NULL DEFAULT 1, revision BIGINT NOT NULL DEFAULT 1,
 created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_sn_rule_code(code), UNIQUE KEY uk_sn_rule_binding(bind_scope,bind_value),
 CONSTRAINT ck_sn_rule_scope CHECK(bind_scope IN ('GENERAL','CUSTOMER','PI')),
 CONSTRAINT ck_sn_rule_binding CHECK((bind_scope='GENERAL' AND bind_value='') OR (bind_scope<>'GENERAL' AND bind_value<>''))
) ENGINE=InnoDB;

CREATE TABLE sn_rule_version (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, rule_id BIGINT NOT NULL, version BIGINT NOT NULL,
 prefix VARCHAR(64) NOT NULL, suffix VARCHAR(64) NOT NULL, base INT NOT NULL, seq_length INT NOT NULL,
 alphabet VARCHAR(144) NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE,
 content_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化编码参数摘要；改名不变',
 created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_sn_rule_version(rule_id,version),
 CONSTRAINT ck_sn_rule_encoding CHECK(base IN (10,16,32,36) AND seq_length BETWEEN 1 AND 12)
) ENGINE=InnoDB;

-- 保留旧页面路径；权限与工厂范围成对装配，不凭角色名称或用户ID绕过用例。
INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,permission_code,route_path,component_key,sort_order) VALUES
 (200,NULL,'M','menu.sn',NULL,'/sn',NULL,5),
 (210,200,'C','menu.factories','sn:factory:list','/factories','sn.factories',10),
 (211,210,'F','permission.factoryCreate','sn:factory:create',NULL,NULL,0),
 (212,210,'F','permission.factorySync','sn:factory:sync',NULL,NULL,0),
 (213,210,'F','permission.factoryMap','sn:factory:map',NULL,NULL,0),
 (214,210,'F','permission.factoryAssign','sn:factory:assign',NULL,NULL,0),
 (220,200,'C','menu.orders','sn:order:list','/orders','sn.orders',20),
 (221,220,'F','permission.orderSync','sn:order:sync',NULL,NULL,0),
 (230,200,'C','menu.rules','sn:rule:list','/rules','sn.rules',30),
 (231,230,'F','permission.ruleCreate','sn:rule:create',NULL,NULL,0),
 (232,230,'F','permission.ruleUpdate','sn:rule:update',NULL,NULL,0),
 (233,230,'F','permission.rulePreview','sn:rule:preview',NULL,NULL,0),
 (234,230,'F','permission.ruleEffective','sn:rule:effective',NULL,NULL,0);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 2,id FROM sys_menu WHERE id BETWEEN 200 AND 234;
INSERT INTO sys_role_menu(role_id,menu_id) VALUES (3,200),(3,210),(4,200),(4,210),(4,220),(5,200),(5,210),(5,220);
UPDATE sys_user SET security_version=security_version+1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id IN (2,3,4,5));
