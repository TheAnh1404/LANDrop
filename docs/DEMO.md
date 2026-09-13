# LANDrop Presentation & Demonstration Guide

This guide provides a reproducible, step-by-step walkthrough for demonstrating the full capability matrix of **LANDrop** in a live technical presentation or academic defense.

---

## 1. Demo Preparation

### 1.1. System Requirements
- **Java Development Kit**: JDK 21+ (Oracle OpenJDK or Eclipse Temurin)
- **Build Tool**: Apache Maven 3.9+
- **Operating System**: macOS, Linux, or Windows 10/11
- **Network**: Localhost loopback or local subnet (Wi-Fi/Ethernet)

### 1.2. Build the Multi-Module Project
Open a terminal in the project root directory and execute:
```bash
mvn clean package -DskipTests
```
Ensure the build outputs:
- `backend/target/landrop-0.1.0-SNAPSHOT.jar`
- `frontend/target/landrop-frontend-0.1.0-SNAPSHOT.jar`

### 1.3. Demo Node & Port Matrix
For a local 4-peer demonstration, each instance runs on a unique local TCP port:

| Instance | Role | Local Port | Display Name | Launcher (macOS/Linux) | Launcher (Windows) |
| :--- | :--- | :---: | :--- | :--- | :--- |
| **Discovery Server** | Peer Registry | `8888` | *N/A* | `./start-discovery.sh` | `start-discovery.bat` |
| **Peer A** | Source Node 1 | `5001` | Peer A | `./start-peerA.sh` | `start-peerA.bat` |
| **Peer B** | Source Node 2 | `5002` | Peer B | `./start-peerB.sh` | `start-peerB.bat` |
| **Peer C** | Source Node 3 | `5003` | Peer C | `./start-peerC.sh` | `start-peerC.bat` |
| **Peer D** | Receiver Node | `5004` | Peer D | `./start-peerD.sh` | `start-peerD.bat` |

### 1.4. Prepare Test Shared File
Generate a 10 MiB test file with a known SHA-256 checksum:

**On macOS / Linux**:
```bash
mkdir -p "$HOME/Downloads/LANDrop/shared"
dd if=/dev/urandom of="$HOME/Downloads/LANDrop/shared/swarm_test.bin" bs=1048576 count=10
shasum -a 256 "$HOME/Downloads/LANDrop/shared/swarm_test.bin"
```
*(Copy the generated 64-character hex hash for use during the demo).*

**On Windows (PowerShell)**:
```powershell
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\Downloads\LANDrop\shared"
$data = New-Object byte[] (10 * 1024 * 1024); (New-Object Random).NextBytes($data); [IO.File]::WriteAllBytes("$env:USERPROFILE\Downloads\LANDrop\shared\swarm_test.bin", $data)
Get-FileHash "$env:USERPROFILE\Downloads\LANDrop\shared\swarm_test.bin" -Algorithm SHA256
```

---

## 2. Step-by-Step Demo Sequence

### Demo 1 — Peer Discovery & Dynamic Registration
1. **Start Discovery Server**:
   ```bash
   ./start-discovery.sh
   ```
   *Terminal Output*: `Discovery server started. Waiting for peers... Port: 8888`
2. **Launch Peer A and Peer B**:
   - In Terminal 2: `./start-peerA.sh`
   - In Terminal 3: `./start-peerB.sh`
3. **Observe UI**:
   - Both windows open with green **"Connected"** status under Discovery.
   - On Peer A, open the **Peers** tab: **"Peer B"** is visible with IP `127.0.0.1:5002` and an `ONLINE` badge.
   - On Discovery Server terminal, observe logs:
     ```text
     [REGISTER] Peer A registered
     [REGISTER] Peer B registered
     [Discovery] Heartbeat sent
     ```

---

### Demo 2 — Direct P2P Real-Time Chat
1. On **Peer A**, navigate to the **Peers** tab and click **Connect** next to **Peer B**.
2. Peer A's UI automatically switches to the **Chat** tab with active peer: `Peer B`.
3. Type `"Hello from Peer A via direct TCP socket!"` and press **Send** (or press Enter).
4. **Observe Peer B**:
   - Peer B's **Chat** tab instantly receives the message with timestamp and sender tag.
   - Reply from Peer B: `"Received loud and clear!"`.
