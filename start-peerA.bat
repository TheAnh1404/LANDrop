@echo off
setlocal
cd /d "%~dp0frontend"
echo =========================================
echo   LANDrop Desktop UI - Peer A
echo =========================================
echo Starting Peer A on Port 5001...
call mvn javafx:run -Djavafx.args="--peer-port=5001 --display-name=PeerA"
pause
