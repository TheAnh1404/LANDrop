#!/bin/bash
# Starts LANDrop Desktop GUI for Peer D (Receiver, Port 5004)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1
echo "Starting LANDrop Peer D (Port 5004)..."
mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5004 --display-name='Peer D' --auto-connect=true"
