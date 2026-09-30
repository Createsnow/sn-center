[CmdletBinding()]
param([Parameter(Mandatory)][string]$SnapshotId,[Parameter(Mandatory)][string]$RunId,[switch]$Apply)
# 最大25000枚的PRINTED IMPORT库存，必须已完成同号主数据；整个库存阶段原子提交，账号保持停用。
$ErrorActionPreference='Stop'
foreach($key in @('ADTEK_LEGACY_TARGET_URL','ADTEK_LEGACY_TARGET_USER','ADTEK_LEGACY_TARGET_PASSWORD','ADTEK_LEGACY_TARGET_SCHEMA')) {
    if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key,'Process'))) { throw "Missing explicit $key" }
}
foreach($id in @($SnapshotId,$RunId)) {
    $parsed=[Guid]::Empty
    if(![Guid]::TryParseExact($id,'D',[ref]$parsed) -or $parsed.ToString() -cne $id) { throw 'Canonical SnapshotId and RunId required.' }
}
$projectRoot=Split-Path -Parent $PSScriptRoot
$java=Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Directory | ForEach-Object { Join-Path $_.FullName 'bin/java.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if(!$java) { throw 'Project JDK missing.' }
$previous=$env:ADTEK_LEGACY_SNAPSHOT_ROOT
try {
    $env:ADTEK_LEGACY_SNAPSHOT_ROOT=Join-Path $projectRoot 'deploy/data/legacy-snapshots'
    $arguments=@('-Xmx768m','-jar',(Join-Path $projectRoot 'backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar'),'--legacy-inventory','--run-id',$RunId,'--snapshot-id',$SnapshotId)
    if($Apply) { $arguments+='--apply' }
    & $java @arguments
    if($LASTEXITCODE -ne 0) { throw 'Legacy inventory phase failed. Accounts remain inactive; preserve source and inspect the sanitized error code.' }
} finally { $env:ADTEK_LEGACY_SNAPSHOT_ROOT=$previous }
