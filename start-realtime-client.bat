@echo off
setlocal
cd /d "%~dp0"
call mvn -q -pl backend compile dependency:build-classpath -Dmdep.outputFile=target/realtime-classpath.txt
if errorlevel 1 exit /b 1
set /p landrop_client_classpath=<backend\target\realtime-classpath.txt
java -cp "backend\target\classes;%landrop_client_classpath%" com.landrop.realtime.cli.RealtimeClient %*
