[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupRunId,
    [Parameter(Mandatory)][string]$RestoreRunId,
    [Parameter(Mandatory)][string]$Month,
    [string]$RunId,
    [ValidateRange(1, 1000000)][int]$RowLimit = 100000,
    [switch]$Apply
)
# 仅独立container-test。默认dry-run；完整证明只读挂载，Java再次校验文件和当前数据库身份/锁定内容。
# apply会移除证明覆盖的整月在线审计，永久归档保留；同RunId可恢复，不对开发库或生产库执行。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'DockerEnvironment.ps1')
$docker = Get-AdtekDocker
$projectRoot = Split-Path -Parent $PSScriptRoot
foreach ($id in @($BackupRunId, $RestoreRunId)) {
    $parsed = [Guid]::Empty
    if (![Guid]::TryParseExact($id, 'D', [ref]$parsed) -or $parsed.ToString() -cne $id) {
        throw 'Canonical backup and restore UUIDs required.'
    }
}
if ($Month -notmatch '^\d{4}-(0[1-9]|1[0-2])$') {
    throw 'Month must be yyyy-MM.'
}
if (!$RunId) {
    if ($Apply) {
        throw 'Apply requires a persisted RunId.'
    }; $RunId = [Guid]::NewGuid().ToString()
}
$parsed = [Guid]::Empty
if (![Guid]::TryParseExact($RunId, 'D', [ref]$parsed) -or $parsed.ToString() -cne $RunId) {
    throw 'Canonical RunId required.'
}
$compose = Join-Path $projectRoot 'deploy/compose.container-test.yml'
$proofRoot = Join-Path $projectRoot 'deploy/data/backups'
$arguments = @('compose', '-f', $compose, 'run', '--rm', '--no-deps', '--volume', "${proofRoot}:/backup:ro", '--env', 'ADTEK_MAINT_PROOF_ROOT=/backup', 'maintenance', '--maintenance', '--action', 'ARCHIVE_FINALIZE', '--run-id', $RunId, '--month', $Month, '--row-limit', "$RowLimit", '--proof-directory', "$BackupRunId/restore-$RestoreRunId")
if ($Apply) {
    $arguments += '--apply'
}
& $docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Archive finalization rejected or failed; retain RunId=$RunId and all proof files."
}
