@echo off
setlocal
cd /d "%~dp0frontend"
echo =========================================
echo   LANDrop Desktop UI - Peer C
echo =========================================
echo Starting Peer C on Port 5003...
call mvn javafx:run -Djavafx.args="--peer-port=5003 --display-name=PeerC"
pause
