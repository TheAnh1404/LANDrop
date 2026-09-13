@echo off
setlocal
cd /d "%~dp0"
echo =========================================
echo   LANDrop Desktop UI - Peer B
echo =========================================
echo Starting Peer B on Port 5002...
call mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5002 --display-name=PeerB --auto-connect=true"
pause
