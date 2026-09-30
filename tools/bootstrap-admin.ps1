[CmdletBinding()]
param([ValidatePattern('^[A-Za-z0-9_-]{2,32}$')][string]$Username = 'admin')

# 只对空测试库初始化一次。既有账号不会被重置；密码只保存到受保护的本地文件。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar'
$jdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Directory | Select-Object -First 1
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Build first: ./tools/verify.ps1'
}
. (Join-Path $PSScriptRoot 'TestEnvironment.ps1')
$previous = Set-AdtekTestEnvironment
$passwordFile = Join-Path $projectRoot 'deploy/.secrets/bootstrap-admin-password'
$previousBootstrapUser = $env:ADTEK_BOOTSTRAP_USERNAME
$previousBootstrapPassword = $env:ADTEK_BOOTSTRAP_PASSWORD
try {
    if (-not (Test-Path -LiteralPath $passwordFile)) {
        $passwordValue = 'Sn9!' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant()
        [IO.File]::WriteAllText($passwordFile, $passwordValue, [Text.UTF8Encoding]::new($false))
    }
    $env:ADTEK_BOOTSTRAP_USERNAME = $Username
    $env:ADTEK_BOOTSTRAP_PASSWORD = [IO.File]::ReadAllText($passwordFile).Trim()
    $env:SPRING_PROFILES_ACTIVE = 'local,bootstrap-admin'
    & (Join-Path $jdk.FullName 'bin/java.exe') '-jar' $jar '--spring.main.web-application-type=none'
    $bootstrapExitCode = $LASTEXITCODE
    if ($bootstrapExitCode -eq 0) {
        Write-Output "Initialized test account '$Username'; initial password: deploy/.secrets/bootstrap-admin-password. Change it at first login."
    }
}
finally {
    Restore-AdtekTestEnvironment $previous
    $env:ADTEK_BOOTSTRAP_USERNAME = $previousBootstrapUser
    $env:ADTEK_BOOTSTRAP_PASSWORD = $previousBootstrapPassword
}
exit $bootstrapExitCode
