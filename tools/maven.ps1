[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArgs)
# 只设置子进程环境；工具与仓库缓存留在本工程 .tools，不修改系统 Java。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Directory | Select-Object -First 1
if (-not $jdk) {
    throw 'Run tools/bootstrap-build.ps1 first.'
}
$env:JAVA_HOME = $jdk.FullName
$env:MAVEN_USER_HOME = Join-Path $projectRoot '.tools/maven-user'
$repository = Join-Path $projectRoot '.tools/m2'
$backend = Join-Path $projectRoot 'backend'
$wrapper = Join-Path $backend 'mvnw.cmd'
Push-Location $backend
try {
    if (Test-Path -LiteralPath $wrapper) {
        & $wrapper "-Dmaven.repo.local=$repository" @MavenArgs
    }
    else {
        $maven = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/maven') -Directory | Select-Object -First 1
        & (Join-Path $maven.FullName 'bin/mvn.cmd') "-Dmaven.repo.local=$repository" @MavenArgs
    }
    $buildExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
exit $buildExitCode
