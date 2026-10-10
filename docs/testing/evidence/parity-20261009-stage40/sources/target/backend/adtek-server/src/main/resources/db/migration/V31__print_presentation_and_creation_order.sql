-- 新成员的创建顺序由护栏表同事务插入顺序确定，转厂更新不会改变顺序。
-- 旧 UUID 数据没有原始自增 ID；升级赋予的顺序只保证稳定，不冒充原型历史顺序。
ALTER TABLE sn_key
 ADD COLUMN creation_order BIGINT NOT NULL AUTO_INCREMENT,
 ADD UNIQUE KEY uk_sn_key_creation_order(creation_order);

-- 业务单号与请求 UUID 分离；保留已交付文件的格式和摘要，恢复按版本执行。
CREATE TABLE sn_business_number (
 number CHAR(19) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY
) ENGINE=InnoDB;
ALTER TABLE sn_acquire_batch
 ADD COLUMN batch_no CHAR(19) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN actor_name VARCHAR(64) NULL,
 ADD UNIQUE KEY uk_sn_acquire_batch_no(batch_no);
ALTER TABLE sn_print
 ADD COLUMN print_no CHAR(19) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN actor_name VARCHAR(64) NULL,
 ADD COLUMN format_version INT NOT NULL DEFAULT 1,
 ADD UNIQUE KEY uk_sn_print_no(print_no);
ALTER TABLE sn_print_member ADD COLUMN batch_no VARCHAR(36) NULL;
-- 历史工号仅使用仍存在的可信账号；历史业务单号缺失保持 NULL，不伪造原号。
UPDATE sn_acquire_batch b JOIN sys_user u ON u.id=b.actor_id SET b.actor_name=u.username;
UPDATE sn_print p JOIN sys_user u ON u.id=p.actor_id SET p.actor_name=u.username;
