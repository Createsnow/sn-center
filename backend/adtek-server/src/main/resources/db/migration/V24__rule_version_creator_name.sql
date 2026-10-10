-- 用户名在平台账号维护接口中不可修改；仅回填已有可信主体的稳定工号，未知历史保持 NULL。
ALTER TABLE sn_rule_version ADD COLUMN created_by_name VARCHAR(64) NULL;
UPDATE sn_rule_version v JOIN sys_user u ON u.id = v.created_by
SET v.created_by_name = u.username;
