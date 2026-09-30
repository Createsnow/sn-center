$ErrorActionPreference = 'Stop'
# 只生成本工程.runtime夹具；验证tar越界/链接/重名拒绝和SQL归一化不掩盖数据/排序规则变化。
. (Join-Path $PSScriptRoot 'BackupSupport.ps1')
$root = Join-Path $PSScriptRoot ('.runtime/backup-support-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
function Write-TestTar([string]$Name, [string[]]$Members, [switch]$Link, [switch]$Empty) {
    $path = Join-Path $root $Name
    $stream = [IO.File]::Create($path); $writer = [System.Formats.Tar.TarWriter]::new($stream, $false)
    try {
        foreach ($member in $Members) {
            $type = if ($Link) {
                [System.Formats.Tar.TarEntryType]::SymbolicLink
            }
            else {
                [System.Formats.Tar.TarEntryType]::RegularFile
            }
            $entry = [System.Formats.Tar.PaxTarEntry]::new($type, $member)
            if ($Link) {
                $entry.LinkName = '../outside'
            }
            else {
                $entry.DataStream = [IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes($(if ($Empty) {
                                ''
                            }
                            else {
                                '中文,9007199254740993'
                            })))
            }
            try {
                $writer.WriteEntry($entry)
            }
            finally {
                if ($entry.DataStream) {
                    $entry.DataStream.Dispose()
                }
            }
        }
    }
    finally {
        $writer.Dispose(); $stream.Dispose()
    }
    return $path
}
function Assert-Rejected([string]$Path) {
    $rejected = $false
    try {
        [void](Get-AdtekTarManifest $Path)
    }
    catch {
        $rejected = $true
    }
    if (!$rejected) {
        throw 'Unsafe tar was accepted.'
    }
}
$valid = Write-TestTar 'valid.tar' @('./objects/valid.csv')
$entries = @(Get-AdtekTarManifest $valid)
$expected = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes('中文,9007199254740993'))).ToLowerInvariant()
if ($entries.Count -ne 1 -or $entries[0].sha256 -ne $expected -or $entries[0].path -ne 'objects/valid.csv') {
    throw 'Valid tar fingerprint failed.'
}
$empty = Write-TestTar 'empty.tar' @('objects/empty.csv') -Empty
if (@(Get-AdtekTarManifest $empty)[0].bytes -ne '0') {
    throw 'Empty file handling failed.'
}
Assert-Rejected (Write-TestTar 'escape.tar' @('../escape.csv'))
Assert-Rejected (Write-TestTar 'absolute.tar' @('/absolute.csv'))
Assert-Rejected (Write-TestTar 'link.tar' @('objects/link.csv') -Link)
Assert-Rejected (Write-TestTar 'duplicate.tar' @('objects/same.csv', 'objects/same.csv'))
$sql = @'
CREATE TABLE `example` (
  `name` varchar(20) COLLATE utf8mb4_0900_as_cs NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
INSERT INTO `example` VALUES ('CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs');
'@
$source = Join-Path $root 'source.sql'; $same = Join-Path $root 'same.sql'; $data = Join-Path $root 'data.sql'; $collation = Join-Path $root 'collation.sql'
[IO.File]::WriteAllText($source, $sql, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($same, $sql.Replace('varchar(20) COLLATE', 'varchar(20) CHARACTER SET utf8mb4 COLLATE'), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($data, $sql.Replace("VALUES ('CHARACTER", "VALUES ('changed CHARACTER"), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($collation, $sql.Replace('utf8mb4_0900_as_cs NOT NULL', 'utf8mb4_0900_ai_ci NOT NULL'), [Text.UTF8Encoding]::new($false))
$hash = Get-AdtekCanonicalSqlDigest $source
if ((Get-AdtekCanonicalSqlDigest $same) -ne $hash) {
    throw 'Redundant charset normalization failed.'
}
if ((Get-AdtekCanonicalSqlDigest $data) -eq $hash) {
    throw 'Changed data was hidden.'
}
if ((Get-AdtekCanonicalSqlDigest $collation) -eq $hash) {
    throw 'Changed collation was hidden.'
}
Write-Output 'Backup support: 9 checks passed (valid/empty/path/link/duplicate tar, equivalent DDL, changed data/collation).'
