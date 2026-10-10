-- 执行状态与最近结果分开；正在拉取时保留上一轮结果供原型一致的界面摘要使用。
ALTER TABLE sn_order_sync_schedule
    ADD COLUMN last_ok BOOLEAN NULL,
    ADD COLUMN last_error_message VARCHAR(500) NOT NULL DEFAULT '';

UPDATE sn_order_sync_schedule
SET last_ok=CASE last_state WHEN 'SUCCESS' THEN TRUE WHEN 'FAILED' THEN FALSE ELSE NULL END;
