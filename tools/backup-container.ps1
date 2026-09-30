[CmdletBinding()]
param([string]$RunId = [Guid]::NewGuid().ToString())
# 仅备份固定container-test工程。先停止Web/API，再持全局读锁，联合封存SQL与不可变文件。
# 不备份Redis会话，不删除任何卷；失败保留未完成目录，并恢复原测试服务。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'DockerEnvironment.ps1')
. (Join-Path $PSScriptRoot 'BackupSupport.ps1')
$docker = Get-AdtekDocker; $projectRoot = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $projectRoot 'deploy/compose.container-test.yml'
$id = [Guid]::Empty
if (![Guid]::TryParseExact($RunId, 'D', [ref]$id)) {
    throw 'Backup RunId must be UUID.'
}
$backupRoot = Join-Path $projectRoot 'deploy/data/backups'
$destination = Join-Path $backupRoot $id.ToString()
if (Test-Path -LiteralPath $destination) {
    throw 'Backup destination already exists; use a new RunId.'
}
$mysql = (& $docker compose -f $compose ps -q mysql).Trim()
$api = (& $docker compose -f $compose ps -q api).Trim()
$web = (& $docker compose -f $compose ps -q web).Trim()
foreach ($container in @($mysql, $api, $web)) {
    if ($container -notmatch '^[0-9a-f]{64}$') {
        throw 'Source test services must be running.'
    }
}
$apiImage = (& $docker inspect --format '{{.Image}}' $api).Trim()
$webImage = (& $docker inspect --format '{{.Image}}' $web).Trim()
foreach ($image in @($apiImage, $webImage)) {
    if ($image -notmatch '^sha256:[0-9a-f]{64}$') {
        throw 'Cannot pin source application images.'
    }
}
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $destination /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' /Q | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Cannot protect backup directory.'
}
$started = [DateTime]::UtcNow; $lockProcess = $null; $connectionId = $null; $stopped = $false
try {
    $stopped = $true
    & $docker compose -f $compose stop web api
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot quiesce source application.'
    }
    $lockShell = 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql --protocol=socket -uroot --batch --skip-column-names --unbuffered --skip-reconnect adtek_sn_container'
    $lockProcess = New-AdtekDockerProcess $docker @('exec', '-i', $mysql, 'sh', '-c', $lockShell)
    $lockErrors = $lockProcess.StandardError.ReadToEndAsync()
    $lockProcess.StandardInput.WriteLine('SET SESSION lock_wait_timeout=10; FLUSH TABLES WITH READ LOCK; SELECT CONNECTION_ID(); DO SLEEP(240);')
    $lockProcess.StandardInput.Close()
    $ready = $lockProcess.StandardOutput.ReadLineAsync()
    if (!$ready.Wait(20000)) {
        throw 'Backup read lock unavailable within budget.'
    }
    $connectionId = $ready.Result
    if ($connectionId -notmatch '^[0-9]+$' -or $lockProcess.HasExited) {
        throw 'Backup read lock failed.'
    }
    $lockedAt = [DateTime]::UtcNow
    $identity = Invoke-AdtekContainerSql $docker $mysql 'adtek_sn_container' 'SELECT CONCAT(@@server_uuid,CHAR(9),@@version,CHAR(9),DATABASE());'
    $dump = Join-Path $destination 'database.sql'; $tar = Join-Path $destination 'files.tar'
    $dumpShell = 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysqldump --protocol=socket -uroot --single-transaction --skip-lock-tables --skip-comments --order-by-primary --hex-blob --set-gtid-purged=OFF --no-tablespaces --routines --events --triggers --default-character-set=utf8mb4 adtek_sn_container'
    Invoke-AdtekDockerOutput $docker @('exec', $mysql, 'sh', '-c', $dumpShell) $dump 120
    Invoke-AdtekDockerOutput $docker @('cp', "${api}:/app/var/files/.", '-') $tar 60
    $files = @(Get-AdtekTarManifest $tar)
    $browserFixture = Join-Path $projectRoot 'frontend/test-results/container-fixture.json'
    $fixtureHash = $null
    if (Test-Path -LiteralPath $browserFixture) {
        Copy-Item -LiteralPath $browserFixture -Destination (Join-Path $destination 'browser-fixture.json')
        $fixtureHash = (Get-FileHash -LiteralPath $browserFixture).Hash.ToLowerInvariant()
    }
    if ($lockProcess.HasExited -or ([DateTime]::UtcNow - $lockedAt).TotalSeconds -gt 220) {
        throw 'Backup read-lock window expired; snapshot cannot be accepted.'
    }
    $manifest = [ordered]@{
        format = 'adtek-container-backup-v1'; runId = $id.ToString(); sourceProject = 'adtek-platform-sn-container-test'; sourceDatabase = 'adtek_sn_container'
        sourceIdentity = $identity; startedAtUtc = $started.ToString('o'); lockedAtUtc = $lockedAt.ToString('o'); completedAtUtc = [DateTime]::UtcNow.ToString('o')
        apiImage = $apiImage; webImage = $webImage
        quiescence = 'web/api stopped + MySQL global read lock'; redisSessionsIncluded = $false
        database = @{file = 'database.sql'; bytes = (Get-Item -LiteralPath $dump).Length.ToString(); sha256 = (Get-FileHash -LiteralPath $dump).Hash.ToLowerInvariant() }
        fileArchive = @{file = 'files.tar'; bytes = (Get-Item -LiteralPath $tar).Length.ToString(); sha256 = (Get-FileHash -LiteralPath $tar).Hash.ToLowerInvariant() }
        files = $files; restoreVerified = $false
        browserFixtureSha256 = $fixtureHash
    }
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8
    Write-Output "Backup created: deploy/data/backups/$($id.ToString()); files=$($files.Count). Restore verification still required."
}
finally {
    if ($connectionId -match '^[0-9]+$') {
        try {
            [void](Invoke-AdtekContainerSql $docker $mysql 'adtek_sn_container' "KILL CONNECTION $connectionId;")
        }
        catch {
            Write-Warning 'Read-lock release unconfirmed; its server-side 240-second limit remains.'
        }
    }
    if ($lockProcess) {
        if (!$lockProcess.HasExited) {
            [void]$lockProcess.WaitForExit(3000)
        }
        $lockProcess.Dispose()
    }
    if ($stopped) {
        # 只恢复本次停止的原容器；up可能跟随刚构建的同名镜像重建，意外把备份操作变成升级。
        & $docker compose -f $compose start --wait --wait-timeout 120 api web
        if ($LASTEXITCODE -ne 0) {
            throw 'Backup finished or failed, but source services did not resume; inspect container-test status.'
        }
        foreach ($original in @(@($api, $apiImage), @($web, $webImage))) {
            $runningImage = (& $docker inspect --format '{{.Image}}' $original[0]).Trim()
            if ($LASTEXITCODE -ne 0 -or $runningImage -cne $original[1]) {
                throw 'Backup service image changed unexpectedly.'
            }
        }
    }
}
