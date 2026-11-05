@echo off
setlocal
rem Run the same gates as CI: backend build and tests, frontend checks and build, documentation links.
rem Usage: tools\verify.bat                backend unit/architecture tests + frontend + docs
rem        tools\verify.bat integration    additionally run *IT tests (needs Docker Desktop for Testcontainers)
call "%~dp0_env.bat" || exit /b 1
set "PROFILE="
if /i "%~1"=="integration" set "PROFILE=-Pintegration"
cd /d "%ADTEK_ROOT%\backend" || exit /b 1
call mvn -B -ntp %PROFILE% verify || goto :failed
cd /d "%ADTEK_ROOT%\frontend" || exit /b 1
if exist "openapi\adtek-template.json" (
  call corepack pnpm api:check || goto :failed
)
for %%T in (lint format:check format:config:check test build) do (
  call corepack pnpm %%T || goto :failed
)
cd /d "%ADTEK_ROOT%" || exit /b 1
node tools\check-docs.mjs || goto :failed
echo All checks passed. Artifacts: backend\adtek-server\target\adtek-server-0.1.0.jar and frontend\dist
exit /b 0
:failed
echo [ERROR] Verification failed; see the output above.
exit /b 1
