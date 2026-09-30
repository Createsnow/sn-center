-- 保留已应用的 V1/V2；管理审计与认证审计共用追加事实表。
ALTER TABLE sys_oper_log ADD module_name VARCHAR(60) NOT NULL DEFAULT '认证';
ALTER TABLE sys_oper_log ADD business_type VARCHAR(16) NOT NULL DEFAULT 'OTHER';
CREATE INDEX ix_user_department_page ON sys_user(dept_id, id);
