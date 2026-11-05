@echo off
rem Local connection settings for start-api.bat and migrate.bat, matching deploy\compose.infra.yml.
rem Used via "call" after _env.bat (not run directly). Passwords are read by the caller, never printed.
set "ADTEK_SECRETS=%ADTEK_ROOT%\deploy\.secrets"
if not exist "%ADTEK_SECRETS%\mysql-app-password" (echo [ERROR] Local secrets are missing. Run tools\infra.bat start first.& exit /b 1)
set "ADTEK_JAR=%ADTEK_ROOT%\backend\adtek-server\target\adtek-server-0.1.0.jar"
if not exist "%ADTEK_JAR%" (echo [ERROR] Server JAR not found. Run tools\verify.bat first.& exit /b 1)
set "ADTEK_DB_URL=jdbc:mysql://127.0.0.1:33316/adtek_template?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED"
set "ADTEK_DB_USER=adtek_app"
set "ADTEK_MIGRATION_USER=adtek_migration"
set "ADTEK_REDIS_HOST=127.0.0.1"
set "ADTEK_REDIS_PORT=36389"
set "ADTEK_SERVER_ADDRESS=127.0.0.1"
set "ADTEK_SERVER_PORT=18090"
exit /b 0
