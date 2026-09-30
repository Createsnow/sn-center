[CmdletBinding()]
param()

# 工作目录任意；仅下载到本工程 .tools，不修改系统 PATH/注册表。
# 需要 PowerShell 7、Node.js 24 和 npm；官方文件校验失败时停止。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools'
$downloadRoot = Join-Path $toolRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null
$lockPath = Join-Path $PSScriptRoot 'build-tools.lock.json'

if (Test-Path -LiteralPath $lockPath) {
    $toolLock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
}
else {
    $jdkAssets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $jdkAsset = @($jdkAssets)[0]
    $mavenMetadata = [xml](Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml').Content
    $mavenVersion = $mavenMetadata.metadata.versioning.versions.version | Where-Object { $_ -match '^3\.9\.\d+$' } | Sort-Object { [version]$_ } | Select-Object -Last 1
    $mavenUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
    $mavenChecksum = ((Invoke-WebRequest "$mavenUrl.sha512").Content -split '\s+')[0]
    $pnpmMetadata = Invoke-RestMethod 'https://registry.npmjs.org/pnpm'
    $pnpmVersion = $pnpmMetadata.versions.PSObject.Properties.Name | Where-Object { $_ -match '^10\.\d+\.\d+$' } | Sort-Object { [version]$_ } | Select-Object -Last 1
    $toolLock = [ordered]@{
        jdk = [ordered]@{ version = $jdkAsset.version.semver; url = $jdkAsset.binary.package.link; sha256 = $jdkAsset.binary.package.checksum }
        maven = [ordered]@{ version = $mavenVersion; url = $mavenUrl; sha512 = $mavenChecksum }
        pnpm = [ordered]@{ version = $pnpmVersion }
    }
    $toolLock | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 -LiteralPath $lockPath
    $toolLock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
}

function Save-VerifiedArchive([string]$Url, [string]$Path, [string]$Algorithm, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Output "Downloading $Url"
        Invoke-WebRequest -Uri $Url -OutFile $Path -TimeoutSec 300
    }
    if ((Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -ne $Expected) {
        throw "Checksum mismatch: $Path. Inspect the download before retrying."
    }
}

$jdkArchive = Join-Path $downloadRoot 'jdk21.zip'
$jdkRoot = Join-Path $toolRoot 'jdk'
if (-not (Test-Path -LiteralPath $jdkRoot)) {
    Save-VerifiedArchive $toolLock.jdk.url $jdkArchive 'SHA256' $toolLock.jdk.sha256
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath $jdkRoot
}
$mavenArchive = Join-Path $downloadRoot 'maven.zip'
$mavenRoot = Join-Path $toolRoot 'maven'
if (-not (Test-Path -LiteralPath $mavenRoot)) {
    Save-VerifiedArchive $toolLock.maven.url $mavenArchive 'SHA512' $toolLock.maven.sha512
    Expand-Archive -LiteralPath $mavenArchive -DestinationPath $mavenRoot
}
$pnpmRoot = Join-Path $toolRoot 'pnpm'
if (-not (Test-Path -LiteralPath (Join-Path $pnpmRoot 'node_modules/pnpm/bin/pnpm.cjs'))) {
    & npm.cmd install --prefix $pnpmRoot --package-lock=false --ignore-scripts --no-audit --no-fund "pnpm@$($toolLock.pnpm.version)"
    if ($LASTEXITCODE -ne 0) {
        throw 'pnpm installation failed'
    }
}
$javaRoot = Get-ChildItem -Directory -LiteralPath $jdkRoot | Select-Object -First 1
$mvnRoot = Get-ChildItem -Directory -LiteralPath $mavenRoot | Select-Object -First 1
& (Join-Path $javaRoot.FullName 'bin/java.exe') -version
& node (Join-Path $pnpmRoot 'node_modules/pnpm/bin/pnpm.cjs') --version
Write-Output "Maven $($toolLock.maven.version): $($mvnRoot.FullName)"
