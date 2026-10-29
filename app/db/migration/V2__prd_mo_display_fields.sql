-- 生产订单快照加只显示的金蝶明细字段：客户 P/N、客户产品描述、规格描述、物料规格型号、生产部门（生产车间）。
-- 不参与比对同步、生成、变更提醒；已有库启动时原地加列（不清库），下次同步金蝶时填入。
-- 列已存在时（用改过 V1 的 8f10229 版本新建的库）跳过，不报重复列。
-- 已执行过的脚本不再修改（Flyway 按校验和核对），表结构再变时另加 V3、V4……
SET @sn_v2_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sn_prd_mo' AND COLUMN_NAME = 'customer_pn') = 0,
  'ALTER TABLE sn_prd_mo
    ADD COLUMN customer_pn   VARCHAR(128) NOT NULL DEFAULT '''' COMMENT ''客户 P/N（金蝶明细 FPN，只显示）'' AFTER qty,
    ADD COLUMN customer_desc VARCHAR(500) NOT NULL DEFAULT '''' COMMENT ''客户产品描述（金蝶明细 FCD，只显示）'' AFTER customer_pn,
    ADD COLUMN spec_desc     VARCHAR(500) NOT NULL DEFAULT '''' COMMENT ''规格描述（金蝶明细 FAdtek，只显示）'' AFTER customer_desc,
    ADD COLUMN material_spec VARCHAR(500) NOT NULL DEFAULT '''' COMMENT ''物料规格型号（FMaterialId.FSpecification，只显示）'' AFTER spec_desc,
    ADD COLUMN dept_code     VARCHAR(64)  NOT NULL DEFAULT '''' COMMENT ''生产部门编码（金蝶明细生产车间 FWorkShopID，只显示）'' AFTER material_spec,
    ADD COLUMN dept_name     VARCHAR(128) NOT NULL DEFAULT '''' COMMENT ''生产部门名称（只显示）'' AFTER dept_code',
  'DO 0'
);
PREPARE sn_v2_stmt FROM @sn_v2_ddl;
EXECUTE sn_v2_stmt;
DEALLOCATE PREPARE sn_v2_stmt;
