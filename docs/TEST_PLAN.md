# LANDrop Comprehensive Test Plan & Verification Matrix

This document provides the complete quality assurance matrix for LANDrop, detailing all automated unit/integration suites and manual verification procedures across 11 test domains.

---

## 1. Test Suite Summary

- **Total Automated Tests**: **182**
  - **Backend Module**: 170 tests
  - **Frontend Module**: 12 tests
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 0
- **Pass Rate**: **100%**
- **Test Command**: `mvn clean test`

---

## 2. Test Execution Matrix

### Domain 1: Discovery Lifecycle
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **DISC-01** | Discovery Server startup on default/custom port | Backend | Server socket binds cleanly, logs startup port | AUTOMATED (PASS) |
| **DISC-02** | Peer registration (`REGISTER`) | Backend | Client registers IP, port, name; receives `REGISTER_ACK` | AUTOMATED (PASS) |
| **DISC-03** | Periodic heartbeat transmission (`HEARTBEAT`) | Backend | Server updates peer last-seen timestamp; returns `HEARTBEAT_ACK` | AUTOMATED (PASS) |
| **DISC-04** | Peer list querying (`PEER_LIST_REQUEST`) | Backend | Returns active peer list excluding requesting peer | AUTOMATED (PASS) |
| **DISC-05** | Dead peer pruning | Backend | Unresponsive peers purged after timeout threshold (15s) | AUTOMATED (PASS) |
| **DISC-06** | Graceful client unregistration (`UNREGISTER`) | Backend | Server receives unregister packet and immediately removes peer | AUTOMATED (PASS) |

### Domain 2: Transport & Connection Handshake
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **CONN-01** | Direct P2P TCP connection | Backend | Full-duplex connection established between two peers | AUTOMATED (PASS) |
| **CONN-02** | Bidirectional `HELLO` handshake | Backend | State transitions `CONNECTING` -> `READY` on both peers | AUTOMATED (PASS) |
| **CONN-03** | Round-trip latency measurement (`PING` / `PONG`) | Backend | Pong returned with matching requestId; latency computed | AUTOMATED (PASS) |
| **CONN-04** | Purpose multiplexing (`CHAT` vs `FILE_TRANSFER`) | Backend | Sockets tagged with `ConnectionPurpose` at handshake | AUTOMATED (PASS) |
| **CONN-05** | Read timeout enforcement on stalled socket | Backend | Socket read times out per `AppConfig` timeout threshold | AUTOMATED (PASS) |

### Domain 3: Real-Time Chat
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **CHAT-01** | Single text message dispatch | Backend | Packet encoded, transmitted, decoded, and dispatched | AUTOMATED (PASS) |
| **CHAT-02** | Multiple concurrent messages | Backend | Thread-safe writes prevent byte interleaving; messages delivered | AUTOMATED (PASS) |
| **CHAT-03** | Chat history recording | Backend | In-memory history stores message order, timestamps, and status | AUTOMATED (PASS) |
| **CHAT-04** | Chat UI message rendering | Frontend | Messages correctly displayed in JavaFX ListView on FX thread | AUTOMATED (PASS) |

### Domain 4: Single-Peer File Transfer
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **XFER-01** | `FILE_OFFER` / `FILE_REQUEST` handshake | Backend | Receiver validates offer parameters and approves transfer | AUTOMATED (PASS) |
| **XFER-02** | Stream binary chunks (`FILE_DATA`) | Backend | Binary slices streamed and assembled into temporary file | AUTOMATED (PASS) |
| **XFER-03** | Progress calculation and callback dispatch | Backend | Bytes transferred and percentage tracked accurately | AUTOMATED (PASS) |
| **XFER-04** | Mid-transfer socket break and resume | Backend | Transfer pauses on break; resumes from last acknowledged chunk | AUTOMATED (PASS) |
| **XFER-05** | Final transfer acknowledgment (`TRANSFER_ACK`) | Backend | Acknowledged upon successful receipt and integrity match | AUTOMATED (PASS) |

### Domain 5: Multi-Peer Concurrent Download (Swarming)
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **SWARM-01**| File availability probing (`FILE_QUERY`) | Backend | Probes peers; parses availability and chunk count | AUTOMATED (PASS) |
| **SWARM-02**| Chunk scheduling across multiple workers | Backend | `ChunkScheduler` distributes chunks across active peers | AUTOMATED (PASS) |
| **SWARM-03**| Concurrent chunk requests (`CHUNK_REQUEST`) | Backend | Workers request distinct chunks simultaneously | AUTOMATED (PASS) |
| **SWARM-04**| Concurrent positional FileChannel writes | Backend | Workers write chunks to exact offsets in `.landrop.part` | AUTOMATED (PASS) |
| **SWARM-05**| Dynamic late peer join | Backend | New peer added mid-transfer; scheduler rebalances chunks | AUTOMATED (PASS) |

