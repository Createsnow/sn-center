-- =====================================================================
-- 生成批次与取消：一次「生成」（整张 PI 的各订单）共用一个批次号，
-- 进度、取消、撤销都按批次整体进行。生成中可请求取消：生成线程看到后整批回滚，任务记为 CANCELED。
-- =====================================================================

ALTER TABLE sn_gen_job
  ADD COLUMN gen_batch VARCHAR(40) NULL COMMENT '生成批次：同一次生成的各订单任务共用' AFTER pi_no,
  ADD COLUMN cancel_by VARCHAR(32) NULL COMMENT '请求取消的账户' AFTER created_by,
  ADD COLUMN cancel_at DATETIME NULL COMMENT '请求取消的时间（生成线程看到后整批回滚）' AFTER cancel_by,
  MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT 'RUNNING / SUCCESS / FAILED / CANCELED / REVOKED',
  ADD KEY ix_job_batch (gen_batch);

-- 升级前的任务各自算一个批次
UPDATE sn_gen_job SET gen_batch = CONCAT('J', id) WHERE gen_batch IS NULL;
