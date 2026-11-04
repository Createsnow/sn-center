#!/bin/sh
set -eu
# 密码只进入 tmpfs 中的配置文件；不写入镜像或持久数据卷。
umask 077
password="$(cat /run/secrets/redis_password)"
case "$password" in *[!0-9a-f]*|'') exit 1 ;; esac
cat > /run/redis/redis.conf <<CONF
bind 0.0.0.0
protected-mode yes
port 6379
requirepass $password
dir /data
appendonly yes
appendfsync everysec
maxmemory 256mb
maxmemory-policy noeviction
CONF
unset password
exec redis-server /run/redis/redis.conf
