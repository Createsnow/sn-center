@echo off
setlocal
rem Copy this template into a new project directory.
rem Usage: tools\new-project.bat --destination ..\my-project --name my-project [--mysql-port 33326] [--api-port 18100] [--web-port 15193] [--deploy-port 18180]
where node >nul 2>nul || (echo [ERROR] Node 24 is required.& exit /b 1)
node "%~dp0new-project.mjs" %*
exit /b %ERRORLEVEL%
