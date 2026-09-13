# LANDrop

A high-performance, decentralized peer-to-peer (P2P) file transfer and real-time messaging application built for Local Area Networks (LAN).

---

## 1. Overview

**LANDrop** solves the fundamental problem of transferring files and messages between disparate computing devices on the same local network without relying on third-party cloud storage, external internet bandwidth, or centralized file-hosting servers.

Traditional file sharing methods (email attachments, USB flash drives, or cloud relays like Google Drive and Dropbox) suffer from latency, file size limits, external data exposure, and internet upload throttling. LANDrop utilizes a **hybrid peer-to-peer (P2P) architecture**:
- A lightweight **Discovery Server** coordinates presence and IP/port lookups.
- All actual chat and high-volume file transfers travel across **direct point-to-point TCP sockets**.
- For large files, LANDrop employs **multi-peer concurrent downloading (swarming)**, retrieving distinct file chunks simultaneously from multiple source peers to maximize local network throughput.

---

## 2. Key Features

- **Automatic Peer Discovery**: Automatic local network registration and presence tracking via lightweight periodic heartbeats.
- **Direct P2P Communication**: Full-duplex raw TCP sockets for direct, private node-to-node exchange without intermediate data relays.
- **Real-Time P2P Chat**: Low-latency interactive text messaging with delivery status and conversation history.
- **Single-Peer Direct File Transfer**: Streamlined file sharing with streaming chunk transmission, real-time progress, and resume on disconnect.
- **Multi-Peer Concurrent Download (Swarming)**: Concurrent multi-source downloads using chunk partitioning and positional `FileChannel` writes.
- **Dynamic Peer Joining**: Transparently balances and partitions remaining chunks when a new source peer joins mid-download.
- **Fault-Tolerant Failover**: Automatically detects peer disconnections, rolls back in-flight chunks to `MISSING`, and reassigns them to surviving peers.
- **Cryptographic SHA-256 Verification**: Streaming end-to-end checksum calculation ensures 100% bit-exact data integrity before final file promotion.
- **Path Traversal Protection**: Comprehensive filename sanitization preventing directory traversal exploits (`../`).
- **Configurable Runtime**: Full configuration hierarchy supporting System Properties, Environment Variables, and sensible production defaults.
- **Structured Observability**: Built-in zero-dependency structured logger with configurable log levels and sensitive data masking.

---

## 3. Technology Stack

- **Platform**: Java 21 LTS
- **UI Framework**: JavaFX 21 (FXML, MVC architecture)
- **Build System**: Apache Maven 3.9+ (Multi-module)
- **Networking**: Standard Java Sockets (`java.net.Socket`, `java.net.ServerSocket`)
- **Protocol**: Custom binary TCP application protocol with a fixed 14-byte framing header
- **Concurrency**: Java 21 Virtual Threads (`Thread.ofVirtual()`)
- **File I/O**: Java NIO (`FileChannel` positional writes, atomic file operations)
- **Integrity**: Standard Java Security API (`java.security.MessageDigest` - SHA-256)
- **Serialization**: Jackson Databind (JSON payloads for control messages)
- **Testing**: JUnit 5, AssertJ, TestFX

---

## 4. Architecture

LANDrop uses a hybrid architecture separating control plane from data plane:
- **Control Plane (Discovery)**: Discovery Server runs on port `8888`. Peers register their presence, periodically send heartbeats, and request active peer lists.
- **Data Plane (Direct P2P)**: Direct TCP connections established between peer nodes. Connection purposes are negotiated during handshake (`CHAT` vs `FILE_TRANSFER`), ensuring bulk transfers never interfere with interactive chat.

```text
               +---------------------------+
               |     Discovery Server      |
               |       (Port 8888)         |
               +-------------+-------------+
                             ^
      Heartbeats & Registry  |  (Control Plane)
       +---------------------+---------------------+
       |                     |                     |
+------+------+       +------+------+       +------+------+
|   Peer A    |<=====>|   Peer B    |<=====>|   Peer C    |
| (Port 5001) |  TCP  | (Port 5002) |  TCP  | (Port 5003) |
+------+------+       +------+------+       +------+------+
       ^                     ^                     ^
       |    Direct TCP Swarm | (Data Plane)        |
       +=====================+=====================+
                             |
                      +------+------+
                      |   Peer D    |
                      |  (Receiver) |
                      | (Port 5004) |
                      +-------------+
```

