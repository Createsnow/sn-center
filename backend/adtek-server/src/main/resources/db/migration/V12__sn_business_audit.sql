-- SN业务事实独立于平台管理日志；默认永久保留，归档不等于销毁。
CREATE TABLE sn_audit (
 id VARCHAR(36) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 actor_id BIGINT NOT NULL,
 operator VARCHAR(64) NOT NULL,
 operator_name VARCHAR(100) NOT NULL,
 roles TEXT NOT NULL,
 source VARCHAR(16) NOT NULL,
 action VARCHAR(80) NOT NULL,
 result VARCHAR(16) NOT NULL,
 factory_id BIGINT NULL,
 factory_code VARCHAR(64) NULL,
 factory_name VARCHAR(128) NULL,
 pi VARCHAR(128) NULL,
 to_factory_id BIGINT NULL,
 to_factory_code VARCHAR(64) NULL,
 to_pi VARCHAR(128) NULL,
 customer VARCHAR(128) NULL,
 material VARCHAR(128) NULL,
 bill_no VARCHAR(128) NULL,
 start_sn VARCHAR(512) NULL,
 end_sn VARCHAR(512) NULL,
 quantity BIGINT NOT NULL,
 before_status VARCHAR(80) NULL,
 after_status VARCHAR(80) NULL,
 batch_id VARCHAR(36) NULL,
 operation_id VARCHAR(36) NULL,
 transfer_id VARCHAR(36) NULL,
 reason VARCHAR(1000) NULL,
 error_code VARCHAR(20) NULL,
 trace_id VARCHAR(64) NULL,
 detail JSON NOT NULL,
 PRIMARY KEY(created_at,id),
 INDEX ix_sn_audit_factory(factory_id,created_at,id),
 INDEX ix_sn_audit_destination(to_factory_id,created_at,id),
 INDEX ix_sn_audit_action(action,created_at,id),
 INDEX ix_sn_audit_pi(pi,created_at,id)
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(created_at) (
 PARTITION p_history VALUES LESS THAN ('2026-09-01'),
 PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
 PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
 PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
 PARTITION pmax VALUES LESS THAN (MAXVALUE)
);
CREATE TABLE sn_audit_archive LIKE sn_audit;
CREATE TABLE sn_audit_export LIKE sn_query_export;
CREATE TABLE sn_audit_export_row (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, ordinal BIGINT NOT NULL,
 factory_id BIGINT NULL, to_factory_id BIGINT NULL, cells JSON NOT NULL,
 PRIMARY KEY(task_id,ordinal), INDEX ix_audit_export_scope(task_id,factory_id,to_factory_id)
) ENGINE=InnoDB;
INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,permission_code,route_path,component_key,sort_order) VALUES
 (300,200,'C','menu.snAudit','sn:audit:view','/audit','sn.audit',90),
 (301,300,'F','permission.snAuditExport','sn:audit:export',NULL,NULL,0);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.id IN (300,301) WHERE r.id IN (2,3,4,5);
UPDATE sys_user SET security_version=security_version+1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id IN (2,3,4,5));
