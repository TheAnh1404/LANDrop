# LANDrop — Backend

## Phase
Phase 6 — Basic P2P File Transfer & Protocol Streaming Engine

## Objective
Provide high-throughput, low-latency, non-blocking direct P2P file streaming over existing TCP `PeerConnection` using Java NIO 64 KB chunking, atomic control packet handshakes (OFFER / REQUEST / ACK), and isolated binary payload framing (`FILE_DATA`).

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
* **Message Type**: 1 byte (`HELLO`, `CHAT_MESSAGE`, `PING`, `PONG`, `FILE_OFFER=5`, `FILE_REQUEST=6`, `TRANSFER_ACK=9`, `FILE_DATA=17`)
* **Payload Length**: 4 bytes (int, unsigned range bounded to `MAX_PAYLOAD_SIZE = 1 MB`)
* **Request ID**: 8 bytes (long, monotonic request/response correlation)
* **Payload**: UTF-8 bytes / JSON strings / binary chunk payloads

### Binary FILE_DATA Payload Structure
```text
+-------------------+--------------------+--------------------+--------------------+
| transferIdLen (1B)| transferId (UTF-8) | chunkOffset (8B)   | chunkData (bytes)  |
+-------------------+--------------------+--------------------+--------------------+
```

## Build & Test
Run all unit and integration tests (82 tests):

```bash
mvn clean test
```

## Current Limitations & Roadmap
Current Phase 6 delivers basic reliable streaming and safety protections (path traversal sanitization, auto duplicate renaming, non-blocking virtual threads, zero-byte file support).
The following advanced features are planned for future phases:
* SHA-256 integrity verification per chunk
* Resume / retry interrupted transfers
* Multi-peer parallel download
* End-to-end encryption
