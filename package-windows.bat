@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\package-windows.ps1" -Type app-image
exit /b %errorlevel%
