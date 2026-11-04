[CmdletBinding()]
param([switch]$Integration)

# Maven 退出码和本次产生的 XML 报告同时验收，避免零发现或旧报告冒充通过。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$startedAt = [DateTime]::UtcNow
$arguments = @('-B', '-ntp')
if ($Integration) {
    $arguments += '-Pintegration'
}
$arguments += 'verify'
& (Join-Path $PSScriptRoot 'maven.ps1') @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Maven verification failed: $LASTEXITCODE"
}
foreach ($reportKind in $(if ($Integration) {
            @('surefire', 'failsafe')
        }
        else {
            @('surefire')
        })) {
    # 骨架阶段尚无 *IT.java 时明确报告 0 个集成测试源，而不是把空运行当作通过；一旦存在源文件即强制发现门禁。
    if ($reportKind -eq 'failsafe' -and -not (Get-ChildItem -Path (Join-Path $projectRoot 'backend/*/src/test/java') -Recurse -File -Filter '*IT.java' -ErrorAction SilentlyContinue)) {
        Write-Warning 'failsafe: no *IT.java sources yet; add Testcontainers integration tests with the first database feature.'
        continue
    }
    $reports = Get-ChildItem -Path (Join-Path $projectRoot "backend/*/target/$reportKind-reports/TEST-*.xml") -File |
        Where-Object { $_.LastWriteTimeUtc -ge $startedAt }
    if (-not $reports) {
        throw "No fresh $reportKind test reports were discovered."
    }
    $count = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($report in $reports) {
        $suite = ([xml](Get-Content -LiteralPath $report.FullName -Raw)).testsuite
        $count += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
    }
    Write-Output "$reportKind discovered=$count failures=$failures errors=$errors skipped=$skipped"
    if ($count -eq 0 -or $failures -gt 0 -or $errors -gt 0 -or $skipped -gt 0) {
        throw "$reportKind verification did not pass the discovery/result gate."
    }
}
