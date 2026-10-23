@echo off
setlocal
rem The messages below are UTF-8; switch the console code page so cmd.exe reads and prints them correctly.
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Repair-SaintViewCertificateAuth.ps1" -Apply
set "result=%ERRORLEVEL%"
echo.
echo PACS를 종료한 뒤 다시 실행해야 변경된 인증 브로커 설정이 적용됩니다.
pause
exit /b %result%
