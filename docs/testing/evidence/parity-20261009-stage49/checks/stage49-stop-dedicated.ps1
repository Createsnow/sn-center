$ErrorActionPreference = 'Stop'
$taskRun = 'D:\AdtekProjects\mf-sn-uniq-guard\adtek-platform-sn\tools\.runtime\comparison-20261008'
$taskStopped = @()
foreach ($taskService in @(
        @{ Name = 'java'; Port = 18081 },
        @{ Name = 'vite'; Port = 15174 },
        @{ Name = 'python'; Port = 8001 },
        @{ Name = 'k3'; Port = 19091 }
    )) {
    $taskExpectedId = [int](Get-Content -LiteralPath (Join-Path $taskRun "order-audits\$($taskService.Name).pid") -Raw).Trim()
    $taskListener = @(Get-NetTCPConnection -LocalPort $taskService.Port -State Listen -ErrorAction Stop)
    if ($taskListener.Count -ne 1 -or $taskListener[0].LocalAddress -ne '127.0.0.1') { throw 'Unexpected listener' }
    $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($taskListener[0].OwningProcess)"
    if ($taskProcess.ProcessId -ne $taskExpectedId -and $taskProcess.ParentProcessId -ne $taskExpectedId) { throw 'Unexpected process lineage' }
    $taskWrapper = Get-CimInstance Win32_Process -Filter "ProcessId=$taskExpectedId"
    $taskLauncher = Get-CimInstance Win32_Process -Filter "ProcessId=$($taskWrapper.ParentProcessId)"
    if ($taskLauncher.CommandLine -notmatch 'order-audits[\\/]run.py' -or $taskLauncher.CommandLine -notmatch (" " + $taskService.Name + "$")) { throw 'Unexpected launcher' }
    $taskStopped += [pscustomobject]@{ service = $taskService.Name; port = $taskService.Port; listenerPid = $taskProcess.ProcessId; wrapperPid = $taskExpectedId; verifiedLauncher = $true }
    Stop-Process -Id $taskProcess.ProcessId -ErrorAction Stop
}
$taskRemaining = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(18081, 15174, 8001, 19091) })
if ($taskRemaining.Count -ne 0) { throw 'Dedicated services still listening' }
$taskMain = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(18080, 15173, 8000, 19090) } | Select-Object LocalAddress, LocalPort, OwningProcess)
if ($taskMain.Count -ne 4) { throw 'Main comparison listeners changed unexpectedly' }
@{ stopped = $taskStopped; main = $taskMain; dedicatedPortsClosed = $true } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskRun 'stage49-services.json') -Encoding utf8
$taskStopped | Format-Table
$taskMain | Format-Table
