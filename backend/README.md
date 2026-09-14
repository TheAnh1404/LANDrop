# LANDrop — Backend

## Current Phase: Phase 16A — Workspace & Members backend

The shared backend hosts Phase 15A HTTP authentication, authenticated WebSocket chat with PostgreSQL persistence, and the Phase 16A workspace/member API. Read the [workspace contract](../docs/PHASE-16A-API-CONTRACT.md) and the [multi-machine realtime guide](../docs/PHASE-15.5A-MULTI-DEVICE.md). Run `bash start-auth.sh` (or `start-auth.bat`) from the repository root after applying the existing migrations. The Core sections below describe the separate LAN transport.

### Overview
LANDrop Backend provides high-performance peer-to-peer (P2P) networking, centralized discovery, real-time messaging, resumable chunked file transfers, and multi-peer parallel swarm downloads using Java 21 virtual threads and standard Java TCP sockets (`java.net.Socket`, `java.net.ServerSocket`).

---

## 🛠 System Requirements
* **Java**: OpenJDK 21 LTS or higher
* **Maven**: Apache Maven 3.9+
* **Operating Systems**: macOS (Apple Silicon / Intel), Linux, Windows 10/11

---

## 📦 Build Instructions

Build and package backend JAR:
```bash
mvn clean package -pl backend
```
Artifact generated: `backend/target/landrop-0.1.0-SNAPSHOT.jar`

Run backend tests:
```bash
mvn test -pl backend
```

---

## 🚀 Execution & CLI Commands

### 1. Discovery Server
Start the centralized Discovery Server (default port: `8888`):
```bash
mvn -pl backend exec:java -Dexec.mainClass="com.landrop.discovery.DiscoveryServer" -Dexec.args="8888"
```
Or with custom port via system property or environment variable:
```bash
java -Dlandrop.discovery.port=8888 -cp backend/target/landrop-0.1.0-SNAPSHOT.jar com.landrop.discovery.DiscoveryServer
```

### 2. Interactive P2P Chat Peer (CLI Mode)
Start Peer A (Local P2P Port 5001, registered to Discovery at localhost:8888):
```bash
mvn -pl backend exec:java -Dexec.mainClass="com.landrop.network.Main" -Dexec.args="peer 5001 localhost 8888 Alice"
```

Start Peer B (Local P2P Port 5002):
```bash
mvn -pl backend exec:java -Dexec.mainClass="com.landrop.network.Main" -Dexec.args="peer 5002 localhost 8888 Bob"
```

Inside the CLI, use commands:
* `/peers` — list online peers
* `/connect <peerId>` — establish direct P2P TCP connection
* `/ping` — measure sub-millisecond round-trip time (RTT)
* `/help` — display command help
* `/quit` — cleanly unregister and exit

---

## 📐 Architecture & Protocol

### 1. Wire Protocol Framing (8-Byte Header)
```text
+----------+----------+----------+----------+
| Version  | Msg Type | Length   | RequestID|
| (1 byte) | (1 byte) | (4 bytes)| (8 bytes)|
+----------+----------+----------+----------+
|                 Payload                    |
|             (Length bytes)                 |
+--------------------------------------------+
```

* **Version**: 1 byte (`CURRENT_VERSION = 1`).
* **Msg Type**: 1 byte (`HELLO=1`, `CHAT_MESSAGE=2`, `PING=3`, `PONG=4`, `FILE_OFFER=5`, `FILE_REQUEST=6`, `CHUNK_REQUEST=7`, `CHUNK_RESPONSE=8`, `TRANSFER_ACK=9`, `FILE_DATA=17`, `RESUME_REQUEST=18`, `RESUME_RESPONSE=19`, `FILE_QUERY=20`, `FILE_QUERY_RESPONSE=21`).
* **Length**: 4 bytes signed integer (`0 <= Length <= 2 MB` enforced by `PacketDecoder`).
* **RequestID**: 8 bytes signed long (monotonic correlation between request and response packets).
* **Payload**: UTF-8 JSON or raw binary chunk bytes.

### 2. Multi-Peer Parallel Swarm Download Architecture
* **Query Availability**: Receiver issues `FILE_QUERY` to all discovered peers.
* **Worker Pools**: Each available peer is assigned a dedicated `PeerDownloadWorker` operating over an isolated `FILE_TRANSFER` TCP socket connection.
* **Bounded Round-Robin Scheduling**: `ChunkScheduler` assigns pending missing chunks across connected workers up to `maxActiveChunksPerPeer = 2`.
* **Positional FileChannel Writes**: Chunks are written directly into `.landrop.part` using concurrent positional writes (`FileChannel.write(buffer, offset)`).
* **Failover & Dynamic Late Peer Join**: If a peer disconnects, its in-flight chunks automatically revert to `MISSING` and are rescheduled to surviving peers. If a new peer joins mid-transfer, it is seamlessly scheduled remaining chunks.
* **Integrity Validation**: Streaming SHA-256 hash is computed and verified against expected hash before atomic rename to the final file.

