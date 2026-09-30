[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SnapshotId,
    [string]$RunId,
    [ValidateRange(1,1000)][int]$ChunkSize=500,
    [switch]$Apply
)
# 默认只读核验。apply只写V14 staging，不激活业务；保存运行号以核验已提交块并续传，不能换源续跑。
$ErrorActionPreference='Stop'
foreach($key in @('ADTEK_LEGACY_TARGET_URL','ADTEK_LEGACY_TARGET_USER','ADTEK_LEGACY_TARGET_PASSWORD','ADTEK_LEGACY_TARGET_SCHEMA')) {
    if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key,'Process'))) { throw "Missing explicit $key" }
}
if(!$RunId) { if($Apply) { throw 'Apply requires a persisted RunId.' }; $RunId=[Guid]::NewGuid().ToString() }
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
    $arguments=@('-jar',(Join-Path $projectRoot 'backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar'),'--legacy-stage','--run-id',$RunId,'--snapshot-id',$SnapshotId,'--chunk-size',"$ChunkSize")
    if($Apply) { $arguments+='--apply' }
    & $java @arguments
    if($LASTEXITCODE -ne 0) { throw "Legacy staging failed. Preserve RunId=$RunId and original snapshot; prior committed chunks remain." }
} finally { $env:ADTEK_LEGACY_SNAPSHOT_ROOT=$previous }
