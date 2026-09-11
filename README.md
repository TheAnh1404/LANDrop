# LANDrop

A peer-to-peer (P2P) desktop application for local network file sharing and real-time messaging.

## Project Structure

```text
LANDrop/
├── backend/          # Backend TCP P2P Core, Networking Engine & Protocol
│   ├── pom.xml
│   ├── README.md
│   └── src/
│       ├── main/java/com/landrop/network/
│       │   ├── PeerServer.java
│       │   ├── PeerClient.java
│       │   └── Main.java
│       └── test/java/com/landrop/network/
│           └── PeerNetworkTest.java
│
├── frontend/         # (Future Phase) JavaFX Desktop User Interface
├── pom.xml           # Root Maven Aggregator POM
├── .gitignore
└── README.md
```

## Phase
**Phase 1 — TCP P2P Core**

## Objective
The objective of Phase 1 is to prove that two independent Java processes can communicate directly through raw TCP sockets on a local network or localhost using Java's standard networking APIs (`java.net.Socket` and `java.net.ServerSocket`). Every peer is designed to act as both a TCP server and a TCP client in the complete P2P architecture.

## Architecture
In this phase, Peer A connects directly to Peer B over TCP:

```text
Peer A
  │
  │ TCP
  ▼
Peer B
```

In the overall LANDrop architecture, peers communicate directly point-to-point without intermediary data relay:

```text
Discovery Server (Future Phase)
       │
       │ peer discovery only
       ▼
┌───────────────┐
│   Peer A      │
│ TCP Server    │
│ TCP Client    │
└───────┬───────┘
        │
        │ Direct TCP
        │
┌───────▼───────┐
│   Peer B      │
│ TCP Server    │
│ TCP Client    │
└───────────────┘
```

## Requirements
* Java 21 (JDK 21)
* Apache Maven 3.9+

## Build & Test
From the project root:

```bash
mvn clean test
```

To package the JAR:

```bash
mvn clean package
```

The output JAR is generated at `backend/target/landrop-0.1.0-SNAPSHOT.jar`.

## Run Server
Start the peer server on port 5002:

```bash
java -jar backend/target/landrop-0.1.0-SNAPSHOT.jar server 5002
```

## Run Client
In another terminal, connect to the peer server:

```bash
java -jar backend/target/landrop-0.1.0-SNAPSHOT.jar client localhost 5002 "Hello Bob"
```

## Expected Output

### Server
```text
=================================
LANDrop - Peer Server
=================================

Starting server...
Port: 5002

Server started successfully.
Waiting for peer connection...

Peer connected!

Received:
Hello Bob

Sending response:
Hello Alice
```

### Client
```text
=================================
LANDrop - Peer Client
=================================

Connecting to localhost:5002...

Connected successfully.

Sending message:
Hello Bob

Waiting for response...

Received:
Hello Alice
```

## Current Limitations
Phase 1 strictly establishes the raw TCP networking core. It does **NOT** yet contain:
* peer discovery
* chat UI / JavaFX
* file sharing & chunking
* SHA-256 integrity verification
* resume / retry
* multi-peer download
* SQLite database
* encryption

## Next Phase
**Phase 2 — Custom TCP Application Protocol**
