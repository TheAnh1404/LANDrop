# LANDrop — Backend

## Phase
Phase 7 — Chunked P2P File Transfer & SHA-256 Integrity Verification

## Objective
Provide reliable, bounded-memory chunked P2P file streaming over existing TCP `PeerConnection` using sequential pull-based chunk requests (default 1 MiB chunk size), streaming SHA-256 integrity verification, temporary `.landrop.part` file staging with atomic moves, bitset de-duplication, and sender consistency checks.

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
* **Message Type**: 1 byte (`HELLO=1`, `CHAT_MESSAGE=2`, `PING=3`, `PONG=4`, `FILE_OFFER=5`, `FILE_REQUEST=6`, `CHUNK_REQUEST=7`, `CHUNK_RESPONSE=8`, `TRANSFER_ACK=9`, `FILE_DATA=17`)
* **Payload Length**: 4 bytes (int, unsigned range bounded to `DEFAULT_MAX_PAYLOAD_SIZE = 2 MB`)
* **Request ID**: 8 bytes (long, monotonic request/response correlation)
* **Payload**: UTF-8 bytes / JSON strings / binary chunk payloads

### Binary CHUNK_RESPONSE (Code 8) Payload Structure
```text
+--------------------+--------------------+--------------------+--------------------+--------------------+
| transferIdLen (1B) | transferId (UTF-8) | chunkIndex (4B)    | chunkLength (4B)   | chunkData (bytes)  |
+--------------------+--------------------+--------------------+--------------------+--------------------+
```

## Build & Test
Run all unit and integration tests (100 tests):

```bash
mvn clean test
```

## Current Status & Roadmap
Phase 7 delivers chunked transfer, SHA-256 verification, and thread-safe streaming.
The following advanced features are intentionally deferred to future phases:
* Phase 8: Resume & retry interrupted transfers (persistent chunk bitmap tracking)
* Multi-peer parallel download (BitTorrent-style swarming)
* End-to-end encryption
