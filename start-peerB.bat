@echo off
setlocal
cd /d "%~dp0frontend"
echo =========================================
echo   LANDrop Desktop UI - Peer B
echo =========================================
echo Starting Peer B on Port 5002...
call mvn javafx:run -Djavafx.args="--peer-port=5002 --display-name=PeerB --auto-connect=true"
pause
