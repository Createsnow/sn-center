[CmdletBinding()]
param(
    [ValidateSet('PARTITIONS', 'CLEAN_PREVIEWS', 'ARCHIVE_COPY', 'ARCHIVE_VERIFY', 'ARCHIVE_FINALIZE')][string]$Action = 'PARTITIONS',
    [string]$Month = [DateTime]::UtcNow.ToString('yyyy-MM'),
    [ValidateRange(1, 12)][int]$MonthsAhead = 3,
    [ValidateRange(1, 1000000)][int]$RowLimit = 100000,
    [string]$RunId,
    [string]$ProofDirectory,
    [switch]$Apply
)
# 独立维护入口。默认只读计划；明确四个ADTEK_MAINT_*变量，不读取旧项目配置或回退页面账号。
# apply必须保存运行号；同号同参数用于恢复中断，成功结果重放。分区DDL不支持整体回滚。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
foreach ($key in @('ADTEK_MAINT_DB_URL', 'ADTEK_MAINT_DB_USER', 'ADTEK_MAINT_DB_PASSWORD', 'ADTEK_MAINT_ALLOWED_SCHEMA')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) {
        throw "Missing $key"
    }
}
if ($Month -notmatch '^\d{4}-(0[1-9]|1[0-2])$') {
    throw 'Month must be yyyy-MM'
}
if (!$RunId) {
    if ($Apply) {
        throw 'Apply requires a persisted RunId (UUID).'
    }; $RunId = [Guid]::NewGuid().ToString()
}
$parsed = [Guid]::Empty
if (![Guid]::TryParseExact($RunId, 'D', [ref]$parsed)) {
    throw 'RunId must be a canonical UUID'
}
$jar = Join-Path $projectRoot 'backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) {
    throw 'Build the backend before running maintenance.'
}
$java = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Directory | ForEach-Object { Join-Path $_.FullName 'bin/java.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$java) {
    $java = 'java'
}
$arguments = @('-jar', $jar, '--maintenance', '--action', $Action, '--run-id', $RunId, '--month', $Month, '--months-ahead', "$MonthsAhead", '--row-limit', "$RowLimit")
if ($Apply) {
    $arguments += '--apply'
}
if ($ProofDirectory) {
    $arguments += @('--proof-directory', $ProofDirectory)
}
& $java @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Maintenance failed (exit $LASTEXITCODE); retain RunId=$RunId for diagnosis/recovery."
}
