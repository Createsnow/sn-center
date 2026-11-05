@echo off
setlocal
rem Apply Flyway migrations to the local database once with the DDL account, then exit.
rem Usage: tools\migrate.bat   (requires tools\infra.bat start and tools\verify.bat)
call "%~dp0_env.bat" || exit /b 1
call "%~dp0_local-env.bat" || exit /b 1
set /p ADTEK_MIGRATION_PASSWORD=<"%ADTEK_SECRETS%\mysql-migration-password"
java -jar "%ADTEK_JAR%" --spring.profiles.active=migrate
if errorlevel 1 (echo [ERROR] Migration failed; see the output above.& exit /b 1)
echo Migration finished.
exit /b 0
