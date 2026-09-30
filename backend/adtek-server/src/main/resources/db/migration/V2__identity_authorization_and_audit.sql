-- V1 已在隔离测试库应用；身份扩展使用新版本，不改写历史迁移。
ALTER TABLE sys_user MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE sys_user ADD language VARCHAR(5) NOT NULL DEFAULT 'zh-CN';
ALTER TABLE sys_user ADD CONSTRAINT ck_user_language CHECK (language IN ('zh-CN','en','vi'));
ALTER TABLE sys_dept MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE sys_role MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE sys_role ADD factory_scope TINYINT NOT NULL DEFAULT 1;
ALTER TABLE sys_role ADD CONSTRAINT ck_role_factory_scope CHECK (factory_scope IN (1,2));
ALTER TABLE sys_menu ADD component_key VARCHAR(80) NULL;

CREATE TABLE sys_user_factory (
    user_id BIGINT NOT NULL,
    factory_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, factory_id)
) ENGINE=InnoDB;

-- 管理员/角色修改共用控制行；该 ID 是锁对象，不赋予任何用户超级权限。
CREATE TABLE sys_security_control (
    id TINYINT NOT NULL PRIMARY KEY,
    initialized BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;
INSERT INTO sys_security_control(id, initialized) VALUES (1, FALSE);

CREATE TABLE sys_oper_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    action VARCHAR(100) NOT NULL,
    object_id VARCHAR(100) NULL,
    success BOOLEAN NOT NULL,
    changes JSON NOT NULL,
    trace_id VARCHAR(36) NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_oper_log_time (create_time, id),
    INDEX ix_oper_log_actor (user_id, create_time)
) ENGINE=InnoDB;

INSERT INTO sys_dept(id, parent_id, dept_name) VALUES (1, NULL, '总部');
INSERT INTO sys_role(id, role_code, role_name, data_scope, factory_scope) VALUES
    (1, 'PLATFORM_ADMIN', '平台管理员', 1, 1),
    (2, 'SN_HQ', 'SN 总部', 1, 2),
    (3, 'FACTORY_OPERATOR', '工厂操作员', 5, 1),
    (4, 'SN_QUERY_ALL', '跨厂查询员', 5, 2),
    (5, 'SN_QUERY_FACTORY', '本厂查询员', 5, 1);

-- 权限是明确的能力清单，菜单可见性不代替后端用例授权；没有默认账号/默认密码。
INSERT INTO sys_menu(id, parent_id, menu_type, menu_name, permission_code, route_path, component_key, sort_order) VALUES
    (100, NULL, 'M', 'menu.system', NULL, '/system', NULL, 10),
    (110, 100, 'C', 'menu.users', 'system:user:list', '/system/users', 'system.users', 10),
    (111, 110, 'F', 'permission.userAdd', 'system:user:add', NULL, NULL, 0),
    (112, 110, 'F', 'permission.userUpdate', 'system:user:update', NULL, NULL, 0),
    (113, 110, 'F', 'permission.userReset', 'system:user:reset', NULL, NULL, 0),
    (114, 110, 'F', 'permission.userStatus', 'system:user:status', NULL, NULL, 0),
    (120, 100, 'C', 'menu.roles', 'system:role:list', '/system/roles', 'system.roles', 20),
    (121, 120, 'F', 'permission.roleUpdate', 'system:role:update', NULL, NULL, 0),
    (130, 100, 'C', 'menu.departments', 'system:dept:list', '/system/departments', 'system.departments', 30),
    (131, 130, 'F', 'permission.deptUpdate', 'system:dept:update', NULL, NULL, 0),
    (140, 100, 'C', 'menu.menus', 'system:menu:list', '/system/menus', 'system.menus', 40),
    (141, 140, 'F', 'permission.menuUpdate', 'system:menu:update', NULL, NULL, 0),
    (150, 100, 'C', 'menu.audit', 'system:audit:list', '/system/audit', 'system.audit', 50),
    (199, 100, 'F', 'permission.administerSecurity', 'system:security:administer', NULL, NULL, 0);
INSERT INTO sys_role_menu(role_id, menu_id) SELECT 1, id FROM sys_menu;
