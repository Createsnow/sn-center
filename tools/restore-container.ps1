[CmdletBinding()]
param([Parameter(Mandatory)][string]$BackupRunId,[string]$RestoreRunId=[Guid]::NewGuid().ToString())
# 只向全新Compose项目/卷恢复；同schema名称不代表同数据库实例。拒绝已有目标或摘要不符。
# 不覆盖源库/源文件，也不自动销毁演练目标。成功证明需SQL、全部文件和HTTPS下载同时通过。
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'DockerEnvironment.ps1')
. (Join-Path $PSScriptRoot 'BackupSupport.ps1')
$docker=Get-AdtekDocker;$projectRoot=Split-Path -Parent $PSScriptRoot
$backupId=[Guid]::Empty;$restoreId=[Guid]::Empty
if(![Guid]::TryParseExact($BackupRunId,'D',[ref]$backupId) -or ![Guid]::TryParseExact($RestoreRunId,'D',[ref]$restoreId)) { throw 'Backup and restore IDs must be UUID.' }
$backup=Join-Path $projectRoot "deploy/data/backups/$($backupId.ToString())"
$manifestPath=Join-Path $backup 'manifest.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if($manifest.format -ne 'adtek-container-backup-v1' -or $manifest.runId -ne $backupId.ToString() -or $manifest.sourceProject -ne 'adtek-platform-sn-container-test' -or $manifest.sourceDatabase -ne 'adtek_sn_container') { throw 'Unsupported backup source/format.' }
foreach($pair in @(@('database.sql',$manifest.database),@('files.tar',$manifest.fileArchive))) {
    $file=Join-Path $backup $pair[0];$entry=$pair[1]
    if($entry.file -ne $pair[0] -or (Get-Item -LiteralPath $file).Length.ToString() -ne $entry.bytes -or (Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -ne $entry.sha256) { throw 'Backup integrity check failed.' }
}
$sourceFiles=@(Get-AdtekTarManifest (Join-Path $backup 'files.tar'))
if((ConvertTo-Json -InputObject $sourceFiles -Depth 5 -Compress) -ne (ConvertTo-Json -InputObject @($manifest.files) -Depth 5 -Compress)) { throw 'Backup file inventory differs.' }
foreach($image in @($manifest.apiImage,$manifest.webImage)) { if($image -notmatch '^sha256:[0-9a-f]{64}$') { throw 'Backup application images are not pinned.' } }
$fixture=Join-Path $backup 'browser-fixture.json'
if(!$manifest.browserFixtureSha256 -or (Get-FileHash -LiteralPath $fixture).Hash.ToLowerInvariant() -ne $manifest.browserFixtureSha256) { throw 'Verified browser fixture required for this isolated restore drill.' }
$targetProject='adtek-sn-restore-'+$restoreId.ToString('N').Substring(0,12)
$target=Join-Path $backup ('restore-'+$restoreId.ToString())
if(Test-Path -LiteralPath $target) { throw 'Restore evidence destination exists; preserve it and use a new run ID.' }
$existing=@(& $docker ps -aq --filter "label=com.docker.compose.project=$targetProject")
$volumes=@(& $docker volume ls -q --filter "label=com.docker.compose.project=$targetProject")
if($LASTEXITCODE -ne 0 -or $existing.Count -gt 0 -or $volumes.Count -gt 0) { throw 'Restore target is not empty.' }
$sourceCompose=Join-Path $projectRoot 'deploy/compose.container-test.yml'
$configuration=(& $docker compose -f $sourceCompose --profile operations config --format json | Out-String | ConvertFrom-Json -AsHashtable)
if($LASTEXITCODE -ne 0) { throw 'Cannot load source deployment configuration.' }
$configuration.name=$targetProject
foreach($name in @($configuration.services.Keys)) { if($name -notin @('mysql','redis','api','web','maintenance')) { $configuration.services.Remove($name) } }
$configuration.services.api.Remove('build');$configuration.services.web.Remove('build')
$configuration.services.maintenance.Remove('build');$configuration.services.maintenance.image=$manifest.apiImage
$configuration.services.api.image=$manifest.apiImage;$configuration.services.web.image=$manifest.webImage
$configuration.services.api.depends_on=@{mysql=@{condition='service_healthy'};redis=@{condition='service_healthy'}}
$configuration.services.web.ports=@(@{target=8443;published='18444';host_ip='127.0.0.1';protocol='tcp'})
$configuration.volumes=@{mysql_data=@{};redis_data=@{};files_data=@{}}
$configuration.networks=@{default=@{}}
New-Item -ItemType Directory -Path $target | Out-Null
$compose=Join-Path $target 'compose.restore.json'
$configuration | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $compose -Encoding utf8
& $docker compose -p $targetProject -f $compose config --quiet
if($LASTEXITCODE -ne 0) { throw 'Isolated restore configuration invalid.' }
$started=[DateTime]::UtcNow
& $docker compose -p $targetProject -f $compose up -d --wait --wait-timeout 180 mysql redis
if($LASTEXITCODE -ne 0) { throw 'Isolated restore dependencies failed; target preserved.' }
$mysql=(& $docker compose -p $targetProject -f $compose ps -q mysql).Trim()
$identity=Invoke-AdtekContainerSql $docker $mysql 'adtek_sn_container' 'SELECT CONCAT(@@server_uuid,CHAR(9),@@version,CHAR(9),DATABASE());'
if(($identity -split "`t")[0] -eq ($manifest.sourceIdentity -split "`t")[0]) { throw 'Refusing restore into source database instance.' }
$count=Invoke-AdtekContainerSql $docker $mysql 'adtek_sn_container' 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();'
if($count -ne '0') { throw 'Refusing restore into a nonempty schema.' }
$importShell='export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql --protocol=socket -uroot --default-character-set=utf8mb4 --skip-reconnect adtek_sn_container'
Invoke-AdtekDockerInput $docker @('exec','-i',$mysql,'sh','-c',$importShell) (Join-Path $backup 'database.sql') 120
$dump=Join-Path $target 'restored.sql'
$dumpShell='export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysqldump --protocol=socket -uroot --single-transaction --skip-lock-tables --skip-comments --order-by-primary --hex-blob --set-gtid-purged=OFF --no-tablespaces --routines --events --triggers --default-character-set=utf8mb4 adtek_sn_container'
Invoke-AdtekDockerOutput $docker @('exec',$mysql,'sh','-c',$dumpShell) $dump 120
$restoredSqlHash=(Get-FileHash -LiteralPath $dump).Hash.ToLowerInvariant()
$canonicalHash=Get-AdtekCanonicalSqlDigest (Join-Path $backup 'database.sql')
if((Get-AdtekCanonicalSqlDigest $dump) -ne $canonicalHash) { throw 'Restored database canonical dump does not match source snapshot.' }
$archiveProof=@()
$months=Invoke-AdtekContainerSql $docker $mysql 'adtek_sn_container' "SELECT DISTINCT DATE_FORMAT(created_at,'%Y-%m') FROM sn_audit_archive ORDER BY 1;"
foreach($month in ($months -split "`n" | Where-Object { $_ })) {
    $month=$month.Trim()
    if($month -notmatch '^\d{4}-(0[1-9]|1[0-2])$') { throw 'Unexpected archive month.' }
    $archiveResult=(& $docker compose -p $targetProject -f $compose run --rm --no-deps maintenance --maintenance --action ARCHIVE_COPY --month $month | Out-String | ConvertFrom-Json)
    if($LASTEXITCODE -ne 0 -or $archiveResult.applied -ne $false -or $archiveResult.state -ne 'DRY_RUN') { throw 'Restored archive fingerprint failed.' }
    $startDate=[DateTime]::ParseExact($month+'-01','yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture)
    $endDate=$startDate.AddMonths(1).ToString('yyyy-MM-dd')
    $archiveCount=Invoke-AdtekContainerSql $docker $mysql 'adtek_sn_container' "SELECT COUNT(*) FROM sn_audit_archive WHERE created_at>='$month-01' AND created_at<'$endDate';"
    $archiveProof+=@([ordered]@{month=$month;onlineRows=$archiveResult.affectedRows;onlineDigest=$archiveResult.sourceDigest;archiveRows=$archiveCount;archiveDigest=$archiveResult.archiveDigest})
}
& $docker compose -p $targetProject -f $compose create api
if($LASTEXITCODE -ne 0) { throw 'Cannot create stopped restore API/file volume.' }
$api=(& $docker compose -p $targetProject -f $compose ps -aq api).Trim()
if($api -notmatch '^[0-9a-f]{64}$') { throw 'Missing isolated restore API.' }
Invoke-AdtekDockerInput $docker @('cp','-a','-',"${api}:/app/var/files") (Join-Path $backup 'files.tar') 60
$restoredTar=Join-Path $target 'restored-files.tar'
Invoke-AdtekDockerOutput $docker @('cp',"${api}:/app/var/files/.",'-') $restoredTar 60
$restoredFiles=@(Get-AdtekTarManifest $restoredTar)
if((ConvertTo-Json -InputObject $restoredFiles -Depth 5 -Compress) -ne (ConvertTo-Json -InputObject $sourceFiles -Depth 5 -Compress)) { throw 'Restored file content differs from backup.' }
& $docker compose -p $targetProject -f $compose up -d --wait --wait-timeout 180 api web
if($LASTEXITCODE -ne 0) { throw 'Restored runtime is not healthy.' }
Copy-Item -LiteralPath $fixture -Destination (Join-Path $projectRoot 'frontend/test-results/container-fixture.json')
$previousUrl=$env:ADTEK_CONTAINER_URL;$previousMode=$env:ADTEK_CONTAINER_VERIFY_RESTORE
try {
    $env:ADTEK_CONTAINER_URL='https://127.0.0.1:18444';$env:ADTEK_CONTAINER_VERIFY_RESTORE='1'
    & (Join-Path $PSScriptRoot 'pnpm.ps1') 'test:container'
    if($LASTEXITCODE -ne 0) { throw 'Restored HTTPS business/file workflow failed.' }
} finally { $env:ADTEK_CONTAINER_URL=$previousUrl;$env:ADTEK_CONTAINER_VERIFY_RESTORE=$previousMode }
$browserFile=Join-Path $projectRoot 'frontend/test-results/container-browser.json'
$browser=Get-Content -LiteralPath $browserFile -Raw | ConvertFrom-Json
if($browser.stats.expected -ne 1 -or $browser.stats.unexpected -ne 0 -or $browser.stats.skipped -ne 0 -or $browser.stats.flaky -ne 0) { throw 'Restore browser evidence incomplete.' }
Copy-Item -LiteralPath $browserFile -Destination (Join-Path $target 'browser.json')
$proof=[ordered]@{
    format='adtek-container-restore-proof-v1';backupRunId=$backupId.ToString();restoreRunId=$restoreId.ToString();targetProject=$targetProject
    backupManifestSha256=(Get-FileHash -LiteralPath $manifestPath).Hash.ToLowerInvariant();targetIdentity=$identity
    startedAtUtc=$started.ToString('o');completedAtUtc=[DateTime]::UtcNow.ToString('o');elapsedSeconds=[Math]::Round(([DateTime]::UtcNow-$started).TotalSeconds,3)
    canonicalSqlIdentical=$true;databaseSha256=$manifest.database.sha256;restoredDumpSha256=$restoredSqlHash
    canonicalSqlSha256=$canonicalHash;canonicalAlgorithm='mysql-dump-utf8mb4-redundancy-v1';fileCount=$restoredFiles.Count;filesIdentical=$true
    auditArchives=$archiveProof
    browser=@{passed=1;failed=0;skipped=0;flaky=0;reportSha256=(Get-FileHash -LiteralPath $browserFile).Hash.ToLowerInvariant()}
    sourceUntouched=$true;restoreVerified=$true;url='https://127.0.0.1:18444'
}
$proof | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $target 'restore-proof.json') -Encoding utf8
Write-Output "Restore verified: $targetProject; SQL and $($restoredFiles.Count) files identical; HTTPS original downloads verified. Evidence: $target"
