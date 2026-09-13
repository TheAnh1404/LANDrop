@echo off
setlocal
cd /d "%~dp0"
echo =========================================
echo   LANDrop Desktop UI - Peer A
echo =========================================
echo Starting Peer A on Port 5001...
call mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5001 --display-name=PeerA --auto-connect=true"
pause
