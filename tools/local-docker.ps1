[CmdletBinding()]
param([ValidateSet('start', 'status', 'stop')][string]$Action = 'status')

# 仅部署本工程隔离环境；保留既有凭据、账号和卷，不自动重置数据。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$secretRoot = Join-Path $projectRoot 'deploy/.secrets'
$composeFiles = @('-f', (Join-Path $projectRoot 'deploy/compose.test.yml'), '-f', (Join-Path $projectRoot 'deploy/compose.local.yml'))

function Invoke-LocalCompose([string[]]$ComposeArguments) {
    & docker compose @composeFiles @ComposeArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Local Docker command failed: $($ComposeArguments -join ' ')"
    }
}

if ($Action -eq 'status') {
    Invoke-LocalCompose @('ps', '-a')
    exit 0
}
if ($Action -eq 'stop') {
    Invoke-LocalCompose @('stop')
    exit 0
}

# 数据卷存在而凭据丢失时必须恢复原凭据；不能重新生成并假装完成初始化。
$existingVolumes = & docker volume ls --filter 'label=com.docker.compose.project=adtek-platform-template-test' --format '{{.Name}}'
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Linux engine is not ready.'
}
if ($existingVolumes) {
    foreach ($name in @('mysql-root-password', 'mysql-app-password', 'mysql-migration-password', 'redis-password', 'jwt-signing-key')) {
        if (-not (Test-Path -LiteralPath (Join-Path $secretRoot $name))) {
            throw "Existing local volumes require the original secret: $name. Restore it before starting."
        }
    }
}

& (Join-Path $PSScriptRoot 'infra.ps1') start
if ($LASTEXITCODE -ne 0) {
    throw 'Local infrastructure startup failed.'
}
& (Join-Path $PSScriptRoot 'check-infra.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'Local infrastructure verification failed.'
}

$certificateFile = Join-Path $secretRoot 'local-tls-certificate.pem'
$privateKeyFile = Join-Path $secretRoot 'local-tls-private-key.pem'
if ((Test-Path -LiteralPath $certificateFile) -ne (Test-Path -LiteralPath $privateKeyFile)) {
    throw 'Local TLS certificate/key pair is incomplete; restore the matching pair.'
}
if (-not (Test-Path -LiteralPath $certificateFile)) {
    # 自签名证书只用于本机 HTTPS；不修改系统或浏览器信任库。
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    $certificate = $null
    try {
        $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $san = [Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
        $san.AddDnsName('localhost')
        $san.AddIpAddress([Net.IPAddress]::Loopback)
        $request.CertificateExtensions.Add($san.Build())
        $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true))
        $usages = [Security.Cryptography.OidCollection]::new()
        [void]$usages.Add([Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.1'))
        $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($usages, $false))
        $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5), [DateTimeOffset]::UtcNow.AddYears(1))
        [IO.File]::WriteAllText($certificateFile, $certificate.ExportCertificatePem(), [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($privateKeyFile, $rsa.ExportPkcs8PrivateKeyPem(), [Text.UTF8Encoding]::new($false))
    }
    finally {
        if ($certificate) {
            $certificate.Dispose()
        }
        $rsa.Dispose()
    }
}

Invoke-LocalCompose @('config', '--quiet')
Invoke-LocalCompose @('run', '--rm', '--no-deps', 'migrate')
$userCount = Invoke-LocalCompose @('exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$(cat /run/secrets/mysql_app_password)" mysql --protocol=tcp -h127.0.0.1 -uadtek_app adtek_template_test --batch --skip-column-names -e "SELECT COUNT(*) FROM sys_user;"')
if ("$userCount".Trim() -notmatch '^\d+$') {
    throw 'Could not determine whether local administrator initialization is needed.'
}
if ([long]"$userCount".Trim() -eq 0) {
    $passwordFile = Join-Path $secretRoot 'bootstrap-admin-password'
    if (-not (Test-Path -LiteralPath $passwordFile)) {
        $password = 'At9!' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant()
        [IO.File]::WriteAllText($passwordFile, $password, [Text.UTF8Encoding]::new($false))
    }
    Invoke-LocalCompose @('run', '--rm', '--no-deps', 'bootstrap')
    Write-Output 'Initialized admin; initial password: deploy/.secrets/bootstrap-admin-password. Change it at first login.'
}
else {
    Write-Output 'Existing local accounts retained; administrator initialization skipped.'
}

Invoke-LocalCompose @('up', '-d', '--wait', '--wait-timeout', '240', 'api', 'web')
$baseUrl = 'https://127.0.0.1:18453'
foreach ($path in @('/', '/healthz', '/health/ready')) {
    $response = Invoke-WebRequest -Uri "$baseUrl$path" -SkipCertificateCheck -TimeoutSec 20
    if ($response.StatusCode -ne 200) {
        throw "Local deployment check failed: $path"
    }
    if ($path -eq '/health/ready') {
        # Actuator 的 vendor JSON 在部分 PowerShell 版本中以字节数组返回。
        $content = if ($response.Content -is [byte[]]) {
            [Text.Encoding]::UTF8.GetString($response.Content)
        }
        else {
            [string]$response.Content
        }
        if (($content | ConvertFrom-Json).status -ne 'UP') {
            throw 'Local API readiness is not UP.'
        }
    }
}
Invoke-LocalCompose @('ps')
Write-Output "Local Docker deployment ready: $baseUrl (local self-signed TLS certificate)."
