[CmdletBinding()]
param()
# 固定本机来源；后端启动成功后导出，不接受来自不明远端的生成输入。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$schema = Join-Path $projectRoot 'frontend/openapi/adtek-sn.json'
Invoke-WebRequest -Uri 'http://127.0.0.1:18080/v3/api-docs' -OutFile $schema -TimeoutSec 20
& (Join-Path $PSScriptRoot 'pnpm.ps1') 'api:generate'
exit $LASTEXITCODE
