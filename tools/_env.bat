@echo off
rem Shared prerequisite check, used via "call" by the other scripts (not run directly).
rem Sets ADTEK_ROOT and verifies that JDK 21, Maven 3.9.9+ and Node 24 are on PATH.
for %%I in ("%~dp0..") do set "ADTEK_ROOT=%%~fI"
set "COREPACK_ENABLE_DOWNLOAD_PROMPT=0"
where java >nul 2>nul || (echo [ERROR] java not found. Install JDK 21 and add it to PATH.& exit /b 1)
java -version 2>&1 | findstr /r /c:"version .21\." >nul || (echo [ERROR] JDK 21 is required; check JAVA_HOME and PATH.& exit /b 1)
where mvn >nul 2>nul || (echo [ERROR] mvn not found. Install Maven 3.9.9 or newer 3.x and add it to PATH.& exit /b 1)
where node >nul 2>nul || (echo [ERROR] node not found. Install Node 24 and add it to PATH.& exit /b 1)
node -e "process.exit(process.versions.node.split('.')[0] === '24' ? 0 : 1)" || (echo [ERROR] Node 24 is required.& exit /b 1)
exit /b 0
