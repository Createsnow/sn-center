-- 全局配置使用显式安全管理权限；版本列防止旧页面覆盖并发修改。
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
-- 新增种子权限也是授权变更，既有管理员令牌必须重新验证。
UPDATE sys_user u JOIN sys_user_role ur ON ur.user_id=u.id AND ur.role_id=1
 SET u.security_version=u.security_version+1;
