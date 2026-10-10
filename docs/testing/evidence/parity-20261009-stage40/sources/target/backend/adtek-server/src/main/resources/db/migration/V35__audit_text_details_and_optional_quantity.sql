-- 原型规则等非数量操作保留NULL；独立文本详情不破坏既有JSON事实及文件快照。
ALTER TABLE sn_audit
    MODIFY quantity BIGINT NULL,
    ADD COLUMN detail_text VARCHAR(2000) NULL;
ALTER TABLE sn_audit_archive
    MODIFY quantity BIGINT NULL,
    ADD COLUMN detail_text VARCHAR(2000) NULL;
