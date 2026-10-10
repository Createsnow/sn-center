-- 原型规则目录按 utf8mb4_bin 区分大小写并按码点排序；不能随测试/部署库的默认排序规则改变。
ALTER TABLE sn_rule
 MODIFY code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY bind_scope VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY bind_value VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
