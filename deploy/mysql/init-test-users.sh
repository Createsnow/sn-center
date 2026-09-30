#!/bin/sh
set -eu
# 只在本项目空卷的第一次初始化执行。运行账号无 DDL 权限，迁移账号单独配置。
# 密码由 infra.ps1 生成 64 位十六进制字符串；拒绝任意 SQL 字符。
app_password="$(cat /run/secrets/mysql_app_password)"
migration_password="$(cat /run/secrets/mysql_migration_password)"
case "$app_password$migration_password" in *[!0-9a-f]*|'') exit 1 ;; esac
export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"
mysql --protocol=socket -uroot <<SQL
CREATE USER 'adtek_app'@'%' IDENTIFIED BY '$app_password';
CREATE USER 'adtek_migration'@'%' IDENTIFIED BY '$migration_password';
GRANT SELECT, INSERT, UPDATE, DELETE ON adtek_sn_test.* TO 'adtek_app'@'%';
GRANT ALL PRIVILEGES ON adtek_sn_test.* TO 'adtek_migration'@'%';
SQL
unset MYSQL_PWD app_password migration_password
