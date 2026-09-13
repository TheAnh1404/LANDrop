# LANDrop Core Scope Specification

This document defines the exact scope, functional boundaries, and architectural taxonomy of the **LANDrop CORE**.

---

## 1. Core Responsibilities

The LANDrop CORE provides a high-performance, decentralized, local-area network (LAN) peer-to-peer file sharing and messaging baseline. Its primary responsibility is enabling reliable, fault-tolerant, high-throughput file transfers and real-time messaging directly between computing nodes without intermediate cloud relays or external internet dependency.

---

## 2. Core Functional Taxonomy

Functionality in the current codebase and project lifecycle is strictly classified into four operational tiers:

```text
┌─────────────────────────────────────────────────────────────┐
│ A. CORE (Engine, Protocols, Networking, Swarm) — FROZEN      │
├─────────────────────────────────────────────────────────────┤
│ B. SUPPORTING INFRASTRUCTURE (Logging, Config, CLI) — FROZEN│
├─────────────────────────────────────────────────────────────┤
│ C. DEMO & DOCUMENTATION (Scripts, Verification) — AVAILABLE  │
├─────────────────────────────────────────────────────────────┤
│ D. FUTURE PRODUCTION (Cloud, NAT, Auth, Mobile) — OUT OF    │
│    SCOPE (Not Implemented)                                  │
└─────────────────────────────────────────────────────────────┘
```

### A. CORE — Must Preserve
The foundation of LANDrop. Any modification to these modules requires comprehensive regression testing:
1. **Binary Protocol Framing Engine**: 14-byte wire framing header (`Packet`, `PacketEncoder`, `PacketDecoder`, `MessageType`).
2. **Peer-to-Peer Networking**: Full-duplex raw TCP sockets, asynchronous reader loops on virtual threads, connection purpose multiplexing (`CHAT` vs `FILE_TRANSFER`), and handshake state management (`PeerConnection`, `PeerServer`).
3. **Discovery System**: Centralized directory coordinator (`DiscoveryServer`) and peer presence client with heartbeat scheduling (`DiscoveryClient`).
4. **P2P Messaging**: In-memory direct text message exchange and session history (`ChatService`, `ChatHistory`).
5. **Single-Peer File Transfer**: Streamed chunk transmission, byte-offset tracking, and pause/resume resilience (`FileTransfer`, `FileTransferService`).
6. **Multi-Peer Concurrent Download (Swarming)**:
   - Chunk availability probing (`FileQueryMessage`, `SharedFileScanner`).
   - Round-robin chunk scheduling across active peers (`ChunkScheduler`).
   - Bitset chunk lifecycle tracking: `MISSING` → `REQUESTED` → `TRANSFERRING` → `COMPLETED` (`ChunkManager`).
   - Dynamic peer joining mid-transfer with work rebalancing.
   - Fault-tolerant mid-transfer failover and in-flight chunk rollback.
   - Concurrent positional assembly directly into `.landrop.part` via Java NIO `FileChannel`.
7. **Cryptographic Data Integrity**: End-to-end streaming SHA-256 digest computation and atomic promotion (`Files.move` with `ATOMIC_MOVE`).

### B. SUPPORTING INFRASTRUCTURE — Must Preserve
Underlying support modules that provide runtime stability and zero external dependency operation:
1. **Centralized Configuration**: `AppConfig` supporting two-tier overrides (System Properties → Environment Variables → Defaults).
2. **Structured Logging**: `AppLogger` providing ISO-8601 formatting, configurable log thresholds (`DEBUG`–`ERROR`), and automatic sensitive data sanitization (masking IPs, tokens, and passwords).
3. **Reactive UI Presentation**: JavaFX 21 MVC desktop interface (`MainApp`, `UIStateService`, Controllers) with safe JavaFX Application Thread dispatching.

### C. DEMO & DOCUMENTATION — Optional / Presentational
Artifacts designed for evaluation, local multi-node demonstration, and academic/engineering defense:
- Platform launch scripts (`start-discovery.sh/.bat`, `start-peerA` through `start-peerD`).
- Technical guides in `docs/` (`ARCHITECTURE.md`, `PROTOCOL.md`, `DEMO.md`, `DEFENSE.md`, `TEST_PLAN.md`, `PERFORMANCE.md`).
- Synthetic loopback benchmark tests (`PerformanceBaselineTest.java`).

### D. FUTURE PRODUCTION — Not Implemented (Out of Scope)
These features are intentionally excluded from the CORE and reserved for future production expansion. **None of these are implemented in the current CORE:**
- User accounts, authentication (OAuth/JWT/LDAP), and authorization.
- Persistent user profiles, contacts, or relational/NoSQL databases.
- Public internet NAT traversal (STUN, TURN, ICE, UPnP) and relay servers.
- Transport-layer or end-to-end encryption (TLS/DTLS, asymmetric key exchange).
- Cloud storage integration (AWS S3, Google Cloud Storage, Azure Blob).
- Distributed backend services, API gateways, microservices, or event buses (Kafka/RabbitMQ).
- Containerization and orchestration (Docker, Kubernetes, Helm).
- Mobile clients (Android/iOS) and WebAssembly/browser clients.
- Billing, quotas, monetization, or enterprise tenant management.

---

## 3. Subsystem Specifications

### 3.1. Core Networking
- **Discovery**: Operates over TCP port 8888. Peers register with display name, host, and port. Periodic heartbeats (every 5 seconds) refresh registration; unresponsive peers are pruned after a 15-second timeout.
- **Framing**: Every packet begins with a fixed 14-byte header: `[Version: 1B] [MsgType: 1B] [Length: 4B] [RequestId: 8B]`.
- **Multiplexing**: The `HELLO` handshake negotiates connection purpose (`CHAT` or `FILE_TRANSFER`), ensuring bulk data transfer never blocks interactive chat.

### 3.2. Core Communication
- **Chat**: Real-time peer-to-peer messaging transmitted over dedicated `CHAT` TCP connections.
- **File Transfer**: Point-to-point binary slice transfer with progress events and SHA-256 validation.

### 3.3. Core Distributed Transfer (Swarming)
- **Chunk Partitioning**: Uniform chunk slicing (default 1 MiB; down to 64 KiB for benchmarks).
- **Worker Swarm**: One `PeerDownloadWorker` per active source peer.
- **Failover**: Disconnection immediately returns active chunks from `TRANSFERRING` to `MISSING` and reassigns them to surviving peers.
- **Dynamic Join**: New peers possessing the file are dynamically admitted into the active worker pool.

### 3.4. Core Integrity
- **SHA-256**: Calculated via streaming `MessageDigest` over the final assembled file. Matches source hash bit-for-bit before final file creation.

### 3.5. Core Reliability
- **Timeouts**: Explicit connection (`5000ms`), socket read (`10000ms`), and chunk response (`5000ms`) timeouts.
- **Cleanup**: Virtual thread readers and workers cleanly interrupted; sockets and `FileChannel` resources closed via `AutoCloseable`.
- **Graceful Shutdown**: Unregistration frame transmitted to discovery server on client application exit.
