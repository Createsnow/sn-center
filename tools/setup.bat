@echo off
setlocal
rem One-time setup on a Windows workstation: checks tools and installs locked frontend dependencies.
rem Usage: tools\setup.bat
call "%~dp0_env.bat" || exit /b 1
cd /d "%ADTEK_ROOT%\frontend" || exit /b 1
call corepack pnpm install --frozen-lockfile || goto :failed
echo Setup finished. Next: tools\verify.bat
exit /b 0
:failed
echo [ERROR] Setup failed; see the output above.
exit /b 1
