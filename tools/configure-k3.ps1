[CmdletBinding(DefaultParameterSetName='Status')]
param(
    [Parameter(Mandatory,ParameterSetName='Import')][string]$JsonPath,
    [Parameter(Mandatory,ParameterSetName='Legacy')][string]$LegacyEnvPath,
    [Parameter(Mandatory,ParameterSetName='Disable')][switch]$Disable
)
# 仅写新项目的本机/容器共用K3秘密目录，不连接ERP、不启动同步、不重启服务、不回显任何字段值。
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'K3Configuration.ps1')
$directory=Join-Path (Split-Path -Parent $PSScriptRoot) 'deploy/.secrets/k3'
if($PSCmdlet.ParameterSetName -ne 'Status') {
    $config=switch($PSCmdlet.ParameterSetName) {
        'Import' { Read-AdtekK3Configuration $JsonPath }
        'Legacy' { Read-AdtekLegacyK3Configuration $LegacyEnvPath }
        'Disable' { $null }
    }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $directory /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' /Q | Out-Null
    if($LASTEXITCODE -ne 0) { throw 'Cannot restrict K3 configuration permissions.' }
    if($Disable) { [IO.File]::WriteAllText((Join-Path $directory 'adtek.k3.enabled'),'false',[Text.UTF8Encoding]::new($false)) }
    else { Write-AdtekK3Configuration $config $directory }
    Write-Output 'K3 configuration saved. Restart the intended test API to apply; no synchronization was started.'
}
$enabled=Join-Path $directory 'adtek.k3.enabled'
$active=(Test-Path -LiteralPath $enabled) -and [IO.File]::ReadAllText($enabled).Trim() -eq 'true'
$missing=@('url','account','app-id','app-secret','username' | Where-Object { $path=Join-Path $directory ('adtek.k3.'+$_); !(Test-Path -LiteralPath $path) -or [string]::IsNullOrWhiteSpace([IO.File]::ReadAllText($path)) })
[ordered]@{enabled=$active;complete=$missing.Count -eq 0;missingFields=$missing;connectivityVerified=$false} | ConvertTo-Json -Depth 3
