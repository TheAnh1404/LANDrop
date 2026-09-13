@echo off
setlocal
cd /d "%~dp0frontend"
echo =========================================
echo   LANDrop Desktop UI - Peer D (Receiver)
echo =========================================
echo Starting Peer D on Port 5004...
call mvn javafx:run -Djavafx.args="--peer-port=5004 --display-name=PeerD --auto-connect=true"
pause
