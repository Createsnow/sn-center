#!/bin/sh
set -eu
# 只在空数据卷第一次初始化时执行（mysql 官方镜像 initdb 机制）。运行账号无 DDL 权限，迁移账号单独配置。
# 密码由 tools\infra.bat 或 deploy/init-secrets.sh 生成的 64 位十六进制字符串；拒绝任意 SQL 字符。
app_password="$(cat /run/secrets/mysql_app_password)"
migration_password="$(cat /run/secrets/mysql_migration_password)"
case "$app_password$migration_password" in *[!0-9a-f]*|'') exit 1 ;; esac
case "$MYSQL_DATABASE" in *[!a-z0-9_]*|'') exit 1 ;; esac
export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"
mysql --protocol=socket -uroot <<SQL
CREATE USER 'adtek_app'@'%' IDENTIFIED BY '$app_password';
CREATE USER 'adtek_migration'@'%' IDENTIFIED BY '$migration_password';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$MYSQL_DATABASE\`.* TO 'adtek_app'@'%';
GRANT ALL PRIVILEGES ON \`$MYSQL_DATABASE\`.* TO 'adtek_migration'@'%';
SQL
unset MYSQL_PWD app_password migration_password
