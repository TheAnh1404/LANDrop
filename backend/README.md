# LANDrop — Backend

## Phase
Phase 2 — Custom TCP Protocol

## Objective
The objective of Phase 2 is to replace raw String communication with a robust, structured binary packet protocol with headers, message typing, payload length validation, request ID correlation, and protection against TCP fragmentation and memory exhaustion.

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
* **Message Type**: 1 byte (`HELLO`, `CHAT_MESSAGE`, `PING`, `PONG`, etc.)
* **Payload Length**: 4 bytes (int, unsigned range bounded to `MAX_PAYLOAD_SIZE = 1 MB`)
* **Request ID**: 8 bytes (long, monotonic request/response correlation)
* **Payload**: UTF-8 bytes / raw binary data

## Requirements
* Java 21 (JDK 21)
* Apache Maven 3.9+

## Build & Test
Run all unit and integration tests:

```bash
mvn clean test
```

Package the executable JAR:

```bash
mvn clean package
```

The output JAR is at `target/landrop-0.1.0-SNAPSHOT.jar`.

## Run Server
Start the peer in server mode on port 5002:

```bash
mvn exec:java -Dexec.mainClass="com.landrop.network.Main" -Dexec.args="server 5002"
```

Or run via packaged JAR:

```bash
java -jar target/landrop-0.1.0-SNAPSHOT.jar server 5002
```

## Run Client
Connect to the peer server and execute the protocol flow (HELLO -> CHAT_MESSAGE -> PING/PONG):

```bash
mvn exec:java -Dexec.mainClass="com.landrop.network.Main" -Dexec.args="client localhost 5002"
```

Or with a custom chat message:

```bash
mvn exec:java -Dexec.mainClass="com.landrop.network.Main" -Dexec.args="client localhost 5002 'Xin chào LANDrop!'"
```

## Current Limitations (Phase 2)
Phase 2 establishes the custom TCP binary packet protocol. It does **NOT** yet contain:
* peer discovery
* chat UI / JavaFX
* file sharing & chunking
* SHA-256 integrity verification
* resume / retry
* multi-peer download
* SQLite
* encryption

## Next Phase
**Phase 3 — File Chunking & Transfer Protocol**
