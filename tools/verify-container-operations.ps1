[CmdletBinding()]
param()
# 验证已完成恢复/归档的明确演练夹具，不创建或删除业务行；调度仅维护该独立schema。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'DockerEnvironment.ps1')
. (Join-Path $PSScriptRoot 'BackupSupport.ps1')
$docker = Get-AdtekDocker
$projectRoot = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $projectRoot 'deploy/compose.container-test.yml'
$mysql = (& $docker compose -f $compose ps -q mysql).Trim()
$project = (& $docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $mysql).Trim()
if ($project -ne 'adtek-platform-sn-container-test') {
    throw 'Unexpected test database project.'
}
$fixture = 'ae074d83-846f-4dca-b125-67e609f40201'
$counts = Invoke-AdtekContainerSql $docker $mysql 'adtek_sn_container' "SELECT CONCAT((SELECT COUNT(*) FROM sn_audit WHERE id='$fixture'),':',(SELECT COUNT(*) FROM sn_audit_archive WHERE id='$fixture'));"
if ($counts -ne '0:1') {
    throw 'Expected exactly one archived synthetic fixture and no online copy.'
}
& (Join-Path $PSScriptRoot 'finalize-container-archive.ps1') -BackupRunId 8f191650-f251-4b14-aab8-a1b22e117054 -RestoreRunId 938a69f3-d632-4138-862e-fd2c858fe13c -Month 2026-08 -RunId 5c64018c-5454-4fd2-b71e-15e3ee4de87d -Apply
# 同一天两次进程启动产生相同runId，第二次必须返回原成功记录。
$runs = @()
foreach ($attempt in 1..2) {
    $result = @(& $docker compose -f $compose run --rm --no-deps scheduler --maintenance-scheduler --once --apply)
    if ($LASTEXITCODE -ne 0) {
        throw 'Scheduled maintenance one-shot failed.'
    }
    $parsed = @($result | ForEach-Object { $_ | ConvertFrom-Json })
    if ($parsed.Count -ne 2 -or @($parsed | Where-Object { $_.state -ne 'SUCCEEDED' -or !$_.applied }).Count) {
        throw 'Unexpected scheduler result.'
    }
    $runs += , ($parsed | ConvertTo-Json -Depth 5 -Compress)
}
if ($runs[0] -cne $runs[1]) {
    throw 'Scheduler retry did not replay the original result.'
}
# API运行账号不能DDL；在事务外用CREATE尝试，若错误获准则立即失败并留下对象用于诊断。
$shell = 'export MYSQL_PWD="$(cat /run/secrets/mysql_app_password)"; exec mysql --protocol=socket -uadtek_app --batch --skip-column-names adtek_sn_container'
$process = New-AdtekDockerProcess $docker @('exec', '-i', $mysql, 'sh', '-c', $shell)
$out = $process.StandardOutput.ReadToEndAsync(); $err = $process.StandardError.ReadToEndAsync()
try {
    $process.StandardInput.WriteLine('CREATE TABLE it_runtime_ddl_must_be_denied(id INT);'); $process.StandardInput.Close()
    if (!$process.WaitForExit(15000)) {
        $process.Kill($true); throw 'Runtime permission probe timed out.'
    }
    [void]$out.GetAwaiter().GetResult(); $errorText = $err.GetAwaiter().GetResult()
    if ($process.ExitCode -eq 0 -or $errorText -notmatch 'ERROR 1142') {
        throw 'Runtime DDL denial was not demonstrated.'
    }
}
finally {
    $process.Dispose()
}
$nginx = (& $docker compose -f $compose exec -T web nginx -v 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Nginx version probe failed.'
}
$summary = [ordered]@{checkedAtUtc = [DateTime]::UtcNow.ToString('o'); project = $project; syntheticAuditOnline = '0'; syntheticAuditArchive = '1'; finalizeReplay = $true; schedulerReplay = $true; schedulerResults = ($runs[0] | ConvertFrom-Json); runtimeDdlDenied = $true; nginx = $nginx }
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $projectRoot 'tools/.runtime/s7-operations.json') -Encoding utf8
Write-Output 'Archive replay, scheduled maintenance replay and runtime DDL denial verified.'
