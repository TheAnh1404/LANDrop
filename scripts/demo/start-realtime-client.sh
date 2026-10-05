#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/../.."
mvn -q -pl backend compile dependency:build-classpath -Dmdep.outputFile=target/realtime-classpath.txt
IFS= read -r landrop_client_classpath < backend/target/realtime-classpath.txt || true
exec java -cp "backend/target/classes:${landrop_client_classpath}" com.landrop.realtime.cli.RealtimeClient "$@"
