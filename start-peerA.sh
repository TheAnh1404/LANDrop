#!/bin/bash
# Starts LANDrop Desktop GUI for Peer A
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/frontend" || exit 1
echo "Starting LANDrop Peer A (Port 5001)..."
mvn javafx:run -Djavafx.args="--peer-port=5001 --display-name='Peer A'"
