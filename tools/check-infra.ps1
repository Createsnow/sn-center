[CmdletBinding()]
param()

# 在已启动的本项目容器内验证认证和实际协议，不把 TCP 端口可达当服务可用。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $projectRoot 'deploy/compose.test.yml'
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$dockerPath = if ($dockerCommand) { $dockerCommand.Source } else {
    @((Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),
        'C:/Program Files/Docker/Docker/resources/bin/docker.exe') |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $dockerPath) { throw 'Docker CLI is not installed.' }
$mysqlResult = & $dockerPath compose -f $composePath exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_app_password)" mysql --protocol=tcp -h127.0.0.1 -uadtek_app adtek_sn_test --batch --skip-column-names -e "SELECT VERSION(),DATABASE(),CURRENT_USER();"'
if ($LASTEXITCODE -ne 0 -or $mysqlResult -notmatch '^8\.4\.') { throw 'MySQL authenticated query failed or wrong major version.' }
Write-Output "MySQL authenticated query: $mysqlResult"
$privileges = & $dockerPath compose -f $composePath exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_app_password)" mysql --protocol=tcp -h127.0.0.1 -uadtek_app adtek_sn_test --batch --skip-column-names -e "SHOW GRANTS;"'
if ($LASTEXITCODE -ne 0 -or ($privileges -join ' ') -match 'ALL PRIVILEGES|CREATE|DROP|ALTER') {
    throw 'Application account privileges are broader than the test configuration allows.'
}
$redisResult = & $dockerPath compose -f $composePath exec -T redis sh -c 'REDISCLI_AUTH="$(cat /run/secrets/redis_password)" redis-cli ping'
if ($LASTEXITCODE -ne 0 -or $redisResult.Trim() -ne 'PONG') { throw 'Redis authenticated PING failed.' }
Write-Output 'Redis authenticated PING: PONG'
$anonymous = & $dockerPath compose -f $composePath exec -T redis redis-cli ping
if (($anonymous -join ' ') -notmatch 'NOAUTH') { throw 'Redis did not reject unauthenticated access.' }
Write-Output 'Redis anonymous request rejected as expected.'
& $dockerPath compose -f $composePath ps
if ($LASTEXITCODE -ne 0) { throw 'Failed to read Compose status.' }
Write-Output 'Infrastructure protocol and authentication checks passed.'
