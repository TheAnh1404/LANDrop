# LANDrop Performance Baseline & Benchmark Report

This document records the empirical performance baseline of the LANDrop file transfer engine, measured using the automated benchmark suite [`PerformanceBaselineTest.java`](file:///Users/theanhmac/Project/LANDrop/backend/src/test/java/com/landrop/benchmark/PerformanceBaselineTest.java).

---

## 1. Important Benchmark Environment Disclaimer

> [!WARNING]
> **LOCAL LOOPBACK TCP DISCLAIMER**
> 
> The benchmark measurements documented herein were executed over **local loopback TCP (`127.0.0.1`)** on macOS (Apple Silicon).
> 
> **These numbers reflect kernel socket buffer throughput and memory bus speed; they are NOT representative of WAN, Internet, or congested Wi-Fi network performance.**
> In real-world multi-host LAN deployments, performance will be bounded by Wi-Fi signal quality, physical switch capacity (1 Gbps / 10 Gbps Ethernet), disk I/O, and peer upload limits.

---

## 2. Benchmark Configuration

- **Platform**: macOS (Apple Silicon), JDK 21 (Temurin / OpenJDK 64-Bit)
- **Network Interface**: Loopback (`127.0.0.1`)
- **Transport**: Standard Java TCP sockets (`java.net.Socket`, `java.net.ServerSocket`)
- **Framing**: Custom 14-byte binary framing header
- **Test Payload**: 2,097,152 bytes (2.0 MiB)
- **Chunk Size**: 65,536 bytes (64 KiB)
- **Total Chunks**: 32 chunks
- **Integrity Validation**: Streaming SHA-256 digest computation and bit-for-bit file comparison

---

## 3. Verified Benchmark Results

| Benchmark Scenario | Active Peers | Payload Size | Chunks | Duration | Effective Throughput | Chunk Distribution | Integrity Check |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Single-Peer Download** | 1 | 2.0 MiB | 32 | **24 ms** | **~83.33 MB/s** | Peer A: 32 / 32 | **PASS (100% Match)** |
| **Multi-Peer Download (Swarm)** | 2 | 2.0 MiB | 32 | **10 ms** | **~200.00 MB/s** | Peer A: 15 / 32<br>Peer B: 17 / 32 | **PASS (100% Match)** |
| **Mid-Transfer Failover** | 2 → 1 | 2.0 MiB | 32 | **29 ms** | **~68.96 MB/s** | Peer B failed at chunk 4;<br>Peer A completed remaining 30 | **PASS (100% Match)** |

---

## 4. Key Performance Observations

### 4.1. Multi-Peer Speedup (~2.4x Throughput)
- By splitting the 32 chunks across two concurrent worker threads reading from distinct TCP sockets, transfer latency dropped from **24 ms** to **10 ms**.
- The `ChunkScheduler` achieved near-optimal load balancing: **15 chunks** processed by Peer A and **17 chunks** processed by Peer B.
- Java NIO `FileChannel` positional writing allowed simultaneous writes without lock contention or write serialization bottlenecks.

### 4.2. Failover Resilience Overhead
- In the failover benchmark, Peer B was intentionally severed while chunk 4 was in flight.
- Detection, rollback of chunk 4 to `MISSING`, worker teardown, and dynamic reassignment of all remaining chunks to Peer A completed with only **5 ms of recovery overhead** compared to single-peer baseline (29 ms vs 24 ms).
- Cryptographic integrity remained uncompromised: SHA-256 verification passed cleanly on the assembled file.

---

## 5. How to Reproduce Benchmarks

To execute the automated benchmark on your local machine:
```bash
mvn test -pl backend -Dtest=PerformanceBaselineTest
```
Output will display real-time execution timing, chunk breakdowns, and hash validation messages.
