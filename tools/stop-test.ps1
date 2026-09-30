[CmdletBinding()]
param()
# 仅终止本工程 jar 的本机测试进程；生产和长任务恢复不能使用此入口。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = [IO.Path]::GetFullPath((Join-Path $projectRoot 'backend/adtek-server/target/adtek-server-0.1.0-SNAPSHOT.jar'))
$pattern = '\s-jar\s+"?' + [regex]::Escape($jar) + '"?(\s|$)'
$processes = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
    $_.CommandLine -match $pattern -and $_.ExecutablePath.StartsWith((Join-Path $projectRoot '.tools/jdk'), [StringComparison]::OrdinalIgnoreCase)
})
foreach ($process in $processes) {
    Stop-Process -Id $process.ProcessId -ErrorAction Stop
    Write-Output "Stopped adtek-platform-sn test process $($process.ProcessId)."
}
if ($processes.Count -eq 0) { Write-Output 'No matching test API process is running.' }
