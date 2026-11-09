@echo off
setlocal
rem Start the API on this machine with the local profile at http://127.0.0.1:18090. Ctrl+C stops it.
rem Usage: tools\start-api.bat   (requires tools\infra.bat start, tools\verify.bat and tools\migrate.bat)
call "%~dp0_env.bat" || exit /b 1
call "%~dp0_local-env.bat" || exit /b 1
set /p ADTEK_DB_PASSWORD=<"%ADTEK_SECRETS%\mysql-app-password"
java -jar "%ADTEK_JAR%" --spring.profiles.active=local
exit /b %ERRORLEVEL%
