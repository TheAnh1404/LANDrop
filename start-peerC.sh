#!/bin/bash
# Starts LANDrop Desktop GUI for Peer C (Port 5003)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1
echo "Starting LANDrop Peer C (Port 5003)..."
mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5003 --display-name='Peer C' --auto-connect=true"
