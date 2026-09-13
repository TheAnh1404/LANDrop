#!/bin/bash
# Starts LANDrop Desktop GUI for Peer D (Receiver, Port 5004)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/frontend" || exit 1
echo "Starting LANDrop Peer D (Port 5004)..."
mvn javafx:run -Djavafx.args="--peer-port=5004 --display-name='Peer D'"
