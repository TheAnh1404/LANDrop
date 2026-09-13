# LANDrop Core Architecture Baseline & Freeze Record

This document establishes the official frozen architectural baseline of the **LANDrop CORE** at Phase 13.

---

## 1. Baseline Version & Metadata

- **Project Version**: `0.1.0-SNAPSHOT`
- **Baseline Status**: **`CORE FROZEN`**
- **Effective Date**: September 13, 2026
- **JDK Target**: Java 21 (LTS)
- **UI Runtime**: JavaFX 21.0.2

---

## 2. Technology Stack Baseline

The CORE is strictly implemented with:
- **Language**: Java 21 (Records, Virtual Threads, Pattern Matching, Sealed Types)
- **Networking**: Standard Java Sockets (`java.net.Socket`, `java.net.ServerSocket`, `java.net.InetSocketAddress`)
- **Protocol Framing**: Custom binary protocol with fixed 14-byte header (`[Version: 1B] [Type: 1B] [Length: 4B] [RequestId: 8B]`)
- **File I/O**: Java NIO (`FileChannel` positional reads and writes, `StandardOpenOption`, `StandardCopyOption.ATOMIC_MOVE`)
- **Integrity**: Standard Java Security API (`java.security.MessageDigest` - SHA-256)
- **Serialization**: Jackson Databind 2.17.0 (Strictly for discovery and transfer control JSON schemas)
- **UI Framework**: JavaFX 21 (FXML layouts, Model-View-Controller architecture)
- **Logging**: Zero-dependency custom structured logger (`AppLogger`)
- **Configuration**: Zero-dependency hierarchical configuration parser (`AppConfig`)
- **Build System**: Apache Maven 3.9+ multi-module build

---

## 3. Frozen Architecture Summary

```text
LANDrop Architecture (Hybrid P2P)
├── Control Plane: Centralized Discovery Server (TCP Port 8888)
│   ├── Client Registration (REGISTER)
│   ├── Client Heartbeats (HEARTBEAT - 5s interval)
│   ├── Dead Node Pruning (15s expiration)
│   └── Online Peer Catalog (PEER_LIST)
│
└── Data Plane: Direct Point-to-Point Mesh
    ├── Handshake Negotiation (HELLO with Purpose Multiplexing)
    ├── Interactive Chat Connection (PURPOSE: CHAT)
    └── Bulk File Transfer Connection (PURPOSE: FILE_TRANSFER)
        ├── Single-Peer Streaming Engine (Offset-based FILE_DATA)
        └── Multi-Peer Swarm Engine
            ├── File Availability Discovery (FILE_QUERY)
            ├── Deterministic Round-Robin Scheduling (ChunkScheduler)
            ├── State Machine (MISSING -> REQUESTED -> TRANSFERRING -> COMPLETED)
            ├── Concurrent Positional Writes (FileChannel.write)
            ├── Dynamic Mid-Download Peer Joining
            ├── Automatic Mid-Transfer Failover & Chunk Reassignment
            └── End-to-End Cryptographic SHA-256 Checksum Validation
```

---

## 4. Supported Capabilities Matrix

