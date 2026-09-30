[CmdletBinding()]
param(
    [switch]$Check,
    [ValidateSet('All', 'Backend', 'Frontend', 'PowerShell', 'Configuration')]
    [string]$Scope = 'All'
)

# 使用固定工具格式化声明的源码；不扫描秘密、数据库迁移、历史证据或生成目录。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Get-ScriptTokenSignature([string]$Text) {
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) {
        throw 'PowerShell parsing failed; formatting was not applied.'
    }
    # 保留标点、运算符及字符串原文；只忽略排版和注释token，防止格式器改动命令。
    $signature = @($tokens | Where-Object { $_.Kind -notin @('NewLine', 'LineContinuation', 'Comment', 'EndOfInput') } |
            ForEach-Object { @($_.Kind.ToString(), $_.Text.Replace("`r`n", "`n")) })
    $shape = @($ast.FindAll({ param($node) $true }, $true) | ForEach-Object { $_.GetType().Name })
    return ConvertTo-Json -InputObject @{ tokens = $signature; astShape = $shape } -Compress -Depth 4
}

if ($Scope -in @('All', 'Backend')) {
    $goal = if ($Check) {
        'spotless:check'
    }
    else {
        'spotless:apply'
    }
    & (Join-Path $PSScriptRoot 'maven.ps1') -B -ntp $goal checkstyle:check
    if ($LASTEXITCODE -ne 0) {
        throw 'Backend formatting failed.'
    }
}

if ($Scope -in @('All', 'Frontend')) {
    $task = if ($Check) {
        'format:check'
    }
    else {
        'format'
    }
    & (Join-Path $PSScriptRoot 'pnpm.ps1') $task
    if ($LASTEXITCODE -ne 0) {
        throw 'Frontend formatting failed.'
    }
}

if ($Scope -in @('All', 'Configuration')) {
    $paths = @(
        Get-ChildItem (Join-Path $projectRoot '.github/workflows') -File -Filter '*.yml'
        Get-ChildItem (Join-Path $projectRoot 'deploy') -File | Where-Object { $_.Extension -in @('.yml', '.json') }
        Get-ChildItem (Join-Path $projectRoot 'backend/adtek-server/src/main/resources') -File -Filter '*.yml'
        Get-ChildItem (Join-Path $projectRoot 'backend/adtek-server/src/test/resources') -File -Filter '*.yml'
        Get-Item (Join-Path $PSScriptRoot 'build-tools.lock.json')
    )
    $mode = if ($Check) {
        '--check'
    }
    else {
        '--write'
    }
    $arguments = @('exec', 'prettier', '--config', '.prettierrc.json', $mode) + @($paths.FullName)
    & (Join-Path $PSScriptRoot 'pnpm.ps1') @arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Configuration formatting failed.'
    }
}

if ($Scope -in @('All', 'PowerShell')) {
    $manifest = Join-Path $projectRoot '.tools/powershell/PSScriptAnalyzer/1.25.0/PSScriptAnalyzer.psd1'
    if (-not (Test-Path -LiteralPath $manifest)) {
        throw 'Run ./tools/bootstrap-format.ps1 first.'
    }
    Import-Module $manifest
    $settings = Join-Path $PSScriptRoot 'format-settings.psd1'
    $files = @(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object { $_.Extension -in @('.ps1', '.psd1') })
    $dirty = @()
    foreach ($file in $files) {
        $original = [IO.File]::ReadAllText($file.FullName)
        $formatted = (Invoke-Formatter -ScriptDefinition $original -Settings $settings).Replace("`r`n", "`n")
        $formatted = ($formatted -replace '(?m)[ \t]+$', '').TrimEnd() + "`n"
        if ((Get-ScriptTokenSignature $original) -cne (Get-ScriptTokenSignature $formatted)) {
            throw "Formatting changed non-whitespace tokens: $($file.Name)"
        }
        if ($original -cne $formatted) {
            $dirty += $file.Name
            if (-not $Check) {
                [IO.File]::WriteAllText($file.FullName, $formatted, [Text.UTF8Encoding]::new($false))
            }
        }
    }
    Write-Output "PowerShell files=$($files.Count) differences=$($dirty.Count)"
    if ($Check -and $dirty.Count -gt 0) {
        throw "Run ./tools/format.ps1 -Scope PowerShell: $($dirty -join ', ')"
    }
}

exit 0
