-- 固定已知业务拒绝的可读原因；旧错误码和历史行保持不变。
ALTER TABLE sn_audit MODIFY COLUMN actor_id BIGINT NULL,
    ADD COLUMN error_message VARCHAR(1000) NULL;
ALTER TABLE sn_audit_archive MODIFY COLUMN actor_id BIGINT NULL,
    ADD COLUMN error_message VARCHAR(1000) NULL;
