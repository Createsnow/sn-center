# K3只读连接的白名单配置。JSON/dotenv只作为数据解析，不执行脚本/插值；源路径必须显式指定。
function Read-AdtekK3Configuration([string]$JsonPath) {
    $file = Get-Item -LiteralPath $JsonPath -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -gt 16384) {
        throw 'K3 configuration must be a JSON file of at most 16 KiB.'
    }
    try {
        $config = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -AsHashtable -ErrorAction Stop
    }
    catch {
        throw 'Invalid K3 JSON configuration; contents suppressed.'
    }
    return Assert-AdtekK3Configuration $config
}

# 与旧app/core/envfile.py一致：export、成对引号、最后赋值有效；只提取K3五字段，不导入DB_*等其他配置。
# 不合并当前Shell环境，避免不知情地混用其他账套；缺字段时在写目标目录前失败，绝不把模板当有效连接。
function Read-AdtekLegacyK3Configuration([string]$EnvPath) {
    $file = Get-Item -LiteralPath $EnvPath -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -gt 1048576) {
        throw 'Legacy K3 configuration must be a dotenv file of at most 1 MiB.'
    }
    $mapping = [System.Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    $mapping.Add('K3_SERVER_URL', 'url'); $mapping.Add('K3_ACCT_ID', 'account'); $mapping.Add('K3_APP_ID', 'appId')
    $mapping.Add('K3_APP_SECRET', 'appSecret'); $mapping.Add('K3_USERNAME', 'username')
    $config = @{}
    foreach ($raw in [IO.File]::ReadAllLines($file.FullName, [Text.Encoding]::UTF8)) {
        $line = $raw.Trim()
        if (!$line -or $line.StartsWith('#')) {
            continue
        }
        if ($line.StartsWith('export ', [StringComparison]::Ordinal)) {
            $line = $line.Substring(7).Trim()
        }
        $separator = $line.IndexOf('=')
        if ($separator -lt 0) {
            continue
        }
        $key = $line.Substring(0, $separator).Trim()
        if (!$mapping.ContainsKey($key)) {
            continue
        }
        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and $value[0] -eq $value[$value.Length - 1] -and $value[0] -in @([char]34, [char]39)) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $config[$mapping[$key]] = $value.Trim()
    }
    return Assert-AdtekK3Configuration $config
}

# 所有入口共用完整校验；错误只包含字段名，避免把源文件/原值带入输出。
function Assert-AdtekK3Configuration($config) {
    $names = @('url', 'account', 'appId', 'appSecret', 'username')
    if ($config -isnot [System.Collections.IDictionary] -or $config.Count -ne $names.Count) {
        throw 'K3 JSON must contain exactly url, account, appId, appSecret and username.'
    }
    foreach ($name in $names) {
        $value = $config[$name]
        if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value) -or $value.Length -gt 4096 -or $value -match '[\x00-\x1f\x7f]' -or $value.Contains('${')) {
            throw "Invalid or missing K3 field: $name; value suppressed."
        }
    }
    if ($config.account.Length -gt 64) {
        throw 'K3 account exceeds the source identity limit.'
    }
    $uri = $null
    if (![Uri]::TryCreate($config.url, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http', 'https') -or !$uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw 'Invalid K3 URL; credentials/query/fragment are not permitted in the URL.'
    }
    return $config
}

# 调用方限定为本工程受保护目录；先关闭开关再写五字段，全部完成后才启用。运行中的应用不热加载。
function Write-AdtekK3Configuration([System.Collections.IDictionary]$Configuration, [string]$Directory) {
    [IO.Directory]::CreateDirectory($Directory) | Out-Null
    $encoding = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText((Join-Path $Directory 'adtek.k3.enabled'), 'false', $encoding)
    $mapping = @{url = 'url'; account = 'account'; appId = 'app-id'; appSecret = 'app-secret'; username = 'username' }
    foreach ($name in $mapping.Keys) {
        [IO.File]::WriteAllText((Join-Path $Directory ('adtek.k3.' + $mapping[$name])), $Configuration[$name], $encoding)
    }
    [IO.File]::WriteAllText((Join-Path $Directory 'adtek.k3.enabled'), 'true', $encoding)
}
