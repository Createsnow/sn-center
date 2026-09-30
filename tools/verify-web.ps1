[CmdletBinding()]
param([switch]$Browser)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$startedAt = [DateTime]::UtcNow
foreach ($task in @('api:check', 'lint', 'format:check', 'test', 'build')) {
    & (Join-Path $PSScriptRoot 'pnpm.ps1') $task
    if ($LASTEXITCODE -ne 0) { throw "Frontend $task failed: $LASTEXITCODE" }
}
$unitFile = Get-Item -LiteralPath (Join-Path $projectRoot 'frontend/test-results/unit.json')
$unit = Get-Content -LiteralPath $unitFile.FullName -Raw | ConvertFrom-Json
if ($unitFile.LastWriteTimeUtc -lt $startedAt -or $unit.numTotalTests -lt 1 -or $unit.numFailedTests -gt 0 -or $unit.numPendingTests -gt 0 -or -not $unit.success) {
    throw 'Frontend unit discovery/result gate failed.'
}
Write-Output "frontend-unit discovered=$($unit.numTotalTests) failures=$($unit.numFailedTests) skipped=$($unit.numPendingTests)"
if ($Browser) {
    & (Join-Path $PSScriptRoot 'pnpm.ps1') 'test:e2e'
    if ($LASTEXITCODE -ne 0) { throw "Browser tests failed: $LASTEXITCODE" }
    $browserFile = Get-Item -LiteralPath (Join-Path $projectRoot 'frontend/test-results/browser.json')
    $browserReport = Get-Content -LiteralPath $browserFile.FullName -Raw | ConvertFrom-Json
    if ($browserFile.LastWriteTimeUtc -lt $startedAt -or $browserReport.stats.expected -lt 1 -or $browserReport.stats.unexpected -gt 0 -or $browserReport.stats.skipped -gt 0 -or $browserReport.stats.flaky -gt 0) {
        throw 'Browser discovery/result gate failed.'
    }
    Write-Output "browser discovered=$($browserReport.stats.expected) failures=$($browserReport.stats.unexpected) skipped=$($browserReport.stats.skipped)"
}
