-- 固定导入输入按千条保存；任务登记同事务，旧内联载荷继续可执行。
CREATE TABLE sn_import_chunk (
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ordinal INT NOT NULL,
 quantity INT NOT NULL,
 sns JSON NOT NULL,
 PRIMARY KEY(task_id, ordinal),
 CONSTRAINT fk_sn_import_work FOREIGN KEY(task_id) REFERENCES sn_work(task_id) ON DELETE CASCADE,
 CONSTRAINT ck_sn_import_chunk CHECK(ordinal>=0 AND quantity BETWEEN 1 AND 1000 AND JSON_TYPE(sns)='ARRAY' AND JSON_LENGTH(sns)=quantity)
) ENGINE=InnoDB COMMENT='仅存初始化固定输入；业务成员仍整批事务提交';
