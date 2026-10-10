-- 已有审计导出保持28列固定快照；新文件使用独立版本，不能改写旧文件摘要。
ALTER TABLE sn_audit_export ADD COLUMN format_version INT NOT NULL DEFAULT 1;

-- 新审计与顺序身份在同一事务追加；归档保留身份，不从随机UUID伪造旧创建顺序。
CREATE TABLE sn_audit_identity (
 creation_order BIGINT NOT NULL AUTO_INCREMENT,
 audit_id VARCHAR(36) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(creation_order),
 UNIQUE KEY uk_sn_audit_identity(created_at,audit_id)
) ENGINE=InnoDB;
