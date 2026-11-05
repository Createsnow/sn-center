@echo off
setlocal
rem Format the whole repository, or only check it.
rem Usage: tools\format.bat          apply Java/XML/POM, frontend and configuration formatting
rem        tools\format.bat check    fail if anything is not formatted
call "%~dp0_env.bat" || exit /b 1
set "MVN_GOAL=spotless:apply"
set "WEB_TASK=format"
set "CFG_TASK=format:config"
if /i "%~1"=="check" (
  set "MVN_GOAL=spotless:check"
  set "WEB_TASK=format:check"
  set "CFG_TASK=format:config:check"
)
cd /d "%ADTEK_ROOT%\backend" || exit /b 1
call mvn -B -ntp %MVN_GOAL% checkstyle:check || goto :failed
cd /d "%ADTEK_ROOT%\frontend" || exit /b 1
call corepack pnpm %WEB_TASK% || goto :failed
call corepack pnpm %CFG_TASK% || goto :failed
echo Formatting finished.
exit /b 0
:failed
echo [ERROR] Formatting failed; run tools\format.bat and review the changes.
exit /b 1
