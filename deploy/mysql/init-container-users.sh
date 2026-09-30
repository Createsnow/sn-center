#!/bin/sh
set -eu
# 只针对容器演练独立空卷；不连接本机adtek_sn_test，更不执行旧项目schema。
app_password="$(cat /run/secrets/mysql_app_password)"
migration_password="$(cat /run/secrets/mysql_migration_password)"
maintenance_password="$(cat /run/secrets/mysql_maintenance_password)"
case "$app_password$migration_password$maintenance_password" in *[!0-9a-f]*|'') exit 1 ;; esac
export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"
mysql --protocol=socket -uroot <<SQL
CREATE USER 'adtek_app'@'%' IDENTIFIED BY '$app_password';
CREATE USER 'adtek_migration'@'%' IDENTIFIED BY '$migration_password';
CREATE USER 'adtek_maintenance'@'%' IDENTIFIED BY '$maintenance_password';
GRANT SELECT, INSERT, UPDATE, DELETE ON adtek_sn_container.* TO 'adtek_app'@'%';
GRANT ALL PRIVILEGES ON adtek_sn_container.* TO 'adtek_migration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, ALTER ON adtek_sn_container.* TO 'adtek_maintenance'@'%';
SQL
unset MYSQL_PWD app_password migration_password maintenance_password
