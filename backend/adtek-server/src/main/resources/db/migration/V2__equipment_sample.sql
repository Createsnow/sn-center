-- 模板设备台账样板；编码不可更改，所属部门与所有者均来自可信身份。
CREATE TABLE equipment_asset (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(128) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 1,
 dept_id BIGINT NOT NULL,
 create_by BIGINT NOT NULL,
 update_by BIGINT NOT NULL,
 create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_equipment_code(code),
 INDEX ix_equipment_scope(dept_id,create_by,id),
 CONSTRAINT ck_equipment_version CHECK(version>0)
) ENGINE=InnoDB;
INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,permission_code,route_path,component_key,sort_order) VALUES
 (200,NULL,'M','menu.equipment',NULL,'/equipment-area',NULL,20),
 (210,200,'C','menu.equipment','equipment:asset:list','/equipment','equipment.assets',10),
 (211,210,'F','permission.equipmentDetail','equipment:asset:detail',NULL,NULL,0),
 (212,210,'F','permission.equipmentCreate','equipment:asset:create',NULL,NULL,0),
 (213,210,'F','permission.equipmentUpdate','equipment:asset:update',NULL,NULL,0);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 2,id FROM sys_menu WHERE id BETWEEN 200 AND 213;
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 3,id FROM sys_menu WHERE id BETWEEN 200 AND 213;
