@echo off
setlocal
cd /d "%~dp0backend"
echo =========================================
echo   LANDrop - Auth Server (Phase 15A)
echo =========================================
echo Starting Authentication Server on port 3000...
call mvn exec:java -Dexec.mainClass="com.landrop.auth.server.AuthHttpServer"
pause
