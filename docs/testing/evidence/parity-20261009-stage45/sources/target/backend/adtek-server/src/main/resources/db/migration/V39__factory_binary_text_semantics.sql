-- 工厂代码/名称沿用原型utf8mb4_bin：区分大小写及重音，目录排序和唯一性不依赖部署默认值。
ALTER TABLE sn_factory
 MODIFY code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

-- 稳定组织映射中的对外编码使用相同语义；组织内码和来源身份保持原约束。
ALTER TABLE sn_org_mapping
 MODIFY org_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY org_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
