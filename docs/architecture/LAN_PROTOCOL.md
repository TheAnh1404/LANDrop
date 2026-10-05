# LANDrop Protocol Specification

> Scope: Java TCP LAN transport. This binary protocol is distinct from the browser WebRTC DataChannel implementation. Crypto-related additions must be checked against MessageType, PeerConnection and P2pCryptoSession in the current backend.

This document defines the custom binary application protocol used by LANDrop for peer-to-peer (P2P) networking, discovery, real-time messaging, and multi-peer concurrent file transfers over standard Java TCP sockets (`java.net.Socket`, `java.net.ServerSocket`).

---

## 1. Protocol Architecture & Design Principles

The LANDrop protocol operates directly over standard TCP streams. TCP provides reliable, in-order byte delivery, but streams are continuous byte sequences without inherent message boundaries. To prevent stream fragmentation and concatenation issues, the LANDrop protocol implements an explicit **Framing Header** on every packet.

Key design principles:
- **Strict Framing**: Every packet begins with a fixed 14-byte header specifying protocol version, message type, payload length, and a correlation request ID.
- **Direct Peer Data Flow**: Control signals and heavy file payloads travel peer-to-peer. The Discovery Server is exclusively used for registration and peer list coordination; it never handles file payloads.
- **Bi-Directional Multiplexing**: Sockets are negotiated during handshake for specific connection purposes (`CHAT` vs `FILE_TRANSFER`), ensuring file transfers do not block interactive chat.
- **Zero External Dependencies**: The network layer is built entirely on standard Java I/O (`DataInputStream`, `DataOutputStream`, `FileChannel`).

---

## 2. Wire Framing Header Format

