[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Destination,
    [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]{2,40}$')][string]$ProjectName,
    [ValidateRange(1024, 65535)][int]$MysqlPort = 33326,
    [ValidateRange(1024, 65535)][int]$RedisPort = 36399,
    [ValidateRange(1024, 65535)][int]$ApiPort = 18100,
    [ValidateRange(1024, 65535)][int]$WebPort = 15193
)

# 只复制模板 Git 已跟踪文件；不复制缓存、账号、秘密、测试数据或原仓库历史。
$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$targetRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
if (Test-Path -LiteralPath $targetRoot) {
    throw 'Destination must not exist.'
}
if ($targetRoot.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Destination must be outside the template checkout.'
}
if (@(@($MysqlPort, $RedisPort, $ApiPort, $WebPort) | Select-Object -Unique).Count -ne 4) {
    throw 'All four ports must be distinct.'
}
if ($ProjectName -eq 'adtek-platform-template') {
    throw 'Choose a distinct project name.'
}
$files = @(& git -C $sourceRoot -c core.quotepath=false ls-files)
if ($LASTEXITCODE -ne 0 -or $files.Count -lt 1) {
    throw 'A populated template Git checkout is required.'
}
$dirty = @(& git -C $sourceRoot status --porcelain)
if ($LASTEXITCODE -ne 0 -or $dirty.Count -gt 0) {
    throw 'Commit or restore template changes before copying a release.'
}
$sourceCommit = (& git -C $sourceRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Cannot resolve template revision.'
}
$database = $ProjectName.Replace('-', '_') + '_test'
$replacements = [ordered]@{
    'adtek-platform-template' = $ProjectName
    'adtek_template_test' = $database
    'adtek-template-' = ($ProjectName + '-')
    '33316' = [string]$MysqlPort
    '36389' = [string]$RedisPort
    '18090' = [string]$ApiPort
    '15183' = [string]$WebPort
}
# 一次匹配原始文本，避免新项目名称包含 adtek-template- 时被第二次替换。
$replacePattern = ($replacements.Keys | ForEach-Object { [regex]::Escape($_) }) -join '|'
New-Item -ItemType Directory -Path $targetRoot | Out-Null
foreach ($relative in $files) {
    if ($relative -match '(^|/)(\.secrets|\.tools|node_modules|target|test-results|\.runtime|data)(/|$)') {
        throw "Unexpected tracked runtime path: $relative"
    }
    $target = [IO.Path]::GetFullPath((Join-Path $targetRoot $relative))
    if (-not $target.StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Tracked path leaves destination.'
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    $original = Join-Path $sourceRoot $relative
    # 图片与发布证据保持字节；仅修改可运行配置和界面中的命名。
    if ($relative -match '^(backend/|frontend/|tools/|deploy/)' -and $relative -notmatch '(^|/)(new-project\.ps1|build-tools\.lock\.json|pnpm-lock\.yaml|images\.lock\.json)$' -and ([IO.Path]::GetFileName($relative) -eq 'Dockerfile' -or [IO.Path]::GetExtension($relative) -in @('.java', '.xml', '.yml', '.json', '.ps1', '.sh', '.ts', '.tsx', '.html', '.conf'))) {
        $content = [IO.File]::ReadAllText($original)
        $content = [regex]::Replace($content, $replacePattern, { param($match) $replacements[$match.Value] })
        [IO.File]::WriteAllText($target, $content, [Text.UTF8Encoding]::new($false))
    }
    else {
        Copy-Item -LiteralPath $original -Destination $target
    }
}
$provenance = [ordered]@{ template = 'adtek-platform-template'; version = '0.1.0'; commit = $sourceCommit; project = $ProjectName; createdAtUtc = [DateTime]::UtcNow.ToString('o'); ports = @{ mysql = $MysqlPort; redis = $RedisPort; api = $ApiPort; web = $WebPort } }
$provenance | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $targetRoot 'template-origin.json') -Encoding utf8
Write-Output "Created $ProjectName from template 0.1.0 at $targetRoot. Bootstrap tools and new isolated infrastructure before use."
