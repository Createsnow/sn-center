# 本工程容器工具共用发现逻辑；仅返回已安装CLI，不启动可见窗口或下载程序。
function Get-AdtekDocker {
    $command=Get-Command docker -ErrorAction SilentlyContinue
    if($command) { return $command.Source }
    $candidates=@((Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),'C:/Program Files/Docker/Docker/resources/bin/docker.exe')
    $path=$candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if(!$path) { throw 'Docker CLI not found; run tools/install-docker.ps1.' }
    return $path
}
