@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Repair-SaintViewCertificateAuth.ps1"
set "result=%ERRORLEVEL%"
echo.
pause
exit /b %result%
