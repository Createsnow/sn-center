[CmdletBinding()]
param()
# 从本项目受保护的秘密文件装配测试环境；不会读取相邻旧工程 .env。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar'
$jdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Directory | Select-Object -First 1
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Build first: ./tools/maven.ps1 -B -ntp verify'
}
. (Join-Path $PSScriptRoot 'TestEnvironment.ps1')
$previous = Set-AdtekTestEnvironment
try {
    Write-Output 'Starting test API on http://127.0.0.1:18080 (Ctrl+C stops this process).'
    & (Join-Path $jdk.FullName 'bin/java.exe') '-jar' $jar
    $serverExitCode = $LASTEXITCODE
}
finally {
    Restore-AdtekTestEnvironment $previous
}
exit $serverExitCode
