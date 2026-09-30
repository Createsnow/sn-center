# 本机隔离备份/恢复共用的有界进程与tar校验。不执行归档中的路径、链接或脚本。
function New-AdtekDockerProcess([string]$Docker, [string[]]$Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Docker; $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($value in $Arguments) {
        [void]$info.ArgumentList.Add($value)
    }
    return [Diagnostics.Process]::Start($info)
}
function Invoke-AdtekDockerOutput([string]$Docker, [string[]]$Arguments, [string]$Output, [int]$Seconds = 120) {
    $process = New-AdtekDockerProcess $Docker $Arguments
    $process.StandardInput.Close()
    $errors = $process.StandardError.ReadToEndAsync()
    $stream = [IO.File]::Open($Output, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $copy = $process.StandardOutput.BaseStream.CopyToAsync($stream)
        if (!$process.WaitForExit($Seconds * 1000)) {
            $process.Kill($true); throw 'Backup subprocess time budget exceeded.'
        }
        [void]$copy.GetAwaiter().GetResult(); [void]$errors.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "Backup subprocess failed: exit=$($process.ExitCode); contents suppressed."
        }
        $stream.Flush($true)
    }
    finally {
        $stream.Dispose(); $process.Dispose()
    }
}
function Invoke-AdtekContainerSql([string]$Docker, [string]$Container, [string]$Schema, [string]$Sql) {
    if ($Container -notmatch '^[0-9a-f]{12,64}$' -or $Schema -notmatch '^[a-z][a-z0-9_]{0,63}$') {
        throw 'Invalid isolated database target.'
    }
    # 固定容器路径、无用户SQL插值。秘密从容器挂载点读取，不放在宿主命令行。
    $shell = 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql --protocol=socket -uroot --batch --skip-column-names --default-character-set=utf8mb4 --skip-reconnect ' + $Schema
    $process = New-AdtekDockerProcess $Docker @('exec', '-i', $Container, 'sh', '-c', $shell)
    $result = $process.StandardOutput.ReadToEndAsync(); $errors = $process.StandardError.ReadToEndAsync()
    try {
        $process.StandardInput.WriteLine($Sql); $process.StandardInput.Close()
        if (!$process.WaitForExit(30000)) {
            $process.Kill($true); throw 'Isolated SQL time budget exceeded.'
        }
        $errorText = $errors.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            $errorNumber = [regex]::Match($errorText, 'ERROR [0-9]+ \([A-Z0-9]+\)').Value
            throw "Isolated SQL failed ($errorNumber); row contents suppressed."
        }
        return $result.GetAwaiter().GetResult().Trim()
    }
    finally {
        $process.Dispose()
    }
}
function Invoke-AdtekDockerInput([string]$Docker, [string[]]$Arguments, [string]$InputFile, [int]$Seconds = 120) {
    $process = New-AdtekDockerProcess $Docker $Arguments
    $result = $process.StandardOutput.ReadToEndAsync(); $errors = $process.StandardError.ReadToEndAsync()
    $stream = [IO.File]::OpenRead($InputFile)
    try {
        $copy = $stream.CopyToAsync($process.StandardInput.BaseStream)
        if (!$copy.Wait($Seconds * 1000)) {
            $process.Kill($true); throw 'Restore input time budget exceeded.'
        }
        [void]$copy.GetAwaiter().GetResult(); $process.StandardInput.Close()
        if (!$process.WaitForExit($Seconds * 1000)) {
            $process.Kill($true); throw 'Restore subprocess time budget exceeded.'
        }
        [void]$result.GetAwaiter().GetResult(); [void]$errors.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "Restore subprocess failed: exit=$($process.ExitCode); contents suppressed."
        }
    }
    finally {
        $stream.Dispose(); $process.Dispose()
    }
}
function Get-AdtekTarManifest([string]$Path) {
    $inputStream = [IO.File]::OpenRead($Path)
    $reader = [System.Formats.Tar.TarReader]::new($inputStream, $false)
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $files = [Collections.Generic.List[object]]::new()
    try {
        while ($null -ne ($entry = $reader.GetNextEntry())) {
            $name = $entry.Name
            while ($name.StartsWith('./', [StringComparison]::Ordinal)) {
                $name = $name.Substring(2)
            }
            if ($name -eq '.' -or $name -eq '') {
                if ($entry.EntryType -ne 'Directory') {
                    throw 'Invalid tar root.'
                }; continue
            }
            if ($name.StartsWith('/') -or $name.Contains('\') -or ($name -split '/') -contains '..' -or $name -notmatch '^[A-Za-z0-9_./-]+$') {
                throw 'Unsafe backup member path.'
            }
            if ($entry.EntryType -eq 'Directory') {
                continue
            }
            if ($entry.EntryType -notin @('RegularFile', 'V7RegularFile') -or !$names.Add($name)) {
                throw 'Backup links, special files and duplicate paths are refused.'
            }
            $hash = if ($entry.Length -eq 0) {
                [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([byte[]]@())).ToLowerInvariant()
            }
            else {
                [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($entry.DataStream)).ToLowerInvariant()
            }
            $files.Add([ordered]@{path = $name; bytes = $entry.Length.ToString(); sha256 = $hash })
        }
    }
    finally {
        $reader.Dispose(); $inputStream.Dispose()
    }
    return @($files | Sort-Object { $_.path })
}
function Get-AdtekCanonicalSqlDigest([string]$Path) {
    # MySQL重放SHOW CREATE后会把隐含字符集写成显式CHARACTER SET；只归一化相同utf8mb4排序规则的冗余声明。
    # 限定在建表列定义行，INSERT数据行不替换；不同字符集/排序规则、约束、分区和全部数据仍逐字节参与摘要。
    $reader = [IO.StreamReader]::new($Path, [Text.UTF8Encoding]::new($false, $true))
    $hash = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
    $inTable = $false
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            if ($line.StartsWith('CREATE TABLE `', [StringComparison]::Ordinal)) {
                $inTable = $true
            }
            if ($inTable -and $line -match '^  `[^`]+` ') {
                $line = [regex]::Replace($line, ' CHARACTER SET utf8mb4(?= COLLATE utf8mb4_[A-Za-z0-9_]+(?: |,|$))', '')
            }
            if ($line -match '^\) ENGINE=') {
                $inTable = $false
            }
            $hash.AppendData([Text.Encoding]::UTF8.GetBytes($line + "`n"))
        }
        return [Convert]::ToHexString($hash.GetHashAndReset()).ToLowerInvariant()
    }
    finally {
        $hash.Dispose(); $reader.Dispose()
    }
}
