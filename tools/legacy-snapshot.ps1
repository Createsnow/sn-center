[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$SourceSchema,
    [Parameter(Mandatory)][string]$SourceZone,
    [ValidateSet('sn-python-repository-v1')][string]$SchemaVersion = 'sn-python-repository-v1',
    [ValidateRange(1, 150000000)][long]$RowLimit = 100000,
    [ValidateRange(1024, 1099511627776)][long]$ByteLimit = 1073741824
)
# 仅从显式环境变量中的SELECT源账号读取。19表原始文件含口令哈希，只写本工程ACL保护目录，不打印内容。
# SourceZone必须来自旧实际部署证据；不能用本机时区代替。完整manifest出现前不允许阶段导入。
$ErrorActionPreference = 'Stop'
foreach ($key in @('ADTEK_LEGACY_SOURCE_URL', 'ADTEK_LEGACY_SOURCE_USER', 'ADTEK_LEGACY_SOURCE_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) {
        throw "Missing explicit $key"
    }
}
$parsed = [Guid]::Empty
if (![Guid]::TryParseExact($RunId, 'D', [ref]$parsed) -or $parsed.ToString() -cne $RunId) {
    throw 'Canonical RunId required.'
}
if ($SourceSchema -notmatch '^[a-z][a-z0-9_]{0,63}$') {
    throw 'Explicit source schema required.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$root = Join-Path $projectRoot 'deploy/data/legacy-snapshots'
New-Item -ItemType Directory -Path $root -Force | Out-Null
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $root /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' /Q | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Cannot restrict legacy snapshot permissions.'
}
$java = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Directory | ForEach-Object { Join-Path $_.FullName 'bin/java.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$java) {
    throw 'Project JDK missing; run bootstrap-build first.'
}
$previous = $env:ADTEK_LEGACY_SNAPSHOT_ROOT
try {
    $env:ADTEK_LEGACY_SNAPSHOT_ROOT = $root
    & $java -jar (Join-Path $projectRoot 'backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar') --legacy-snapshot --run-id $RunId --source-schema $SourceSchema --source-zone $SourceZone --schema-version $SchemaVersion --row-limit "$RowLimit" --byte-limit "$ByteLimit"
    if ($LASTEXITCODE -ne 0) {
        throw 'Legacy snapshot failed; incomplete files retained, never treat them as a complete snapshot.'
    }
}
finally {
    $env:ADTEK_LEGACY_SNAPSHOT_ROOT = $previous
}
