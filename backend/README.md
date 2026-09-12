# LANDrop — Backend

## Phase
Phase 9 — Multi-Peer Concurrent File Transfer

## Objective
Upgrade the chunked P2P file transfer engine with multi-peer parallel swarming capabilities:
* Receiver concurrently requests different chunks from multiple source peers over direct TCP sockets.
* File availability discovery via `FILE_QUERY (20)` and `FILE_QUERY_RESPONSE (21)`.
* Thread-safe chunk state management (`ChunkManager`) with peer tracking and duplicate assignment prevention.
* Bounded round-robin scheduler (`ChunkScheduler`) respecting per-peer concurrency bounds (`maxActiveChunksPerPeer = 2`).
* Direct-to-disk concurrent positional writes into `.landrop.part` via Java NIO `FileChannel`.
* Automatic failover when peers disconnect, releasing in-flight chunks for reassignment without restarting transfer.
* Streaming SHA-256 integrity verification upon completion, atomic move to final file, and partial file deletion on mismatch.
* Backward-compatible with single-peer Phase 1–8 file transfers.

## Protocol Wire Format
```text
+----------+----------+----------+----------+
| Version  | Msg Type | Length   | RequestID|
| (1 byte) | (1 byte) | (4 bytes)| (8 bytes)|
+----------+----------+----------+----------+
|                 Payload                    |
|             (Length bytes)                 |
+--------------------------------------------+
```

* **Version**: 1 byte (`CURRENT_VERSION = 1`)
* **Message Type**: 1 byte (`HELLO=1`, `CHAT_MESSAGE=2`, `PING=3`, `PONG=4`, `FILE_OFFER=5`, `FILE_REQUEST=6`, `CHUNK_REQUEST=7`, `CHUNK_RESPONSE=8`, `TRANSFER_ACK=9`, `FILE_DATA=17`, `RESUME_REQUEST=18`, `RESUME_RESPONSE=19`, `FILE_QUERY=20`, `FILE_QUERY_RESPONSE=21`)
* **Payload Length**: 4 bytes (int, bounded to `DEFAULT_MAX_PAYLOAD_SIZE = 2 MB`)
* **Request ID**: 8 bytes (long, monotonic request/response correlation)
* **Payload**: UTF-8 bytes / JSON strings / binary chunk payloads

### Binary CHUNK_RESPONSE (Code 8) Payload Structure
```text
+--------------------+--------------------+--------------------+--------------------+--------------------+
| transferIdLen (1B) | transferId (UTF-8) | chunkIndex (4B)    | chunkLength (4B)   | chunkData (bytes)  |
+--------------------+--------------------+--------------------+--------------------+--------------------+
```

### FILE_QUERY (Code 20, JSON)
```json
{
  "queryId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "fileName": "ubuntu-24.04-desktop-amd64.iso",
  "fileSize": 6120341504,
  "fileSha256": "3a0b...9f",
  "requesterPeerId": "peer-d",
  "requesterPeerName": "Peer D"
}
```

### FILE_QUERY_RESPONSE (Code 21, JSON)
```json
{
  "queryId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "available": true,
  "fileName": "ubuntu-24.04-desktop-amd64.iso",
  "fileSize": 6120341504,
  "chunkSize": 1048576,
  "totalChunks": 5837,
  "fileSha256": "3a0b...9f",
  "sourcePeerId": "peer-a",
  "sourcePeerName": "Peer A",
  "reason": ""
}
```

## Build & Test
Run all unit and integration tests (142 backend tests):

```bash
mvn clean test
```

## Current Status & Roadmap
* Phase 1: TCP P2P Core — COMPLETED
* Phase 2: Custom Binary Protocol — COMPLETED
* Phase 3: Real-time Chat — COMPLETED
* Phase 4: Peer Discovery — COMPLETED
* Phase 5: JavaFX Desktop UI — COMPLETED
* Phase 6: Basic P2P File Transfer — COMPLETED
* Phase 7: Chunked Transfer & SHA-256 Verification — COMPLETED
* Phase 8: P2P File Transfer Resume + Retry — COMPLETED
* Phase 9: Multi-Peer Parallel Swarming File Transfer — COMPLETED

Deferred to Future Phases:
* Phase 10: End-to-end encryption (TLS / AES-GCM)
* Phase 11: Persistent transfer database (SQLite)