### Domain 6: Failover & Resilience
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **FAIL-01** | Source peer disconnect during download | Backend | Worker catches error; marks peer offline | AUTOMATED (PASS) |
| **FAIL-02** | In-flight chunk rollback to `MISSING` | Backend | Interrupted chunk returned to `MISSING` without data corruption | AUTOMATED (PASS) |
| **FAIL-03** | Reassignment to surviving peer | Backend | Surviving peer downloads failed chunk; transfer completes | AUTOMATED (PASS) |
| **FAIL-04** | Complete swarm failure (all peers disconnect) | Backend | Transfer transitions to `FAILED` with clear error message | AUTOMATED (PASS) |

### Domain 7: Cryptographic Data Integrity
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **INTG-01** | Full-file SHA-256 streaming verification | Backend | Assembled file hash matches source hash bit-for-bit | AUTOMATED (PASS) |
| **INTG-02** | Tampered / corrupted chunk detection | Backend | Hash mismatch caught; corrupt file deleted; error reported | AUTOMATED (PASS) |
| **INTG-03** | Atomic file promotion | Backend | Temporary `.part` file renamed atomically upon verified match | AUTOMATED (PASS) |
| **INTG-04** | Zero-byte file transfer | Backend | Empty file matches standard SHA-256 digest (`e3b0c44...`) | AUTOMATED (PASS) |

### Domain 8: Security Protections
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **SEC-01**  | Path traversal attempt (`../../etc/passwd`) | Backend | Filename sanitized; written inside designated download folder | AUTOMATED (PASS) |
| **SEC-02**  | Oversized packet attack (> 2 MiB) | Backend | `PacketDecoder` immediately throws `ProtocolException` | AUTOMATED (PASS) |
| **SEC-03**  | Negative payload length | Backend | Rejection with `ProtocolException` | AUTOMATED (PASS) |
| **SEC-04**  | Sensitive data logging sanitization | Backend | `AppLogger` masks IPs, passwords, and tokens | AUTOMATED (PASS) |

### Domain 9: Edge Cases
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **EDGE-01** | 1-byte file transfer | Backend | Correctly transfers and verifies single 1-byte chunk | AUTOMATED (PASS) |
| **EDGE-02** | Non-uniform final chunk size | Backend | Last chunk calculated with exact remainder bytes | AUTOMATED (PASS) |
| **EDGE-03** | Rapid client connect and instant disconnect | Backend | Server and peer handle EOF without unhandled exceptions | AUTOMATED (PASS) |
| **EDGE-04** | Invalid protocol version byte | Backend | Socket rejects packet with `Unsupported protocol version` | AUTOMATED (PASS) |

### Domain 10: UI & State Lifecycle
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **UI-01**   | FXML view hierarchy loading | Frontend | All 6 FXML views load without missing element errors | AUTOMATED (PASS) |
| **UI-02**   | UIStateService thread safety | Frontend | Background updates safely dispatched via `Platform.runLater` | AUTOMATED (PASS) |
| **UI-03**   | Application shutdown toolkit guard | Frontend | Suppression of `IllegalStateException` after FX shutdown | AUTOMATED (PASS) |
| **UI-04**   | Multi-peer transfer card visual rendering | Frontend | Progress bar, speed, ETA, and peer badges render correctly | MANUAL (PASS) |

### Domain 11: Configuration & Observability
| ID | Scenario | Scope | Expected Result | Status |
| :--- | :--- | :---: | :--- | :---: |
| **CFG-01**  | Default operational configurations | Backend | Defaults return expected baseline values | AUTOMATED (PASS) |
| **CFG-02**  | System property overrides (`-Dlandrop.*`) | Backend | System property takes priority over environment variable | AUTOMATED (PASS) |
| **CFG-03**  | Environment variable fallback (`LANDROP_*`) | Backend | Env var used when system property is absent | AUTOMATED (PASS) |
| **CFG-04**  | Log level filtering (`DEBUG`, `INFO`, etc.) | Backend | Messages filtered according to configured threshold | AUTOMATED (PASS) |

---

## 3. Regression & Build Verification Results

```text
Results :

Tests run: 182, Failures: 0, Errors: 0, Skipped: 0

[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for LANDrop - Root Aggregator 0.1.0-SNAPSHOT:
[INFO] 
[INFO] LANDrop - Root Aggregator .......................... SUCCESS
[INFO] LANDrop - Backend .................................. SUCCESS
[INFO] LANDrop - Frontend ................................. SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
