[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'pnpm.ps1') dev
exit $LASTEXITCODE
