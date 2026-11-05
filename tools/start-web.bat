@echo off
setlocal
rem Start the Vite dev server at http://127.0.0.1:15183; /api is proxied to the local API on 18090. Ctrl+C stops it.
rem Usage: tools\start-web.bat
call "%~dp0_env.bat" || exit /b 1
cd /d "%ADTEK_ROOT%\frontend" || exit /b 1
call corepack pnpm dev
exit /b %ERRORLEVEL%