Every discrete unit transmitted across the network begins with a **14-byte fixed-size binary framing header**, followed immediately by `Length` bytes of payload.

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    Version    |   Msg Type    |         Payload Length        |
|    (1 byte)   |   (1 byte)    |            (4 bytes)          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Request ID                          |
|                            (8 bytes)                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                            Payload                            |
|                     (Payload Length bytes)                    |
+---------------------------------------------------------------+
```

### Field Definitions

| Field | Size | Data Type | Encoding / Endianness | Description |
| :--- | :---: | :--- | :--- | :--- |
| **Version** | 1 byte | Unsigned Byte | Integer `1` | Protocol specification version. Current version is `0x01` (`Packet.CURRENT_VERSION`). |
| **Msg Type** | 1 byte | Signed Byte | Byte code `1`–`21` | Message type opcode identifying packet semantics (see Catalog below). |
| **Payload Length** | 4 bytes | Signed 32-bit Int | Big-Endian (Network Byte Order) | Length in bytes of the following payload (`0` to `2,097,152`). |
| **Request ID** | 8 bytes | Signed 64-bit Long | Big-Endian (Network Byte Order) | Monotonically increasing correlation identifier for matching requests and responses. |
| **Payload** | *N* bytes | Raw Bytes | Binary or UTF-8 JSON | Variable payload data defined by the message type. If Length = 0, no bytes follow. |

---

## 3. Message Type Catalog

The protocol defines 21 discrete message types across four functional domains:

| Code | Name | Domain | Payload Type | Purpose |
| :---: | :--- | :--- | :--- | :--- |
| **1** | `HELLO` | Connection Handshake | UTF-8 String | Handshake exchange identifying peer name and connection purpose (`CHAT` or `FILE_TRANSFER`). |
| **2** | `CHAT_MESSAGE` | Messaging | UTF-8 String / JSON | Real-time text message transmitted between peers. |
| **3** | `PING` | Keep-Alive / Latency | Empty (0 bytes) | Heartbeat probe sent to measure round-trip latency. |
| **4** | `PONG` | Keep-Alive / Latency | Empty (0 bytes) | Immediate response to `PING` carrying the matching `requestId`. |
| **5** | `FILE_OFFER` | Single-Peer Transfer | UTF-8 JSON | Sender offers a file specifying filename, size, chunk configuration, and SHA-256. |
| **6** | `FILE_REQUEST` | Single-Peer Transfer | UTF-8 JSON | Receiver accepts or requests file transfer initiation. |
| **7** | `CHUNK_REQUEST` | Multi-Peer Download | UTF-8 JSON | Receiver requests a specific chunk index from a source peer. |
| **8** | `CHUNK_RESPONSE` | Multi-Peer Download | Framed Binary | Source peer returns chunk binary data with transfer ID, chunk index, and length. |
| **9** | `TRANSFER_ACK` | File Transfer | UTF-8 JSON | Transfer completion acknowledgment and SHA-256 verification confirmation. |
| **10** | `REGISTER` | Discovery | UTF-8 JSON | Client registers display name, host, and listening port with Discovery Server. |
| **11** | `REGISTER_ACK` | Discovery | UTF-8 JSON | Discovery Server acknowledges registration success or failure. |
| **12** | `PEER_LIST_REQUEST` | Discovery | UTF-8 JSON | Client requests list of active online peers. |
| **13** | `PEER_LIST` | Discovery | UTF-8 JSON | Discovery Server returns serialized array of online peer descriptors. |
| **14** | `HEARTBEAT` | Discovery | UTF-8 JSON | Client sends periodic presence heartbeat to maintain active registration. |
| **15** | `HEARTBEAT_ACK` | Discovery | UTF-8 JSON | Discovery Server confirms heartbeat and updates peer last-seen timestamp. |
| **16** | `UNREGISTER` | Discovery | UTF-8 JSON | Client notifies Discovery Server of graceful departure. |
| **17** | `FILE_DATA` | Single-Peer Streaming | Framed Binary | Binary slice of file data carrying transfer ID, byte offset, and raw data bytes. |
| **18** | `RESUME_REQUEST` | Transfer Resilience | UTF-8 JSON | Receiver requests resuming an interrupted transfer from a specific chunk/offset. |
| **19** | `RESUME_RESPONSE` | Transfer Resilience | UTF-8 JSON | Sender confirms resume offset or re-negotiates transfer state. |
| **20** | `FILE_QUERY` | Multi-Peer Download | UTF-8 JSON | Coordinator probes peers to check if they have a specific file available. |
| **21** | `FILE_QUERY_RESPONSE` | Multi-Peer Download | UTF-8 JSON | Peer confirms or denies availability of queried file and reports its chunk count. |

---

## 4. Binary Payload Layouts

To maximize throughput and minimize serialization overhead during bulk file transfer, data-heavy messages avoid JSON and use compact binary framing:

### 4.1. `CHUNK_RESPONSE` (Code 8)
Transmitted by source peers during multi-peer concurrent downloads.

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  idLen (1B)   |          transferId (idLen bytes, UTF-8)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          chunkIndex (4B)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          chunkLength (4B)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     rawChunkData (chunkLength bytes)          |
+---------------------------------------------------------------+
```

- **`idLen`** (1 byte): Length of `transferId` string in bytes (`1` to `255`).
- **`transferId`** (`idLen` bytes): UUID identifying the transfer session.
- **`chunkIndex`** (4 bytes, Big-Endian signed int): 0-based chunk sequence index.
- **`chunkLength`** (4 bytes, Big-Endian signed int): Exact byte count of chunk data.
- **`rawChunkData`** (`chunkLength` bytes): Unmodified payload bytes.

