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
# Windows JDK的NIO管道使用Unix-domain临时套接字；过长的用户TEMP路径会导致connect失败。
# 作为Maven用户属性传给Surefire/Failsafe，不修改系统TEMP，也不更换或升级JDK。
$socketTemp = Join-Path $projectRoot '.tools/tmp'
New-Item -ItemType Directory -Force -Path $socketTemp | Out-Null
$localProperties = @("-Dmaven.repo.local=$repository", "-Djdk.net.unixdomain.tmpdir=$socketTemp")
$backend = Join-Path $projectRoot 'backend'
$wrapper = Join-Path $backend 'mvnw.cmd'
Push-Location $backend
try {
    if (Test-Path -LiteralPath $wrapper) {
        & $wrapper @localProperties @MavenArgs
    }
    else {
        $maven = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/maven') -Directory | Select-Object -First 1
        & (Join-Path $maven.FullName 'bin/mvn.cmd') @localProperties @MavenArgs
    }
    $buildExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
exit $buildExitCode