For comprehensive architectural details and sequence flows, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## 5. Project Structure

```text
LANDrop/
├── pom.xml                               # Root Maven Aggregator POM
├── README.md                             # Authoritative project entry point
├── start-discovery.sh / .bat             # Discovery server launcher (Port 8888)
├── start-peerA.sh / .bat                 # Peer A GUI launcher (Port 5001)
├── start-peerB.sh / .bat                 # Peer B GUI launcher (Port 5002)
├── start-peerC.sh / .bat                 # Peer C GUI launcher (Port 5003)
├── start-peerD.sh / .bat                 # Peer D Receiver GUI launcher (Port 5004)
├── docs/                                 # Technical documentation
│   ├── ARCHITECTURE.md                   # System design & sequence diagrams
│   ├── PROTOCOL.md                       # 14-byte wire framing & message schemas
│   ├── DEMO.md                           # 7-scenario reproducible presentation guide
│   ├── DEFENSE.md                        # Technical defense questions & answers
│   ├── TEST_PLAN.md                      # Comprehensive test matrix (182 tests)
│   └── PERFORMANCE.md                    # Verified benchmark metrics & analysis
├── backend/                              # Network, Protocol & Storage Module
│   ├── pom.xml
│   ├── README.md
│   └── src/
│       ├── main/java/com/landrop/
│       │   ├── config/                   # Centralized AppConfig
│       │   ├── util/                     # Structured AppLogger
│       │   ├── protocol/                 # Framed Packet, Encoders, Decoders
│       │   ├── discovery/                # DiscoveryServer & DiscoveryClient
│       │   ├── peer/                     # PeerConnection & PeerServer
│       │   ├── chat/                     # ChatService & History
│       │   └── transfer/                 # Swarm Coordinator, Scheduler, Workers
│       └── test/java/com/landrop/        # 170 unit, integration & benchmark tests
└── frontend/                             # JavaFX Presentation Module
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/landrop/ui/      # MainApp, Controllers & Reactive State
        │   └── resources/com/landrop/ui/ # FXML layouts, icons, and styles
        └── test/java/com/landrop/ui/     # 12 UI unit & integration tests
```

---

## 6. Requirements

- **JDK**: Java Development Kit 21 or higher (OpenJDK or Eclipse Temurin recommended)
- **Maven**: Apache Maven 3.9.0 or higher
- **Operating Systems**: macOS 12+, Ubuntu Linux 20.04+, or Windows 10/11
- **Network Environment**: Local network interface (Loopback `127.0.0.1` or Local Subnet)

---

## 7. Build

Build the multi-module project and generate runnable JAR artifacts:

```bash
# Clean, compile, execute automated test suite, and package JARs
mvn clean package
```

Upon completion, the target JAR artifacts will be located at:
- `backend/target/landrop-0.1.0-SNAPSHOT.jar`
- `frontend/target/landrop-frontend-0.1.0-SNAPSHOT.jar`

---

## 8. Run Discovery Server

### macOS / Linux:
```bash
./start-discovery.sh
# Optional: specify custom port
./start-discovery.sh 8888
```

### Windows:
```cmd
start-discovery.bat
:: Optional: specify custom port
start-discovery.bat 8888
```

### Manual Maven Command:
```bash
mvn exec:java -pl backend -Dexec.mainClass="com.landrop.discovery.DiscoveryServer" -Dexec.args="8888"
```

---

## 9. Run Peer

To start a single LANDrop desktop application instance:

```bash
# Launch peer with custom name and port
mvn javafx:run -pl frontend -Djavafx.args="--peer-port=5001 --display-name='Peer A'"
```

---

## 10. Run Multiple Peers

To demonstrate multi-node swarming on a single computer, launch distinct peer instances on non-conflicting ports:

| Peer Node | Role | Port | macOS / Linux Launcher | Windows Launcher |
| :--- | :--- | :---: | :--- | :--- |
| **Peer A** | Source Node | `5001` | `./start-peerA.sh` | `start-peerA.bat` |
| **Peer B** | Source Node | `5002` | `./start-peerB.sh` | `start-peerB.bat` |
| **Peer C** | Source Node | `5003` | `./start-peerC.sh` | `start-peerC.bat` |
| **Peer D** | Receiver Node | `5004` | `./start-peerD.sh` | `start-peerD.bat` |

