@echo off
setlocal
set PORT=%1
if "%PORT%"=="" set PORT=8888

cd /d "%~dp0backend"
echo =========================================
echo   LANDrop - Discovery Server
echo =========================================
echo Starting Discovery Server on port %PORT%...
call mvn exec:java -Dexec.mainClass="com.landrop.discovery.DiscoveryServer" -Dexec.args="%PORT%"
pause
