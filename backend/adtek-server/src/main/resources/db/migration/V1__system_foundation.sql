-- 全新目标库初始化，禁止对旧 sn-center-python schema 执行。
-- 业务标识 JSON 层以文本输出；数据库内部使用 BIGINT，不含无隔离能力的 tenant_id。
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
