[CmdletBinding()]
param([ValidateSet('build', 'start', 'status', 'stop', 'check', 'bootstrap', 'restart-api')][string]$Action = 'status')
# 仅固定container-test项目及独立卷。生成的自签证书只用于localhost测试，不安装信任根。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'DockerEnvironment.ps1')
$docker = Get-AdtekDocker
$projectRoot = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $projectRoot 'deploy/compose.container-test.yml'
$secrets = Join-Path $projectRoot 'deploy/.secrets/container-test'
if ($Action -in @('build', 'start', 'bootstrap')) {
    # 可为空；只有configure-k3显式安装后才启用真实连接。绑定只读目录，不把秘密写入镜像。
    $k3Directory = Join-Path $projectRoot 'deploy/.secrets/k3'
    New-Item -ItemType Directory -Path $k3Directory -Force | Out-Null
    New-Item -ItemType Directory -Path $secrets -Force | Out-Null
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $secrets /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' /Q | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot restrict test secret permissions.'
    }
    foreach ($name in @('mysql-root-password', 'mysql-app-password', 'mysql-migration-password', 'mysql-maintenance-password', 'redis-password', 'jwt-signing-key')) {
        $path = Join-Path $secrets $name
        if (!(Test-Path -LiteralPath $path)) {
            [IO.File]::WriteAllText($path, [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant(), [Text.UTF8Encoding]::new($false))
        }
    }
    $adminPassword = Join-Path $secrets 'container-admin-password'
    if (!(Test-Path -LiteralPath $adminPassword)) {
        [IO.File]::WriteAllText($adminPassword, 'Sn9!' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant(), [Text.UTF8Encoding]::new($false))
    }
    $certPath = Join-Path $secrets 'tls-cert.pem'; $keyPath = Join-Path $secrets 'tls-key.pem'
    if ((Test-Path -LiteralPath $certPath) -ne (Test-Path -LiteralPath $keyPath)) {
        throw 'Incomplete test TLS pair; keep existing files and resolve before starting.'
    }
    if (!(Test-Path -LiteralPath $certPath)) {
        $rsa = [Security.Cryptography.RSA]::Create(3072)
        try {
            $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
            $san = [Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
            $san.AddDnsName('localhost'); $san.AddIpAddress([Net.IPAddress]::Parse('127.0.0.1'))
            $request.CertificateExtensions.Add($san.Build())
            $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true))
            $oids = [Security.Cryptography.OidCollection]::new(); [void]$oids.Add([Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.1'))
            $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($oids, $true))
            $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5), [DateTimeOffset]::UtcNow.AddDays(90))
            try {
                [IO.File]::WriteAllText($keyPath, $rsa.ExportPkcs8PrivateKeyPem(), [Text.UTF8Encoding]::new($false))
                [IO.File]::WriteAllText($certPath, $certificate.ExportCertificatePem(), [Text.UTF8Encoding]::new($false))
            }
            finally {
                $certificate.Dispose()
            }
        }
        finally {
            $rsa.Dispose()
        }
    }
}
& $docker compose -f $compose config --quiet
if ($LASTEXITCODE -ne 0) {
    throw 'Container test configuration invalid.'
}
switch ($Action) {
    'build' {
        & $docker compose -f $compose build api web
    }
    'start' {
        & $docker compose -f $compose up -d --wait --wait-timeout 240
    }
    'stop' {
        & $docker compose -f $compose stop
    }
    'status' {
        & $docker compose -f $compose ps -a
    }
    'restart-api' {
        # 配置文件变动不改变Compose哈希；显式重启同一API容器才能重新加载，不重启数据库或重置账号。
        & $docker compose -f $compose restart --no-deps api
        if ($LASTEXITCODE -ne 0) {
            throw 'Container API restart failed.'
        }
        $apiId = (& $docker compose -f $compose ps -q api).Trim()
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        do {
            $health = (& $docker inspect --format '{{.State.Health.Status}}' $apiId).Trim()
            if ($LASTEXITCODE -ne 0) {
                throw 'Cannot inspect restarted API.'
            }
            if ($health -eq 'healthy') {
                break
            }
            Start-Sleep -Seconds 1
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($health -ne 'healthy') {
            throw 'Restarted API did not become healthy.'
        }
        Write-Output 'Container API reloaded configuration and is healthy.'
    }
    'bootstrap' {
        & $docker compose -f $compose run --rm --no-deps bootstrap
    }
    'check' {
        Write-Output 'Container test configuration valid.'
    }
}
if ($LASTEXITCODE -ne 0) {
    throw "Container test $Action failed."
}
if ($Action -eq 'start') {
    Write-Output 'Container test: https://127.0.0.1:18443 (local self-signed certificate); existing development database is separate.'
}
