# LANDrop Technical Defense & Architectural Q&A

This document provides clear, technically precise answers to critical engineering questions commonly raised during project defense presentations, code audits, and technical evaluations.

---

### 1. Why TCP instead of UDP?
- **Reliability & Order**: File transfer demands 100% bit-exact delivery. TCP provides built-in stream ordering, acknowledgment, flow control (sliding window), and retransmission on packet loss.
- **Congestion Control**: Modern TCP implementations (CUBIC/BBR) automatically adapt to network bandwidth limits without congesting the local LAN.
- **Simplicity**: Building a reliable sliding-window protocol with flow control and congestion avoidance on raw UDP (e.g., custom QUIC/UDT) would introduce excessive complexity and potential edge-case bugs without meaningful throughput advantages on low-latency local networks.

---

### 2. Why a custom binary protocol instead of HTTP/REST or WebSockets?
- **Zero Framework Bloat**: Eliminates the overhead of heavy web servers (Netty, Tomcat, Jetty, Spring Boot) and large runtime dependencies.
- **Minimal Framing Overhead**: LANDrop's fixed 14-byte binary header adds negligible overhead compared to verbose HTTP/1.1 headers (which often exceed 500–1,000 bytes per request).
- **Direct Streaming Efficiency**: Large binary chunks can be streamed directly from disk (`FileChannel`) to socket output streams (`DataOutputStream`) without Base64 encoding or JSON escaping.
- **Deterministic Validation**: Fixed 14-byte headers allow exact stream parsing with immediate rejection of invalid versions or oversized payloads.

---

### 3. Why use a Discovery Server if the system is P2P?
- **Bootstrapping Problem**: In a pure decentralized network, a newly joined peer has no initial knowledge of other peer IP addresses or ports without broadcasting.
- **Subnet & Firewall Limitations**: UDP multicast/broadcast (mDNS / Bonjour) is frequently blocked across managed Wi-Fi networks, VLANs, enterprise AP isolation, and cross-platform virtual machines.
- **Lightweight Coordinator**: The centralized Discovery Server acts strictly as a lightweight phonebook (registry). Once peer addresses are resolved, all chat and file transfers flow directly peer-to-peer.

---

### 4. Why not send the file through the Discovery Server?
- **Bandwidth Bottleneck**: If the central server routed files, its network interface and memory would bottleneck all transfers, reducing throughput as more peers join (O(N) load on server).
- **Scalability**: In direct P2P, aggregate network bandwidth scales linearly with the number of peers. Two peers transferring at 1 Gbps do not affect two other peers transferring at 1 Gbps.
- **Privacy**: The discovery coordinator has zero access to transfer payloads or private conversations.

---

### 5. How does multi-peer downloading work?
- **Chunk Partitioning**: Files are divided into uniform chunks (default: 1 MiB; configurable down to 64 KiB for benchmarks).
- **Availability Probing**: The receiver queries candidate peers via `FILE_QUERY` to confirm who possesses the file.
- **Coordinator & Scheduler**: The receiver's `MultiPeerDownloadCoordinator` uses a round-robin `ChunkScheduler` to distribute `MISSING` chunks across available `PeerDownloadWorker` threads.
- **Concurrent Positional Assembly**: Each worker requests chunks via `CHUNK_REQUEST`. Received chunks are written directly to their predetermined byte offset in a single `.landrop.part` file using Java NIO `FileChannel.write(buffer, offset)`. Multiple threads write concurrently without locking the whole file.

---

### 6. What happens when a peer disconnects during transfer?
- **Loss Detection**: The worker detects disconnection immediately via EOF/SocketException on the reader thread or a timeout on the chunk future.
- **State Rollback**: The worker marks the peer as offline and returns the interrupted chunk from `TRANSFERRING` to `MISSING`.
- **Dynamic Reassignment**: The coordinator is notified, re-evaluates missing chunks, and assigns the unfinished chunk to a surviving healthy peer.
- **Zero Data Loss**: Progress achieved by other chunks remains safely written in the partial file; only the failed chunk is retried.