5. **Technical Note**: This communication travels directly peer-to-peer over port 5002. The Discovery Server experiences zero network traffic during chat.

---

### Demo 3 — Single-Peer Direct File Transfer
1. On **Peer A**, navigate to the **Chat** tab (or **Transfers** tab).
2. Click **"Send File"** (paperclip icon in chat or header button).
3. Select a small test file (e.g. `sample.txt` or an image).
4. **Observe Peer B**:
   - Peer B displays an incoming transfer notification card: `sample.txt (from Peer A)`.
   - Click **"Accept"**.
5. Both peers show a live progress bar updating in real time.
6. Upon reaching 100%, the status switches to **"Verifying SHA-256 integrity..."**, followed by:
   `✓ Transfer completed • Integrity verified • SHA-256: [hash]`
7. Click the **"Open"** button on Peer B to launch the received file directly.

---

### Demo 4 — Multi-Peer Concurrent Download (Swarming)
1. **Launch Peer C and Peer D**:
   - In Terminal 4: `./start-peerC.sh`
   - In Terminal 5: `./start-peerD.sh`
2. Ensure `swarm_test.bin` is placed in the shared folders of **Peer A**, **Peer B**, and **Peer C**.
3. On **Peer D (Receiver)**, navigate to the **Transfers** tab and click **"⚡ Multi-Peer Download"**.
4. In the dialog, enter:
   - **File Name**: `swarm_test.bin`
   - **File Size**: `10485760` (10 MiB)
   - **SHA-256**: *(paste the 64-char hash generated in Step 1.4)*
   - Click **"Download"**.
5. **Observe Concurrent Download Swarm**:
   - Peer D sends `FILE_QUERY` to Peers A, B, and C.
   - All three peers respond `FILE_QUERY_RESPONSE (available=true)`.
   - The coordinator starts concurrent downloads across 3 workers.
   - The transfer card on Peer D displays:
     - Combined throughput speed (`MB/s`) and dynamic ETA.
     - Per-peer chunk breakdown badges:
       - `Peer A: 4 chunks`
       - `Peer B: 3 chunks`
       - `Peer C: 3 chunks`
   - Chunks are written into `.landrop.part` simultaneously via `FileChannel`.
6. Upon completion, SHA-256 is verified, the file is atomically renamed to `swarm_test.bin`, and status transitions to `COMPLETED`.

---

### Demo 5 — Dynamic Late Peer Join
1. Start a multi-peer download on **Peer D** with only **Peer A** and **Peer B** running.
2. Observe Peer A and Peer B sharing chunk downloads 50/50.
3. Mid-download, launch **Peer C** (`./start-peerC.sh`) having the same file.
4. Coordinator automatically detects Peer C, initializes a new worker, and partitions remaining chunks across all 3 peers.
5. The UI shows the new `Peer C: X chunks` badge join dynamically.

---

### Demo 6 — Mid-Transfer Peer Failure & Automatic Failover
1. Start a multi-peer download of `swarm_test.bin` on **Peer D** from **Peer A** and **Peer B**.
2. While the progress bar is at ~30%:
   - Force kill **Peer B** (press `Ctrl+C` in Terminal 3).
3. **Observe Automatic Recovery**:
   - Peer D detects socket disconnection on Peer B.
   - Peer B's in-flight chunk is automatically rolled back to `MISSING`.
   - Peer B's badge changes to `[Offline]`.
   - The coordinator automatically reassigns unfinished chunks to surviving **Peer A**.
   - The download continues smoothly without interruption or exception.
   - Download finishes at 100% and SHA-256 verification **succeeds**.

---

### Demo 7 — Cryptographic Data Integrity
1. Check the hash of the downloaded file on Peer D:
   ```bash
   shasum -a 256 "$HOME/Downloads/LANDrop/swarm_test.bin"
   ```
2. Verify that the output hash matches the original file hash bit-for-bit.
3. Confirm that no partial files (`*.part`) remain in the directory.

---

## 3. Clean Teardown

To shut down all running instances:
1. Close all JavaFX application windows (Peer A, B, C, D) — graceful `UNREGISTER` is transmitted to the Discovery Server automatically.
2. In Terminal 1, press `Ctrl+C` to stop the Discovery Server.
