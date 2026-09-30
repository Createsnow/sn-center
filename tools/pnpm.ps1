[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pnpm = Join-Path $projectRoot '.tools/pnpm/node_modules/pnpm/bin/pnpm.cjs'
if (-not (Test-Path -LiteralPath $pnpm)) { throw 'Run ./tools/bootstrap-build.ps1 first.' }
Push-Location (Join-Path $projectRoot 'frontend')
try {
    & node $pnpm @Arguments
    $pnpmExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $pnpmExitCode
