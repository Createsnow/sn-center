-- 已生成查询文件保留原22列、原始快照和摘要；新导出使用原型11列。
-- 恢复工作器按持久版本选择写法，不能用当前表头改写旧文件。
ALTER TABLE sn_query_export ADD COLUMN format_version INT NOT NULL DEFAULT 1;
