#!/bin/bash
# Starts LANDrop Desktop GUI for Peer B
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/frontend" || exit 1
echo "Starting LANDrop Peer B (Port 5002)..."
mvn javafx:run -Djavafx.args="--peer-port=5002 --display-name='Peer B' --auto-connect=true"
