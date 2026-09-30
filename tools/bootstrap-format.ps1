[CmdletBinding()]
param()

# 固定格式工具下载到工程缓存，不修改全局模块或PowerShell配置。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$moduleRoot = Join-Path $projectRoot '.tools/powershell'
$manifest = Join-Path $moduleRoot 'PSScriptAnalyzer/1.25.0/PSScriptAnalyzer.psd1'
if (-not (Test-Path -LiteralPath $manifest)) {
    Save-Module -Name PSScriptAnalyzer -RequiredVersion 1.25.0 -Repository PSGallery -Path $moduleRoot -Force
}
Import-Module $manifest -Force
Write-Output 'PSScriptAnalyzer 1.25.0 is ready in the project cache.'
