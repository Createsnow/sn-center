#!/bin/sh
set -eu
# Linux 部署机：生成 deploy/.secrets/ 下缺失的随机秘密（64 位十六进制），已有文件保持不变，不输出秘密值。
# 目录 0700 只允许部署用户进入；文件 0644 以便容器内非 root 用户读取单独挂载的秘密文件。
dir="$(cd "$(dirname "$0")" && pwd)/.secrets"
mkdir -p "$dir"
chmod 0700 "$dir"
for name in mysql-root-password mysql-app-password mysql-migration-password; do
  file="$dir/$name"
  if [ ! -s "$file" ]; then
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$file"
    echo "created $name"
  fi
  chmod 0644 "$file"
done
