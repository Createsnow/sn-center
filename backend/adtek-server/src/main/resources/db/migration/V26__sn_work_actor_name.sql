-- 稳定工号在提交时快照；只回填仍有可信账号对应的历史记录，未知来源保持 NULL。
ALTER TABLE sn_work ADD COLUMN actor_name VARCHAR(64) NULL;
UPDATE sn_work w JOIN sys_user u ON u.id = w.actor_id
SET w.actor_name = u.username;
