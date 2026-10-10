-- 自动同步记录不构成显式绑定；既有成功绑定以审计事实恢复，保留人员明确设置的归属。
ALTER TABLE sn_org_mapping
    ADD COLUMN binding_kind VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    ADD CONSTRAINT ck_sn_org_binding_kind CHECK (binding_kind IN ('AUTO','EXPLICIT'));

-- 早期日志只有工厂ID，无法区分同一工厂的多个来源；保守保护其全部既有映射。
UPDATE sn_org_mapping m SET binding_kind='EXPLICIT'
WHERE EXISTS (
    SELECT 1 FROM sys_oper_log a
    WHERE a.action='sn:factory:map' AND a.success=TRUE
      AND BINARY a.object_id=BINARY CAST(m.factory_id AS CHAR)
);

ALTER TABLE sn_org_mapping
    DROP INDEX uk_sn_org_code,
    DROP INDEX uk_sn_org_factory,
    ADD COLUMN explicit_code VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN binding_kind='EXPLICIT' THEN org_code ELSE NULL END) VIRTUAL,
    ADD COLUMN explicit_factory_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN binding_kind='EXPLICIT' THEN factory_id ELSE NULL END) VIRTUAL,
    ADD UNIQUE KEY uk_sn_org_explicit_code(source_system,source_account,explicit_code),
    ADD UNIQUE KEY uk_sn_org_explicit_factory(source_system,source_account,explicit_factory_id);

-- 对齐当前快照归属，不改历史SN、领取、转厂、生成记录和已消耗额度。
-- 仅存在差异时抬全局修订，使旧预演失效；没有差异的原行保持原版本。
UPDATE sn_snapshot_control SET revision=revision+1 WHERE id=1 AND EXISTS (
    SELECT 1 FROM sn_order_bill b
    LEFT JOIN sn_org_mapping m ON m.source_system=b.source_system
      AND m.source_account=b.source_account AND m.org_id=b.org_id AND m.binding_kind='EXPLICIT'
    LEFT JOIN sn_factory f ON BINARY f.name=BINARY b.org_name
    WHERE b.active=TRUE AND m.org_id IS NULL
      AND (NOT(b.factory_id <=> f.id) OR NOT(BINARY b.factory_code <=> BINARY f.code))
);
UPDATE sn_order_bill b
LEFT JOIN sn_org_mapping m ON m.source_system=b.source_system
  AND m.source_account=b.source_account AND m.org_id=b.org_id AND m.binding_kind='EXPLICIT'
LEFT JOIN sn_factory f ON BINARY f.name=BINARY b.org_name
SET b.factory_id=f.id,b.factory_code=f.code,
    b.snapshot_version=(SELECT revision FROM sn_snapshot_control WHERE id=1)
WHERE b.active=TRUE AND m.org_id IS NULL
  AND (NOT(b.factory_id <=> f.id) OR NOT(BINARY b.factory_code <=> BINARY f.code));
UPDATE sn_order_entry e JOIN sn_order_bill b ON b.id=e.bill_id
SET e.factory_id=b.factory_id,
    e.snapshot_version=(SELECT revision FROM sn_snapshot_control WHERE id=1)
WHERE b.active=TRUE AND e.active=TRUE AND NOT(e.factory_id <=> b.factory_id);
