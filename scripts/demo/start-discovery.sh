#!/bin/bash
# Starts the LANDrop Centralized Discovery Server
PORT=${1:-8888}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../backend" || exit 1
echo "Starting LANDrop Discovery Server on port $PORT..."
mvn exec:java -Dexec.mainClass="com.landrop.discovery.DiscoveryServer" -Dexec.args="$PORT"
