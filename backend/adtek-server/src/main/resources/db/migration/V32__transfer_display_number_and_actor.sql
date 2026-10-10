-- 转厂业务单号独立于请求 UUID，沿用原型 T + 本地秒时间 + 四位尾号。
-- 旧记录缺失的业务单号不伪造；工号仅从仍存在的可信账号回填。
ALTER TABLE sn_transfer
 ADD COLUMN transfer_no CHAR(19) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN actor_name VARCHAR(64) NULL,
 ADD COLUMN decided_name VARCHAR(64) NULL,
 ADD UNIQUE KEY uk_sn_transfer_no(transfer_no);
UPDATE sn_transfer t JOIN sys_user u ON u.id=t.actor_id SET t.actor_name=u.username;
UPDATE sn_transfer t JOIN sys_user u ON u.id=t.decided_by SET t.decided_name=u.username;
