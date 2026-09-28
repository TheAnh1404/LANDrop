@echo off
setlocal
cd /d "%~dp0"
echo ========================================================
echo   LANDrop Desktop UI - Che do Mang Noi Bo P2P (Offline)
echo ========================================================
echo Khoi dong LANDrop voi che do tu dong tim kiem UDP Multicast...
call mvn -pl frontend -am -DskipTests install
if errorlevel 1 exit /b 1
call mvn -pl frontend javafx:run "-Djavafx.args=--offline=true"
pause
