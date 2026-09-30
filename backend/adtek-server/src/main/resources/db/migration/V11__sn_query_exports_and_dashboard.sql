-- S6只读查询固定导出，快照不再依赖当前SN行、今日规则或今日工厂名称。
CREATE TABLE sn_query_export (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, actor_id BIGINT NOT NULL,
 format VARCHAR(8) NOT NULL, filters JSON NOT NULL, quantity BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_query_export_format CHECK(format IN ('CSV','XLSX')),
 INDEX ix_query_export_owner(actor_id,created_at,task_id)
) ENGINE=InnoDB;
CREATE TABLE sn_query_export_row (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, ordinal BIGINT NOT NULL,
 factory_id BIGINT NULL, pi VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, cells JSON NOT NULL,
 PRIMARY KEY(task_id,ordinal), INDEX ix_query_export_scope(task_id,factory_id,pi)
) ENGINE=InnoDB;
INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,permission_code,route_path,component_key,sort_order) VALUES
 (280,200,'C','menu.query','sn:query:view','/query','sn.query',80),
 (281,280,'F','permission.queryExport','sn:query:export',NULL,NULL,0),
 (290,200,'C','menu.dashboard','sn:dashboard:view','/dashboard','sn.dashboard',1);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.id IN (280,281,290) WHERE r.id IN (2,3,4,5);
UPDATE sys_user SET security_version=security_version+1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id IN (2,3,4,5));
