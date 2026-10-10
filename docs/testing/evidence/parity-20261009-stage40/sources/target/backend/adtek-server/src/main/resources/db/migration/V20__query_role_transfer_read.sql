-- 对齐原型查询员的转厂历史入口，仅授予读取，不授予申请、审批或目标候选写入资格。
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.id,270 FROM sys_role r
WHERE r.id IN (4,5)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu m WHERE m.role_id=r.id AND m.menu_id=270);
UPDATE sys_user SET security_version=security_version+1
WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id IN (4,5));
