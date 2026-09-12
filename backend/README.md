# LANDrop — Backend

## Phase
Phase 8 — P2P File Transfer Resume + Retry

## Objective
Upgrade the chunked P2P file transfer engine with session-level resume and retry capabilities:
* Interruption detection on network drop without destroying `.landrop.part` files.
* Controlled P2P reconnection with bounded backoff (`MAX_RECONNECT_ATTEMPTS = 5`).
* Two-way resume handshake (`RESUME_REQUEST` and `RESUME_RESPONSE`) using Base64-encoded `BitSet` chunk bitmaps.
* Strict source file & metadata validation (transferId, fileName, fileSize, chunkSize, totalChunks, SHA-256).
* Resuming sequentially from the first missing chunk without re-transmitting completed chunks.
* Per-chunk timeout and retry mechanism (`MAX_CHUNK_RETRIES = 3`).
* Final file size check, streaming SHA-256 integrity verification, and atomic rename.

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
* **Message Type**: 1 byte (`HELLO=1`, `CHAT_MESSAGE=2`, `PING=3`, `PONG=4`, `FILE_OFFER=5`, `FILE_REQUEST=6`, `CHUNK_REQUEST=7`, `CHUNK_RESPONSE=8`, `TRANSFER_ACK=9`, `FILE_DATA=17`, `RESUME_REQUEST=18`, `RESUME_RESPONSE=19`)
* **Payload Length**: 4 bytes (int, bounded to `DEFAULT_MAX_PAYLOAD_SIZE = 2 MB`)
* **Request ID**: 8 bytes (long, monotonic request/response correlation)
* **Payload**: UTF-8 bytes / JSON strings / binary chunk payloads

### Binary CHUNK_RESPONSE (Code 8) Payload Structure
```text
+--------------------+--------------------+--------------------+--------------------+--------------------+
| transferIdLen (1B) | transferId (UTF-8) | chunkIndex (4B)    | chunkLength (4B)   | chunkData (bytes)  |
+--------------------+--------------------+--------------------+--------------------+--------------------+
```

### RESUME_REQUEST (Code 18, JSON)
```json
{
  "transferId": "47608a73-94c1-4565-b783-feaead260b64",
  "fileName": "sample.mp4",
  "fileSize": 104857600,
  "chunkSize": 1048576,
  "totalChunks": 100,
  "fileSha256": "52406a802793990972002ae44bf2d17df7638b2d27c038932367f7126df11e47",
  "completedChunksBitmap": "//////8="
}
```

### RESUME_RESPONSE (Code 19, JSON)
```json
{
  "transferId": "47608a73-94c1-4565-b783-feaead260b64",
  "accepted": true,
  "nextMissingChunk": 42,
  "reason": ""
}
```

## Build & Test
Run all unit and integration tests (113 tests):

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

Deferred to Future Phases:
* Phase 9: Multi-peer parallel swarming transfer
* Phase 10: End-to-end encryption (TLS / AES-GCM)
* Phase 11: Persistent transfer database (SQLite)

