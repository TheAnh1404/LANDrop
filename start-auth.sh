#!/usr/bin/env bash
cd "$(dirname "$0")/backend" || exit 1
echo "========================================="
echo "  LANDrop - Auth Server (Phase 15A)"
echo "========================================="
echo "Starting shared Auth + Realtime backend (AUTH_PORT / REALTIME_PORT / BIND_HOST from .env)..."
mvn compile exec:java -Dexec.mainClass="com.landrop.auth.server.AuthHttpServer"
