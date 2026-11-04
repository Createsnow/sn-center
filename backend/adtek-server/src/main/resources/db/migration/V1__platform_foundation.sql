-- Adtek template 0.1.0 独立空库初始化；不可用于替换既有产品迁移历史。
CREATE TABLE sys_dept (
    id BIGINT NOT NULL PRIMARY KEY,
    parent_id BIGINT NULL,
    dept_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_dept_parent (parent_id)
) ENGINE=InnoDB;

CREATE TABLE sys_user (
    id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    dept_id BIGINT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    security_version BIGINT NOT NULL DEFAULT 1,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_user_username (username),
    INDEX ix_user_dept (dept_id)
) ENGINE=InnoDB;

CREATE TABLE sys_role (
    id BIGINT NOT NULL PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    data_scope TINYINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_role_code (role_code),
    CONSTRAINT ck_role_scope CHECK (data_scope BETWEEN 1 AND 5)
) ENGINE=InnoDB;

CREATE TABLE sys_menu (
    id BIGINT NOT NULL PRIMARY KEY,
    parent_id BIGINT NULL,
    menu_type CHAR(1) NOT NULL,
    menu_name VARCHAR(100) NOT NULL,
    permission_code VARCHAR(100) NULL,
    route_path VARCHAR(200) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_menu_type CHECK (menu_type IN ('M','C','F')),
    UNIQUE KEY uk_menu_permission (permission_code)
) ENGINE=InnoDB;

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB;
CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB;
CREATE TABLE sys_role_dept (
    role_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, dept_id)
) ENGINE=InnoDB;

ALTER TABLE sys_user MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE sys_user ADD language VARCHAR(5) NOT NULL DEFAULT 'zh-CN';
ALTER TABLE sys_user ADD CONSTRAINT ck_user_language CHECK (language IN ('zh-CN','en','vi'));
ALTER TABLE sys_dept MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE sys_role MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE sys_role ADD resource_scope TINYINT NOT NULL DEFAULT 1;
ALTER TABLE sys_role ADD CONSTRAINT ck_role_resource_scope CHECK (resource_scope IN (1,2));
ALTER TABLE sys_menu ADD component_key VARCHAR(80) NULL;


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
INSERT INTO sys_role(id, role_code, role_name, data_scope, resource_scope) VALUES
    (1, 'PLATFORM_ADMIN', '平台管理员', 1, 1),
    (2, 'EQUIPMENT_ADMIN', '设备管理员', 1, 1),
    (3, 'EQUIPMENT_USER', '设备登记员', 5, 1);

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

ALTER TABLE sys_oper_log ADD module_name VARCHAR(60) NOT NULL DEFAULT '认证';
ALTER TABLE sys_oper_log ADD business_type VARCHAR(16) NOT NULL DEFAULT 'OTHER';
CREATE INDEX ix_user_department_page ON sys_user(dept_id, id);

ALTER TABLE sys_role ADD COLUMN version BIGINT NOT NULL DEFAULT 1 COMMENT '配置乐观锁版本';
ALTER TABLE sys_dept ADD COLUMN version BIGINT NOT NULL DEFAULT 1 COMMENT '配置乐观锁版本';
ALTER TABLE sys_menu MODIFY id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 1 COMMENT '配置乐观锁版本';
CREATE TABLE sys_dict_type (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(64) NOT NULL COMMENT '不可变字典编码', name VARCHAR(100) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 1,
 UNIQUE KEY uk_dict_type_code(code)
) COMMENT='系统字典类型；停用保留历史引用';
CREATE TABLE sys_dict_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, type_id BIGINT NOT NULL,
 value VARCHAR(100) NOT NULL COMMENT '类型内唯一且不可变的业务值',
 label_zh VARCHAR(100) NOT NULL, label_en VARCHAR(100) NOT NULL, label_vi VARCHAR(100) NOT NULL,
 sort_order INT NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 1,
 UNIQUE KEY uk_dict_item_value(type_id,value), KEY ix_dict_item_order(type_id,sort_order,id)
) COMMENT='字典值及三语显示名称；禁止物理删除';
INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,permission_code,route_path,component_key,sort_order)
 VALUES (160,100,'C','menu.dictionaries','system:dict:list','/system/dictionaries','system.dictionaries',60),
        (161,160,'F','menu.dictUpdate','system:dict:update',NULL,NULL,1);
INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1,160),(1,161);
UPDATE sys_user u JOIN sys_user_role ur ON ur.user_id=u.id AND ur.role_id=1
 SET u.security_version=u.security_version+1;

ALTER TABLE sys_oper_log
 ADD COLUMN actor_dept_id BIGINT NULL COMMENT '写审计时的操作者部门快照；历史缺失/匿名为空',
 ADD COLUMN actor_username VARCHAR(64) NULL COMMENT '写审计时的不可变工号快照；未认证为空',
 ADD COLUMN request_method VARCHAR(8) NULL COMMENT '受控HTTP方法，不存请求头',
 ADD COLUMN request_route VARCHAR(160) NULL COMMENT '服务端路由模板，不存原始URL或参数',
 ADD COLUMN http_status SMALLINT NULL COMMENT '入口失败HTTP状态',
 ADD COLUMN error_code INT NULL COMMENT '已知统一错误码；未知为空',
 ADD INDEX ix_oper_log_dept_time(actor_dept_id,create_time,id),
 ADD INDEX ix_oper_log_trace(trace_id),
 ADD INDEX ix_oper_log_action_time(action,create_time,id);

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

ALTER TABLE sys_user ADD principal_type VARCHAR(8) NOT NULL DEFAULT 'USER', ADD CONSTRAINT ck_user_principal CHECK(principal_type='USER');
ALTER TABLE sys_file ADD filename VARCHAR(128) NULL, ADD media_type VARCHAR(128) NULL;
