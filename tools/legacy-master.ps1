[CmdletBinding()]
param([Parameter(Mandatory)][string]$SnapshotId,[Parameter(Mandatory)][string]$RunId,[switch]$Apply)
# RunId必须已完成staging；主数据与停用身份原子写入，不启用任何账号，不代表完整业务迁移。
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
    $arguments=@('-jar',(Join-Path $projectRoot 'backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar'),'--legacy-master','--run-id',$RunId,'--snapshot-id',$SnapshotId)
    if($Apply) { $arguments+='--apply' }
    & $java @arguments
    if($LASTEXITCODE -ne 0) { throw 'Legacy master phase failed. No accounts activated; inspect the sanitized error code.' }
} finally { $env:ADTEK_LEGACY_SNAPSHOT_ROOT=$previous }