### 4.2. `FILE_DATA` (Code 17)
Transmitted during single-peer stream transfers.

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  idLen (1B)   |          transferId (idLen bytes, UTF-8)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          offset (8B, long)                    |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     rawChunkData (remaining payload)          |
+---------------------------------------------------------------+
```

- **`idLen`** (1 byte): Length of `transferId` string in bytes.
- **`transferId`** (`idLen` bytes): UUID identifying the transfer session.
- **`offset`** (8 bytes, Big-Endian signed long): Byte offset within target file.
- **`rawChunkData`**: Slice of file bytes corresponding to `(packet.length - 1 - idLen - 8)`.

---

## 5. Control Message JSON Schemas

Control packets encode UTF-8 JSON representations of operational messages.

### 5.1. `FILE_OFFER` (Code 5)
```json
{
  "transferId": "4a71b2d0-8f92-4f80-9a99-b1d5635f3d77",
  "fileName": "demo_video.mp4",
  "fileSize": 104857600,
  "senderPeerId": "peer-uuid-1",
  "senderDisplayName": "Peer A",
  "chunkSize": 1048576,
  "totalChunks": 100,
  "fileSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

### 5.2. `FILE_QUERY` (Code 20) & `FILE_QUERY_RESPONSE` (Code 21)
Query sent by receiver coordinator:
```json
{
  "queryId": "4a71b2d0-8f92-4f80-9a99-b1d5635f3d77",
  "fileName": "demo_video.mp4",
  "fileSize": 104857600,
  "expectedSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "requesterPeerId": "peer-uuid-4",
  "requesterDisplayName": "Peer D"
}
```
Response sent by source peer:
```json
{
  "queryId": "4a71b2d0-8f92-4f80-9a99-b1d5635f3d77",
  "available": true,
  "fileName": "demo_video.mp4",
  "fileSize": 104857600,
  "totalChunks": 100,
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "reason": null
}
```

### 5.3. `CHUNK_REQUEST` (Code 7)
```json
{
  "transferId": "4a71b2d0-8f92-4f80-9a99-b1d5635f3d77",
  "chunkIndex": 4
}
```

### 5.4. `REGISTER` (Code 10) & `REGISTER_ACK` (Code 11)
Registration request from client to discovery server:
```json
{
  "peerId": "7f8b9192-3112-4c27-86c5-4a693c0de987",
  "displayName": "Peer A",
  "host": "127.0.0.1",
  "port": 5001,
  "status": "ONLINE",
  "lastSeen": 1726200000000
}
```
Server response:
```json
{
  "success": true,
  "message": "Peer registered successfully"
}
```

---

## 6. Connection Lifecycle & Multiplexing

### 6.1. Connection Purpose Negotiation
To ensure heavy file downloads never starve or block real-time chat messages, LANDrop peers maintain separate TCP sockets for each purpose:
- **`CHAT`**: Dedicated connection for text messaging and peer presence.
- **`FILE_TRANSFER`**: Dedicated on-demand connection for bulk data transmission.

The connection purpose is established during the initial `HELLO` handshake:
```text
Client                                  Server
  |                                        |
  |--- HELLO [FILE_TRANSFER|Peer D] ------>| (Tags connection as FILE_TRANSFER)
  |<-- HELLO [FILE_TRANSFER|Peer A] -------|
  |                                        |
```
If the payload does not contain the `FILE_TRANSFER|` prefix, the connection defaults to `CHAT`.

### 6.2. Asynchronous Packet Processing
- Incoming packets are read in a dedicated background virtual thread (`peer-reader-<id>`).
- Packets are dispatched to registered `PacketHandler` callbacks based on `MessageType`.
- Outgoing packets are synchronized with a reentrant `writeLock` to guarantee atomic frame writes across threads without byte interleaving.

---

## 7. Protocol Validation & Security Rules

1. **Protocol Version Enforcement**: Sockets immediately abort if `version != 1`.
2. **Maximum Payload Size Limit**: `PacketDecoder` enforces a maximum payload limit of 2,097,152 bytes (2 MiB) by default. Any packet with `payloadLength > maxPayloadSize` or `payloadLength < 0` triggers an immediate `ProtocolException` to prevent memory exhaustion attacks.
3. **Stream Boundary Integrity**: Packets are read with `DataInputStream.readFully()`, guaranteeing complete chunk assembly before dispatch.
4. **Path Traversal Protection**: All received filenames are stripped of path separators (`/`, `\`, `..`) to prevent directory traversal outside designated folders.
5. **SHA-256 Format Verification**: Hashes must match the 64-character lowercase hex regular expression `^[a-fA-F0-9]{64}$`.
