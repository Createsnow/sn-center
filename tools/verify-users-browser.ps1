[CmdletBinding()]
param()
# 使用 Testcontainers 隔离设施。缺 Node/Edge/Docker 时失败，不回退 mock 或跳过。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$startedAt = [DateTime]::UtcNow
& (Join-Path $PSScriptRoot 'maven.ps1') -B -ntp '-Pbrowser,acceptance' verify
if ($LASTEXITCODE -ne 0) {
    throw "Isolated users browser verification failed: $LASTEXITCODE"
}
$report = Get-Item -LiteralPath (Join-Path $projectRoot 'backend/adtek-server/target/failsafe-reports/TEST-com.adtek.server.UsersBrowserE2E.xml')
if ($report.LastWriteTimeUtc -lt $startedAt) {
    throw 'No fresh backend browser harness report.'
}
$suite = ([xml](Get-Content -LiteralPath $report.FullName -Raw)).testsuite
if ([int]$suite.tests -lt 1 -or [int]$suite.failures -gt 0 -or [int]$suite.errors -gt 0 -or [int]$suite.skipped -gt 0) {
    throw 'Browser harness discovery/result gate failed.'
}
$browserFile = Get-Item -LiteralPath (Join-Path $projectRoot 'frontend/test-results/browser-users.json')
if ($browserFile.LastWriteTimeUtc -lt $startedAt) {
    throw 'No fresh Playwright users report.'
}
$browserReport = Get-Content -LiteralPath $browserFile.FullName -Raw | ConvertFrom-Json
if ($browserReport.stats.expected -ne 17 -or $browserReport.stats.unexpected -gt 0 -or $browserReport.stats.skipped -gt 0 -or $browserReport.stats.flaky -gt 0) {
    throw 'Playwright isolated auth/users/configuration/audit/export/SN discovery/result gate failed.'
}
Write-Output "users browser discovered=$($browserReport.stats.expected) failures=0 skipped=0; isolated MySQL/Redis and backend harness passed."
