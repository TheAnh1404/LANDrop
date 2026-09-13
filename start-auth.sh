#!/usr/bin/env bash
cd "$(dirname "$0")/backend" || exit 1
echo "========================================="
echo "  LANDrop - Auth Server (Phase 15A)"
echo "========================================="
echo "Starting Authentication Server on port 3000..."
mvn exec:java -Dexec.mainClass="com.landrop.auth.server.AuthHttpServer"
