@echo off
setlocal
cd /d "%~dp0..\..\backend"
echo =========================================
echo   LANDrop - Auth Server (Phase 15A)
echo =========================================
echo Starting shared Auth + Realtime backend (AUTH_PORT / REALTIME_PORT / BIND_HOST from .env)...
call mvn compile exec:java -Dexec.mainClass="com.landrop.auth.server.AuthHttpServer"
pause
