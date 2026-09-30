#!/bin/sh
set -eu
# 密钥只从明确挂载项装入本进程，不使用eval，不输出值；运行容器不挂载迁移账号密码。
for name in ADTEK_DB_PASSWORD ADTEK_MIGRATION_PASSWORD ADTEK_MAINT_DB_PASSWORD ADTEK_REDIS_PASSWORD ADTEK_JWT_SECRET ADTEK_BOOTSTRAP_PASSWORD; do
  path="/run/secrets/$name"
  if [ -f "$path" ]; then
    value="$(cat "$path")"
    [ -n "$value" ] || exit 2
    export "$name=$value"
  fi
done
unset value path name
exec java -XX:MaxRAMPercentage=70 -Djava.io.tmpdir=/tmp -jar /app/app.jar "$@"