---

### 7. How are chunks tracked?
- **Bitmap & State Machine**: Managed by `ChunkManager.java`.
- Each chunk progresses through distinct lifecycle states:
  ```text
  MISSING ──(assigned)──> REQUESTED ──(socket read)──> TRANSFERRING ──(written)──> COMPLETED
     ^                                                      |
     └────────────────(failure / disconnect)────────────────┘
  ```
- All state transitions use synchronized concurrency controls to prevent race conditions or duplicate assignments.

---

### 8. How is data integrity guaranteed?
- **End-to-End Cryptographic Checksum**: The file offer/query metadata specifies the authoritative 256-bit SHA-256 digest of the original file.
- **Post-Assembly Verification**: Once all chunks are written, `finalizeAndVerify()` computes the SHA-256 digest of the assembled `.landrop.part` file using Java's `MessageDigest`.
- **Atomic Promotion**: The file is only moved to its final user destination (`Files.move` with `ATOMIC_MOVE`) if the computed hash matches the expected hash bit-for-bit. If a mismatch is detected, the partial file is deleted and the transfer fails.

---

### 9. How is SHA-256 calculated?
- To avoid loading multi-gigabyte files into RAM, checksums are computed using a **streaming buffer** (8 KiB to 64 KiB blocks) read from disk through `DigestInputStream` or iterative `MessageDigest.update()`.
- This ensures fixed, minimal memory consumption (~64 KB) regardless of whether the file is 10 MB or 50 GB.

---

### 10. How is path traversal prevented?
- **Filename Sanitization**: Any filename received over the network (`FILE_OFFER`, `FILE_QUERY`) is sanitized before disk I/O.
- Directory separators (`/`, `\`), path traversal tokens (`..`), and null bytes are stripped or replaced with safe basenames.
- Target paths are resolved against a designated, sandboxed download directory (default: `~/Downloads/LANDrop`) and validated with `.normalize()` to prevent writing outside the sandbox.

---

### 11. How are sockets and threads cleaned up?
- **Java 21 Virtual Threads**: Asynchronous tasks, reader loops, and worker tasks run on lightweight virtual threads (`Thread.ofVirtual()`), minimizing operating system thread overhead.
- **`AutoCloseable` Pattern**: Core networking components (`PeerConnection`, `DiscoveryClient`, `MultiPeerDownloadCoordinator`, `PeerDownloadWorker`) implement `AutoCloseable`.
- **Graceful Termination**: Sockets and channels are closed in `finally` blocks, interrupt flags are respected, and executor services are shut down explicitly.

---

### 12. How does the system handle late peer joins?
- When a new peer enters the network and possesses the target file, `coordinator.addSourcePeer()` dynamically spawns a new `PeerDownloadWorker`.
- The worker is integrated into the active worker map, and `scheduleWork()` redistributes remaining `MISSING` chunks across the newly expanded pool without restarting or stalling the download.

---

### 13. What happens if the Discovery Server becomes unavailable?
- **Ongoing P2P Transfers Continue**: Active chat sessions and multi-peer file downloads operate on direct TCP sockets and are completely unaffected by discovery server failure.
- **Offline Resilience**: Discovery clients implement reconnect retry loops. If the server restarts, clients re-register automatically on their next heartbeat attempt.
- **Limitation**: New peers cannot be discovered until the discovery server comes back online.

---

### 14. What are the known limitations of the current implementation?
- **LAN-Optimized**: Designed for local subnets where peer IP addresses are directly reachable. Traversing symmetric NATs or corporate firewalls across the public Internet would require STUN/TURN/ICE relay mechanisms.
- **Unencrypted Transport**: Transmissions occur in plaintext TCP without TLS/DTLS. (Designed as a clean networking systems demonstration; TLS framing can be layered on top of sockets in future phases).
- **Manual Port Selection for Multi-Instance**: When running multiple instances on the same physical host, each instance requires distinct port configurations (`5001`, `5002`, etc.) to prevent port collisions.
