@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
powershell -ExecutionPolicy Bypass -File "%SCRIPT_DIR%launch-docpilot-with-qdrant.ps1"
exit /b %ERRORLEVEL%