-- 原型生成任务使用独立递增编号。持久任务 UUID 继续负责幂等与关联，页面编号独立保存。
CREATE TABLE sn_generation_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    UNIQUE KEY uk_generation_job_task(task_id)
) ENGINE=InnoDB;
INSERT INTO sn_generation_job(task_id)
SELECT task_id FROM sn_work WHERE kind='GENERATE' ORDER BY created_at,task_id;
