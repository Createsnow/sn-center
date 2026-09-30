[CmdletBinding(DefaultParameterSetName = 'Items')]
param(
    [Parameter(Mandatory)][string]$CredentialFile,
    [ValidatePattern('^http://127\.0\.0\.1:\d+$')][string]$BaseUrl = 'http://127.0.0.1:18080',
    [Parameter(Mandatory, ParameterSetName = 'Items')][switch]$Items,
    [Parameter(Mandatory, ParameterSetName = 'Acquire')][switch]$Acquire,
    [Parameter(Mandatory, ParameterSetName = 'Callback')][string]$CallbackFile,
    [Parameter(Mandatory, ParameterSetName = 'Batch')][string]$BatchId,
    [Parameter(Mandatory, ParameterSetName = 'Task')][string]$TaskId,
    [Parameter(Mandatory, ParameterSetName = 'Items')][Parameter(Mandatory, ParameterSetName = 'Acquire')][ValidatePattern('^[1-9][0-9]{0,18}$')][string]$FactoryId,
    [Parameter(Mandatory, ParameterSetName = 'Items')][Parameter(Mandatory, ParameterSetName = 'Acquire')][string]$Pi,
    [Parameter(Mandatory, ParameterSetName = 'Acquire')][guid]$OperationId,
    [Parameter(ParameterSetName = 'Items')][Parameter(ParameterSetName = 'Batch')][string]$Cursor,
    [Parameter(ParameterSetName = 'Items')][Parameter(ParameterSetName = 'Batch')][ValidateRange(1, 1000)][int]$Size = 1000
)
# 仅本机隔离联调；凭据从调用方受保护文件读取，不打印token/secret、不替调用方换操作号。
$ErrorActionPreference = 'Stop'
$credential = Get-Content -LiteralPath $CredentialFile -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $credential.clientId -or -not $credential.clientSecret) {
    throw 'Credential file must contain clientId and clientSecret.'
}
$session = $null
$headers = $null
try {
    $tokenBody = @{clientId = $credential.clientId; clientSecret = $credential.clientSecret } | ConvertTo-Json -Compress
    $session = Invoke-RestMethod -Uri "$BaseUrl/api/v1/integrations/auth/token" -Method Post -ContentType 'application/json' -Body $tokenBody -TimeoutSec 30
    if ($session.code -ne 0 -or -not $session.data.accessToken) {
        throw 'MES token rejected.'
    }
    $headers = @{Authorization = "Bearer $($session.data.accessToken)" }
    $method = 'Get'
    $body = $null
    switch ($PSCmdlet.ParameterSetName) {
        'Items' {
            $path = '/integrations/sn/items?factoryId=' + [uri]::EscapeDataString($FactoryId) + '&pi=' + [uri]::EscapeDataString($Pi) + '&size=' + $Size
        }
        'Batch' {
            $path = '/integrations/sn/acquire-batches/' + ([guid]$BatchId).ToString() + '/items?size=' + $Size
        }
        'Task' {
            $path = '/tasks/' + ([guid]$TaskId).ToString()
        }
        'Acquire' {
            $path = '/integrations/sn/acquisitions'; $method = 'Post'; $body = @{operationId = $OperationId.ToString(); factoryId = $FactoryId; pi = $Pi } | ConvertTo-Json -Compress
        }
        'Callback' {
            $command = Get-Content -LiteralPath $CallbackFile -Raw -Encoding UTF8 | ConvertFrom-Json
            if (-not $command.operationId -or -not $command.batchId -or $command.targetStatus -ne 'PRINTED') {
                throw 'Callback requires stable operationId, batchId and PRINTED.'
            }
            $path = '/integrations/sn/print-confirmations'; $method = 'Post'; $body = $command | ConvertTo-Json -Depth 8 -Compress
        }
    }
    if ($Cursor) {
        $path += '&cursor=' + [uri]::EscapeDataString($Cursor)
    }
    $request = @{Uri = "$BaseUrl/api/v1$path"; Method = $method; Headers = $headers; TimeoutSec = 60 }
    if ($body) {
        $request.ContentType = 'application/json'; $request.Body = [Text.Encoding]::UTF8.GetBytes($body)
    }
    $result = Invoke-RestMethod @request
    $result | ConvertTo-Json -Depth 20
}
catch {
    # 禁止把含凭据的HTTP请求、底层异常或令牌写到控制台；未知提交须查原operationId。
    throw 'MES request failed. Check local API/audit status. For an unknown write result, query or retry the original operationId and payload.'
}
finally {
    $tokenBody = $null; $credential = $null; $session = $null; $headers = $null
}