---

## ⚙️ Operational Configuration (`AppConfig`)

All operational settings have sensible production defaults and can be overridden via Java System Properties or Environment Variables:

| Configuration Property | Environment Variable | Default Value | Description |
| :--- | :--- | :--- | :--- |
| `landrop.discovery.port` | `DISCOVERY_PORT` | `8888` | Port for Discovery Server |
| `landrop.discovery.host` | `DISCOVERY_HOST` | `localhost` | Hostname of Discovery Server |
| `landrop.discovery.heartbeat.interval.sec` | `DISCOVERY_HEARTBEAT_SEC` | `5` | Heartbeat keep-alive interval |
| `landrop.discovery.timeout.ms` | `DISCOVERY_TIMEOUT_MS` | `15000` | Peer timeout eviction threshold |
| `landrop.peer.port` | `PEER_PORT` | `5001` | Default local P2P listening port |
| `landrop.peer.advertised.host` | `ADVERTISED_HOST` | `127.0.0.1` | Host address advertised to peers |
| `landrop.peer.connect.timeout.ms` | `CONNECT_TIMEOUT_MS` | `5000` | Socket connection timeout (ms) |
| `landrop.peer.socket.timeout.ms` | `SOCKET_TIMEOUT_MS` | `10000` | Socket read timeout (ms) |
| `landrop.transfer.chunk.size` | `CHUNK_SIZE` | `1048576` (1 MB) | Transfer chunk size |
| `landrop.transfer.chunk.timeout.ms` | `CHUNK_TIMEOUT_MS` | `5000` | Per-chunk response timeout (ms) |
| `landrop.transfer.max.chunk.retries` | `MAX_CHUNK_RETRIES` | `3` | Max retries before reassignment |
| `landrop.transfer.max.reconnect.attempts` | `MAX_RECONNECT_ATTEMPTS` | `5` | Reconnection backoff attempts |
| `landrop.log.level` | `LANDROP_LOG_LEVEL` | `INFO` | Logging level (`DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `landrop.log.timestamps` | `LANDROP_LOG_TIMESTAMPS` | `false` | Enable timestamp prefix in logs |

---

## 🔍 Logging & Observability (`AppLogger`)

LANDrop uses zero-dependency structured logging covering all 18 critical operational events:
* `[INFO]` Application startup & shutdown
* `[INFO] [REGISTER]` Discovery registration & eviction
* `[INFO] [HEARTBEAT]` Keep-alive pings
* `[DEBUG] [P2P]` Peer connections, handshakes, disconnects
* `[INFO] [TRANSFER]` Single-peer file transfers, resume negotiations
* `[INFO] [MULTI-PEER]` Swarm download startup, progress milestones, completion
* `[DEBUG] [WORKER]` Chunk assignments, per-chunk completion metrics
* `[WARN] [WORKER]` Timeouts, socket dropoffs, failover reassignment
* `[INFO] [VERIFY]` Streaming SHA-256 verification results

---

## ⚡ Performance Baseline

Measurements conducted on local TCP loopback with a 2.0 MiB test payload (32 chunks × 64 KB):
* **Single-Peer Download**: 24 ms (~83 MB/s)
* **Multi-Peer Download (2 Peers)**: 10 ms (~200 MB/s) — **2.4x speedup** with 50/50 chunk split
* **Mid-Transfer Failover Recovery**: 29 ms (Peer severed mid-transfer; 100% recovered by surviving peer)
* **Integrity**: 100% SHA-256 verified

---

## 📊 Phase Status Matrix
* [x] **Phase 1**: TCP P2P Core
* [x] **Phase 2**: Custom Binary Protocol
* [x] **Phase 3**: Real-time Chat
* [x] **Phase 4**: Peer Discovery
* [x] **Phase 5**: JavaFX Desktop UI
* [x] **Phase 6**: Basic P2P File Transfer
* [x] **Phase 7**: Chunking & SHA-256 Verification
* [x] **Phase 8**: Resume & Retry
* [x] **Phase 9**: Multi-Peer Swarm Download
* [x] **Phase 10**: End-to-End System Validation
* [x] **Phase 11**: Production Hardening & Observability
