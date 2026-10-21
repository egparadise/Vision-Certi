@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0Repair-BlankCertificateWindow.ps1" (
  echo Repair-BlankCertificateWindow.ps1 was not found next to Repair.cmd.
  pause
  exit /b 1
)
echo Close SaintView PACS completely before running this repair.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Repair-BlankCertificateWindow.ps1" -RepairCache
set "repairExit=%ERRORLEVEL%"
echo.
echo Keep this result if the certificate window is still blank.
pause
exit /b %repairExit%
