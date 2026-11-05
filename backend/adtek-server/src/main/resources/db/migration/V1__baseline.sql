-- 基线：固定当前库的默认字符集与排序规则，后续迁移建表默认继承。
-- 外部或托管 MySQL 的服务器默认值可能不同（如 utf8mb4_0900_ai_ci），不依赖创建库时的参数。
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
