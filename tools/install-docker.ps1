[CmdletBinding()]
param()

# 用户显式授权的本机安装入口。官方安装包必须通过 Authenticode 校验；不重启电脑。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$downloadRoot = Join-Path $projectRoot '.tools/downloads'
$runtimePath = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Force -Path $downloadRoot, $runtimePath | Out-Null
$installerPath = Join-Path $downloadRoot 'Docker Desktop Installer.exe'
if (-not (Test-Path -LiteralPath $installerPath)) {
    Write-Output 'Downloading the official Docker Desktop Windows x64 installer.'
    Invoke-WebRequest -Uri 'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe' -OutFile $installerPath -TimeoutSec 900
}
$signature = Get-AuthenticodeSignature -LiteralPath $installerPath
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Docker Inc') {
    throw "Docker installer signature verification failed: $($signature.Status)"
}
Write-Output "Verified Docker installer: $($signature.SignerCertificate.Subject)"
$pwshPath = (Get-Process -Id $PID).Path
$wslScript = Join-Path $PSScriptRoot 'enable-wsl.ps1'
Write-Output 'Enabling WSL 2 prerequisites. Windows may display a UAC authorization prompt.'
$wslProcess = Start-Process -FilePath $pwshPath -Verb RunAs -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile', '-File', ('"' + $wslScript + '"'))
$wslProcess.WaitForExit()
if ($wslProcess.ExitCode -ne 0) {
    throw "WSL setup failed; inspect tools/.runtime/wsl-result.json (exit $($wslProcess.ExitCode))."
}
$wslResult = Get-Content -LiteralPath (Join-Path $runtimePath 'wsl-result.json') -Raw | ConvertFrom-Json
if (-not $wslResult.success) {
    throw $wslResult.error
}
$desktopPath = Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/Docker Desktop.exe'
if (-not (Test-Path -LiteralPath $desktopPath)) {
    Write-Output 'Installing Docker Desktop for the current user with the WSL 2 backend.'
    $installProcess = Start-Process -FilePath $installerPath -WindowStyle Hidden -PassThru -Wait -ArgumentList @('install', '--user', '--quiet', '--accept-license', '--backend=wsl-2', '--no-windows-containers')
    if ($installProcess.ExitCode -notin @(0, 3010)) {
        throw "Docker installer returned $($installProcess.ExitCode)"
    }
    if ($installProcess.ExitCode -eq 3010) {
        $wslResult.restartNeeded = $true
    }
}
[ordered]@{
    installedAt = (Get-Date).ToString('o')
    installerSha256 = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash
    installerVersion = (Get-Item -LiteralPath $installerPath).VersionInfo.ProductVersion
    desktopPath = $desktopPath
    restartNeeded = $wslResult.restartNeeded
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimePath 'docker-install-result.json') -Encoding utf8
if ($wslResult.restartNeeded) {
    Write-Output 'Installation completed. A Windows restart is required before Linux containers can run. No restart has been requested.'
}
else {
    Write-Output "Installation completed: $desktopPath"
}