---

## 11. Demo Workflow

The complete multi-peer swarming and failover demo follows this sequence:

```text
Discovery Server (Port 8888)
        │
        ├── 1. Launch Peer A (5001), Peer B (5002), Peer C (5003)
        ├── 2. Launch Receiver Peer D (5004)
        │
        ▼
   Peer Discovery (All peers register & populate online lists)
        │
        ▼
   Direct P2P Chat (Verify real-time messaging between A and B)
        │
        ▼
   Multi-Peer Download Request (Peer D queries file on A, B, C)
        │
        ▼
   Concurrent Chunk Swarm (A, B, C transmit distinct chunks to D)
        │
        ▼
   Peer Failure Simulation (Disconnect Peer B mid-download)
        │
        ▼
   Automatic Chunk Reassignment (Surviving peers take over B's chunks)
        │
        ▼
   Cryptographic Assembly (100% SHA-256 match & atomic file creation)
```

For the complete presentation script and commands, see [docs/DEMO.md](docs/DEMO.md).

---

## 12. Configuration

LANDrop provides centralized configuration through [`AppConfig.java`](file:///Users/theanhmac/Project/LANDrop/backend/src/main/java/com/landrop/config/AppConfig.java). Settings follow a strict priority hierarchy:
1. **Java System Properties** (`-Dlandrop.*` or `-D<key>`)
2. **Environment Variables** (`LANDROP_*` or standard uppercase keys)
3. **Hardcoded Defaults**

### Configuration Reference

| Parameter | System Property | Environment Variable | Default Value | Description |
| :--- | :--- | :--- | :---: | :--- |
| **Discovery Port** | `landrop.discovery.port` | `DISCOVERY_PORT` | `8888` | Port for the central discovery server |
| **Discovery Host** | `landrop.discovery.host` | `DISCOVERY_HOST` | `localhost` | Hostname/IP of discovery server |
| **Heartbeat Interval** | `landrop.discovery.heartbeat.interval.sec` | `DISCOVERY_HEARTBEAT_SEC` | `5` | Heartbeat interval (seconds) |
| **Discovery Timeout** | `landrop.discovery.timeout.ms` | `DISCOVERY_TIMEOUT_MS` | `15000` | Peer expiration threshold (ms) |
| **Peer Port** | `landrop.peer.port` | `PEER_PORT` | `5001` | Inbound TCP listener port |
| **Advertised Host** | `landrop.peer.advertised.host` | `ADVERTISED_HOST` | `127.0.0.1` | IP advertised to other peers |
| **Connect Timeout** | `landrop.peer.connect.timeout.ms` | `CONNECT_TIMEOUT_MS` | `5000` | Peer TCP connection timeout (ms) |
| **Socket Timeout** | `landrop.peer.socket.timeout.ms` | `SOCKET_TIMEOUT_MS` | `10000` | Socket read timeout (`SoTimeout`) (ms) |
| **Chunk Size** | `landrop.transfer.chunk.size` | `CHUNK_SIZE` | `1048576` | Transfer chunk size (1 MiB default) |
| **Max Chunk Retries**| `landrop.transfer.max.chunk.retries` | `MAX_CHUNK_RETRIES` | `3` | Retries before chunk failover |
| **Chunk Timeout** | `landrop.transfer.chunk.timeout.ms` | `CHUNK_TIMEOUT_MS` | `5000` | Max wait for `CHUNK_RESPONSE` (ms) |
| **Download Folder** | `landrop.transfer.download.dir` | `DOWNLOAD_DIR` | `~/Downloads/LANDrop` | Destination directory for files |
| **Log Level** | `landrop.log.level` | `LANDROP_LOG_LEVEL` | `INFO` | Logging threshold (`DEBUG`, `INFO`, `WARN`, `ERROR`) |

---

## 13. Logging & Observability

LANDrop includes a lightweight, zero-dependency structured logger ([`AppLogger.java`](file:///Users/theanhmac/Project/LANDrop/backend/src/main/java/com/landrop/util/AppLogger.java)):
- **Standard Format**: `[ISO-8601 UTC] [LEVEL] [Thread] [Tag] Message`
- **Supported Levels**: `DEBUG`, `INFO`, `WARN`, `ERROR`
- **Sensitive Data Masking**: Automatically sanitizes IP addresses, file paths, and potential tokens.

Example log output:
```text
[2026-09-13T14:30:00.123Z] [INFO ] [peer-worker-1] [MULTI-PEER] Assigned chunk 4 to peer Peer A
[2026-09-13T14:30:00.250Z] [INFO ] [peer-worker-2] [WORKER] Chunk 5 completed from Peer B (6/32)
[2026-09-13T14:30:01.010Z] [INFO ] [multi-peer-verify] [VERIFY] Multi-peer SHA-256 verification successful: 4717dc8365...
```

---

## 14. Testing

The project maintains **100% automated test coverage** across all networking, protocol framing, concurrency, swarming, and edge-case scenarios:

- **Backend Automated Tests**: **170** passed (Protocol, Discovery, Networking, Chunk Scheduler, Failover, Benchmarks)
- **Frontend Automated Tests**: **12** passed (UI Controller lifecycles, FXML layouts, State dispatchers)
- **Total Test Count**: **182 passed, 0 failures, 0 errors, 0 skipped**

Execute the complete automated test suite:
```bash
mvn clean test
```

For the detailed test matrix across all 11 functional domains, see [docs/TEST_PLAN.md](docs/TEST_PLAN.md).

---

## 15. Performance

Performance was empirically measured under local loopback conditions using [`PerformanceBaselineTest.java`](file:///Users/theanhmac/Project/LANDrop/backend/src/test/java/com/landrop/benchmark/PerformanceBaselineTest.java):

> [!NOTE]
> **Loopback Disclaimer**: The benchmark was executed over local loopback TCP (`127.0.0.1`) on Apple Silicon. These values reflect memory and socket buffer speeds and are **not representative of WAN / Internet performance**.

### Phase 11 Verified Benchmark Results (2.0 MiB Payload, 32 Chunks):
- **Single-Peer Download**: **24 ms** (~83.33 MB/s throughput)
- **Multi-Peer Download (2 Peers)**: **10 ms** (~200.00 MB/s throughput, **~2.4x speedup**, 15/17 chunk distribution)
- **Mid-Transfer Failover**: **29 ms** (Peer B severed at chunk 4; Peer A completed all remaining chunks seamlessly)
- **Data Integrity**: **100% SHA-256 match across all runs**

For full benchmark analysis, see [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

---

## 16. Security & Hardening

1. **Path Traversal Protection**: Received filenames are sanitized to prevent directory traversal (`../`, absolute paths, null bytes). Files are constrained to the designated download folder.
2. **Cryptographic Integrity Validation**: Files are received into `.landrop.part` temporary storage and verified against expected SHA-256 hashes before atomic promotion.
3. **Framing & Memory Limits**: `PacketDecoder` enforces strict payload limits (max 2 MiB) and positive length bounds to prevent out-of-memory (OOM) exploits.
4. **Log Sanitization**: Sensitive operational values (passwords, auth tokens) are masked prior to terminal or log file emission.

---

## 17. Troubleshooting

| Issue | Cause | Solution |
| :--- | :--- | :--- |
| **`Address already in use`** | Another process is using port `8888` or `5001-5004`. | Identify process (`lsof -i :8888` or `netstat -ano \| findstr :8888`) or specify an alternate port via `-Dpeer.port=5005`. |
| **`Discovery unavailable`** | Discovery server is not running or blocked by local firewall. | Launch `./start-discovery.sh` first and ensure inbound TCP port 8888 is allowed. |
| **`Peer cannot connect`** | Peer IP address or port mismatch. | Verify peers are on the same subnet and advertise their reachable IP using `-Dlandrop.peer.advertised.host=<LAN_IP>`. |
| **`SHA-256 mismatch`** | File was modified, corrupted in transit, or truncated. | LANDrop automatically discards the corrupted `.part` file. Restart transfer with healthy source peers. |
| **`JavaFX Native Access Warning`** | Java 21+ warnings regarding restricted JNI access. | Already resolved in `frontend/pom.xml` via `--enable-native-access=ALL-UNNAMED`. |

---

## 18. Project Status

**Production-ready for the current project scope.** All 182 unit/integration tests pass cleanly, build succeeds with zero warnings, and multi-node demonstration workflows are verified.
