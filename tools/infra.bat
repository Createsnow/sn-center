@echo off
setlocal
rem Local MySQL in Docker Desktop (Linux engine), defined by deploy\compose.infra.yml.
rem Usage: tools\infra.bat start|stop|status
rem start creates random local secrets in deploy\.secrets once; stop keeps the data volumes.
for %%I in ("%~dp0..") do set "ADTEK_ROOT=%%~fI"
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=status"
where docker >nul 2>nul || (echo [ERROR] Docker CLI not found. Install Docker Desktop with the Linux engine.& exit /b 1)
cd /d "%ADTEK_ROOT%\deploy" || exit /b 1
if /i "%ACTION%"=="start" goto :start
if /i "%ACTION%"=="stop" goto :stop
if /i "%ACTION%"=="status" goto :status
echo Usage: tools\infra.bat start^|stop^|status
exit /b 2

:start
where node >nul 2>nul || (echo [ERROR] Node 24 is required to create local secrets.& exit /b 1)
node "%ADTEK_ROOT%\tools\init-secrets.mjs" || exit /b 1
docker compose -f compose.infra.yml up -d --wait --wait-timeout 180 || goto :failed
echo MySQL 127.0.0.1:33326 database sn_center. Passwords stay in deploy\.secrets and are not printed.
exit /b 0

:stop
docker compose -f compose.infra.yml stop || goto :failed
exit /b 0

:status
docker compose -f compose.infra.yml ps || goto :failed
exit /b 0

:failed
echo [ERROR] docker compose %ACTION% failed. Check that Docker Desktop is running.
exit /b 1
