@echo off
setlocal
cd /d "%~dp0"
echo =========================================
echo   LANDrop Desktop UI - Peer D
echo =========================================
echo Starting Peer D on Port 5004...
call mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5004 --display-name=PeerD --auto-connect=true"
pause
