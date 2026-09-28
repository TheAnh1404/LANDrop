#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

echo "========================================================"
echo "  LANDrop Desktop UI - Standalone P2P (Offline Mode)   "
echo "========================================================"
echo "Starting LANDrop with UDP Multicast Zero-Config Discovery..."
./mvnw -pl frontend -am -DskipTests install
./mvnw -pl frontend javafx:run '-Djavafx.args=--offline=true'