| Capability | Status | Verification Reference |
| :--- | :---: | :--- |
| **Discovery Server Registration & Heartbeats** | Verified | Automated tests (`DiscoveryServerTest`, `DiscoveryClientTest`) |
| **Peer List Discovery & Dynamic Updates** | Verified | Automated tests (`PeerRegistryTest`) |
| **Direct P2P Full-Duplex TCP Connections** | Verified | Automated tests (`PeerConnectionTest`, `PeerServerTest`) |
| **Bi-Directional Real-Time P2P Chat** | Verified | Automated tests (`ChatServiceTest`, `ChatCliTest`) |
| **Single-Peer Direct File Streaming** | Verified | Automated tests (`FileTransferServiceTest`, `FileTransferUtilsTest`) |
| **Pause & Resume on Connection Drop** | Verified | Automated tests (`FileTransferServiceTest`) |
| **Multi-Peer Concurrent Download (Swarm)** | Verified | Automated tests (`MultiPeerDownloadIntegrationTest`) |
| **Dynamic Late Peer Joining Mid-Transfer** | Verified | Automated tests (`testLatePeerJoinsInProgressDownload`) |
| **Mid-Transfer Peer Failover & Reassignment** | Verified | Automated tests (`testFailoverWhenSourcePeerFailsMidTransfer`) |
| **End-to-End SHA-256 Bit-Exact Verification** | Verified | Automated tests (`testSha256VerificationOnCompletedDownload`) |
| **Path Traversal Protection (`../` Sanitation)**| Verified | Automated tests (`Phase10SystemValidationIntegrationTest`) |
| **Zero-Byte File Transfer Handling** | Verified | Automated tests (`testZeroByteMultiPeerDownload`) |
| **Memory & Packet Framing Limit Enforcement**| Verified | Automated tests (`PacketDecoderTest`) |

---

## 5. Quality & Verification Baselines

### 5.1. Automated Test Baseline
- **Total Tests Executed**: **182**
- **Backend Tests**: 170 passed
- **Frontend Tests**: 12 passed
- **Failures**: **0**
- **Errors**: **0**
- **Skipped**: **0**
- **Pass Rate**: **100%**

### 5.2. Build Baseline
Execution of `mvn clean package` yields `BUILD SUCCESS` with zero compilation errors and generates two standalone JAR artifacts:
- `backend/target/landrop-0.1.0-SNAPSHOT.jar`
- `frontend/target/landrop-frontend-0.1.0-SNAPSHOT.jar`

### 5.3. Performance Baseline (Empirical Local Loopback)
- **Single-Peer Baseline (2.0 MiB, 32 chunks)**: 24 ms (~83.33 MB/s)
- **Multi-Peer Swarm (2 peers, 32 chunks)**: 10 ms (~200.00 MB/s, **2.4x speedup**)
- **Failover Recovery (Peer severed at chunk 4)**: 29 ms total elapsed time (~68.96 MB/s)
- **Integrity**: 100% SHA-256 verification match

---

## 6. Security Baseline

- **Path Traversal Shield**: Filenames received over network sockets are stripped of path separators (`/`, `\`, `..`) and confined to the designated download folder.
- **Cryptographic Verification**: Incomplete downloads are held in `.landrop.part` files; only promoted to user destination upon successful 256-bit SHA-256 checksum matching. Corrupted files are deleted.
- **Oversized Packet Guard**: `PacketDecoder` enforces a strict 2 MiB payload limit and positive length checks to prevent buffer overflow or out-of-memory denial-of-service.
- **Log Privacy**: Sensitive credentials, tokens, and IP addresses are masked in log output.
- **Code Cleanliness**: Zero credentials, tokens, or machine-specific paths exist in the repository.

---

## 7. Known Architectural Limitations

1. **Local Subnet Scope**: Peer addresses must be directly reachable via IP. The CORE does not include NAT traversal (STUN/TURN/ICE) or public internet relay servers.
2. **Unencrypted TCP**: Network communication is plaintext TCP without TLS/DTLS.
3. **No Central Identity**: Peers are identified by UUID and display name; there are no authenticated user accounts or password verification.
4. **Port Allocation for Multi-Instance**: When executing multiple peer nodes on a single host machine, each instance must be assigned an explicit distinct port (`5001`, `5002`, `5003`, `5004`).

---

## 8. Architectural Freeze Rule

> [!IMPORTANT]
> **CORE FREEZE RULE**
> 
> The LANDrop CORE (Phase 1–13) is officially declared **FROZEN**.
> 
> Future production expansions (cloud infrastructure, user authentication, end-to-end encryption, internet NAT traversal, mobile clients, and enterprise management) must extend the CORE through well-defined external interfaces and modules **without modifying or destabilizing the validated networking, framing, and swarming engine**.
