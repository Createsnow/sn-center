#Requires -RunAsAdministrator
[CmdletBinding()]
param()

# 仅由 install-docker.ps1 的显式安装操作调用；启用虚拟机平台但不自动重启。
$ErrorActionPreference = 'Stop'
$runtimePath = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Force -Path $runtimePath | Out-Null
$result = [ordered]@{ success = $false; restartNeeded = $false; error = $null }
try {
    $feature = Get-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform
    if ($feature.State -ne 'Enabled') {
        $enabled = Enable-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform -All -NoRestart
        $result.restartNeeded = [bool]$enabled.RestartNeeded
    }
    & wsl.exe --install --no-distribution --web-download *> (Join-Path $runtimePath 'wsl-install.log')
    if ($LASTEXITCODE -notin @(0, 3010)) {
        throw "WSL installation returned $LASTEXITCODE; see tools/.runtime/wsl-install.log"
    }
    if ($LASTEXITCODE -eq 3010) {
        $result.restartNeeded = $true
    }
    $result.success = $true
}
catch {
    $result.error = $_.Exception.Message
}
finally {
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimePath 'wsl-result.json') -Encoding utf8
}
if (-not $result.success) {
    exit 1
}
