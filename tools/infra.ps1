[CmdletBinding()]
param([ValidateSet('start', 'status', 'stop', 'check')][string]$Action = 'status')

# 仅操作固定 Compose 项目。stop 保留数据；不提供自动删卷/重置入口。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $projectRoot 'deploy/compose.test.yml'
$secretsPath = Join-Path $projectRoot 'deploy/.secrets'
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$dockerPath = if ($dockerCommand) {
    $dockerCommand.Source
}
else {
    @(
        (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),
        'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
    ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $dockerPath) {
    throw 'Docker CLI not found. Run tools/install-docker.ps1 first.'
}
if ($Action -eq 'start') {
    New-Item -ItemType Directory -Force -Path $secretsPath | Out-Null
    foreach ($name in @('mysql-root-password', 'mysql-app-password', 'mysql-migration-password', 'redis-password', 'jwt-signing-key')) {
        $secretFile = Join-Path $secretsPath $name
        if (-not (Test-Path -LiteralPath $secretFile)) {
            $secretValue = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
            [IO.File]::WriteAllText($secretFile, $secretValue, [Text.UTF8Encoding]::new($false))
        }
    }
    # 凭据只授权当前账号与 SYSTEM 读取；输出不包含秘密值。
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $secretsPath /inheritance:r /grant:r "*${currentSid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' /Q | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to protect local test secrets.'
    }
}
& $dockerPath compose -f $composePath config --quiet
if ($LASTEXITCODE -ne 0) {
    throw 'Compose configuration invalid or local secrets are missing.'
}
if ($Action -eq 'check') {
    Write-Output 'Compose configuration valid.'; exit 0
}
& $dockerPath info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) {
    throw 'Docker engine is not ready. Start Docker Desktop; restart Windows if WSL installation requested it.'
}
switch ($Action) {
    'start' {
        & $dockerPath compose -f $composePath up -d --wait --wait-timeout 180
    }
    'stop' {
        & $dockerPath compose -f $composePath stop
    }
    'status' {
        & $dockerPath compose -f $composePath ps
    }
}
if ($LASTEXITCODE -ne 0) {
    throw "Compose $Action failed."
}
if ($Action -eq 'start') {
    Write-Output 'MySQL: 127.0.0.1:33306 / adtek_sn_test / adtek_app'
    Write-Output 'Redis: 127.0.0.1:36379; passwords are in deploy/.secrets (not printed).'
}
