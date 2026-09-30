# 仅供本项目入口点 dot-source。环境秘密只驻留子进程，不回显；调用方必须 finally 还原。
function Set-AdtekTestEnvironment {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $secretRoot = Join-Path $projectRoot 'deploy/.secrets'
    $values = @{
        ADTEK_DB_URL = 'jdbc:mysql://127.0.0.1:33306/adtek_sn_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED'
        ADTEK_DB_USER = 'adtek_app'
        ADTEK_MIGRATION_USER = 'adtek_migration'
        ADTEK_REDIS_HOST = '127.0.0.1'
        ADTEK_REDIS_PORT = '36379'
        ADTEK_SERVER_ADDRESS = '127.0.0.1'
        ADTEK_SERVER_PORT = '18080'
        ADTEK_FILE_ROOT = (Join-Path $projectRoot 'deploy/data/files')
        SPRING_PROFILES_ACTIVE = 'local'
        # 显式导入新工程受保护配置；缺目录时继续默认禁用，不读取旧.env。
        SPRING_CONFIG_IMPORT = ('optional:configtree:' + (Join-Path $secretRoot 'k3').Replace('\', '/') + '/')
    }
    $files = @{
        ADTEK_DB_PASSWORD = 'mysql-app-password'
        ADTEK_MIGRATION_PASSWORD = 'mysql-migration-password'
        ADTEK_REDIS_PASSWORD = 'redis-password'
        ADTEK_JWT_SECRET = 'jwt-signing-key'
    }
    foreach ($entry in $files.GetEnumerator()) {
        $path = Join-Path $secretRoot $entry.Value
        if (-not (Test-Path -LiteralPath $path)) {
            throw 'Missing test secrets. Run ./tools/infra.ps1 start first.'
        }
        $values[$entry.Key] = [IO.File]::ReadAllText($path).Trim()
        if ($values[$entry.Key] -notmatch '^[0-9a-f]{64}$') {
            throw "Invalid secret format: $($entry.Value)"
        }
    }
    $previous = @{}
    foreach ($entry in $values.GetEnumerator()) {
        $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    return $previous
}

function Restore-AdtekTestEnvironment([hashtable]$Previous) {
    foreach ($entry in $Previous.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}
