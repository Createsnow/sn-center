[CmdletBinding()]
param()
# 在唯一临时工程中执行正式Windows配置入口，绝不写开发秘密/连接ERP；失败不降级或跳过。
$ErrorActionPreference='Stop'
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('adtek-k3-config-'+[Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path (Join-Path $temporary 'tools')
$secret='synthetic:$#''"\=!'
$script=Join-Path $temporary 'tools/configure-k3.ps1'
$inputFile=Join-Path $temporary 'connection.json'
$saved=Join-Path $temporary 'deploy/.secrets/k3'
$checks=0
try {
    foreach($name in @('configure-k3.ps1','K3Configuration.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $temporary ('tools/'+$name)) }
    $state= & $script | ConvertFrom-Json
    if($state.enabled -or $state.complete) { throw 'Absent configuration must be disabled.' };$checks++
    $valid=@{url='https://example.invalid/K3Cloud';account='fixture-account';appId='fixture-app';appSecret=$secret;username='合成用户'}
    $valid | ConvertTo-Json | Set-Content -LiteralPath $inputFile -Encoding utf8
    $output=(& $script -JsonPath $inputFile) -join "`n"
    if($output.Contains($secret)) { throw 'Configuration secret leaked.' };$checks++
    $state= & $script | ConvertFrom-Json
    if(!$state.enabled -or !$state.complete -or $state.connectivityVerified) { throw 'Configured state incorrect.' };$checks++
    if([IO.File]::ReadAllText((Join-Path $saved 'adtek.k3.app-secret')) -cne $secret) { throw 'Literal secret changed.' };$checks++
    $original=(Get-FileHash -LiteralPath (Join-Path $saved 'adtek.k3.app-secret')).Hash
    foreach($kind in @('missing','extra','newline','url-secret','placeholder')) {
        $bad=$valid.Clone()
        switch($kind) {
            'missing' {$bad.Remove('account')}
            'extra' {$bad['spring.datasource.url']='unexpected'}
            'newline' {$bad.username="line`nbreak"}
            'url-secret' {$bad.url='https://user:secret@example.invalid/'}
            'placeholder' {$bad.appSecret='${UNTRUSTED}'}
        }
        $bad | ConvertTo-Json | Set-Content -LiteralPath $inputFile -Encoding utf8
        $rejected=$false
        try { & $script -JsonPath $inputFile | Out-Null } catch { $rejected=$true;if($_.ToString().Contains($secret)){throw 'Validation leaked secret.'} }
        if(!$rejected -or (Get-FileHash -LiteralPath (Join-Path $saved 'adtek.k3.app-secret')).Hash -ne $original) { throw 'Invalid input changed configuration.' };$checks++
    }
    & $script -Disable | Out-Null
    $state= & $script | ConvertFrom-Json
    if($state.enabled -or !$state.complete) { throw 'Disable must preserve existing fields without enabling connection.' };$checks++
    $legacyFile=Join-Path $temporary 'sn.env'
    $marker=Join-Path $temporary 'must-not-execute'
    $legacy=@(
        '# synthetic legacy configuration',
        'K3_SERVER_URL="https://example.invalid/K3Cloud/"',
        'K3_ACCT_ID=fixture-account',
        'K3_APP_ID=fixture-app',
        'K3_APP_SECRET=old-value',
        ('export K3_APP_SECRET="'+$secret+'"'),
        "K3_USERNAME=' 合成用户 '",
        'k3_app_secret=wrong-case-ignored',
        ('DB_PASSWORD=$(New-Item -Path "'+$marker+'")')
    ) -join "`r`n"
    [IO.File]::WriteAllText($legacyFile,$legacy,[Text.UTF8Encoding]::new($true))
    $sourceHash=(Get-FileHash -LiteralPath $legacyFile).Hash
    $output=(& $script -LegacyEnvPath $legacyFile) -join "`n"
    if($output.Contains($secret) -or (Test-Path -LiteralPath $marker)) { throw 'Legacy configuration leaked or executed content.' };$checks++
    if((Get-FileHash -LiteralPath $legacyFile).Hash -ne $sourceHash) { throw 'Legacy source was modified.' };$checks++
    if([IO.File]::ReadAllText((Join-Path $saved 'adtek.k3.app-secret')) -cne $secret -or [IO.File]::ReadAllText((Join-Path $saved 'adtek.k3.username')) -cne '合成用户') { throw 'Legacy quotes/duplicates/case/trim handling incorrect.' };$checks++
    if(@(Get-ChildItem -LiteralPath $saved -File).Count -ne 6) { throw 'Unexpected legacy configuration imported.' };$checks++
    $state=& $script | ConvertFrom-Json
    if(!$state.enabled -or !$state.complete) { throw 'Legacy configuration was not enabled.' };$checks++
    foreach($badLegacy in @($legacy.Replace('K3_ACCT_ID=fixture-account','K3_ACCT_ID='),$legacy.Replace('K3_APP_ID=fixture-app','k3_app_id=fixture-app'),$legacy.Replace($secret,'${SHOULD_NOT_EXPAND}'))) {
        [IO.File]::WriteAllText($legacyFile,$badLegacy,[Text.UTF8Encoding]::new($false))
        $rejected=$false
        try { & $script -LegacyEnvPath $legacyFile | Out-Null } catch { $rejected=$true;if($_.ToString().Contains($secret)){throw 'Legacy validation leaked secret.'} }
        if(!$rejected -or [IO.File]::ReadAllText((Join-Path $saved 'adtek.k3.app-secret')) -cne $secret) { throw 'Invalid legacy input changed configuration.' };$checks++
    }
    Write-Output "K3 configuration checks=$checks failures=0; only synthetic temporary files used."
} finally {
    $resolved=[IO.Path]::GetFullPath($temporary)
    $root=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if(!$resolved.StartsWith($root,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^adtek-k3-config-[0-9a-f]{32}$') { throw 'Refusing unsafe temporary cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
