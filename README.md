# LANDrop

A peer-to-peer (P2P) desktop application for local network file sharing and real-time messaging built with Java 21, JavaFX 21, and Maven multi-module architecture.

---

## 🚀 Hướng Dẫn Cách Chạy (Quick Start Guide)

### 1. Yêu cầu hệ thống
* **Java 21+** (JDK 21)
* **Apache Maven 3.9+**

### 2. Biên dịch dự án
Từ thư mục gốc `LANDrop`:
```bash
mvn clean install
```
*(Biên dịch và chạy kiểm thử toàn bộ 111 unit & integration tests trên cả `backend` (100 tests) và `frontend` (11 tests)).*

---

### 3. Nguyên Tắc Phân Bổ Cổng (Port Allocation)
Để tránh xung đột cổng `Address already in use`, hãy chạy các thành phần theo cấu hình cổng riêng biệt:

| Thành phần | Vai trò | Cổng sử dụng | Lệnh / Script |
| :--- | :--- | :---: | :--- |
| **Discovery Server** | Đăng ký & phân giải peer nội mạng | **`8888`** | `./start-discovery.sh` |
| **Peer A** | Desktop UI Client 1 | **`5001`** | `./start-peerA.sh` |
| **Peer B** | Desktop UI Client 2 | **`5002`** | `./start-peerB.sh` |

> [!IMPORTANT]
> * **Discovery Server** chỉ cần bật **1 cửa sổ duy nhất** trên cổng `8888`.
> * Mỗi Peer khi mở giao diện cần có **Local P2P Port khác nhau** (ví dụ Peer A dùng `5001`, Peer B dùng `5002`).

---

### 4. Cách Chạy Nhanh Bằng Script Tiện Ích (Khuyên dùng)

Mở **3 cửa sổ Terminal riêng biệt** tại thư mục gốc `/Users/theanhmac/Project/LANDrop`:

#### Terminal 1: Khởi động Discovery Server
```bash
cd /Users/theanhmac/Project/LANDrop
./start-discovery.sh
```
> Server sẽ lắng nghe tại cổng `8888`:
> ```text
> Starting LANDrop Discovery Server on port 8888...
> =================================
> LANDrop - Discovery Server
> =================================
> Discovery server started.
> Waiting for peers...
> ```

#### Terminal 2: Khởi động Peer A (Port 5001)
```bash
cd /Users/theanhmac/Project/LANDrop
./start-peerA.sh
```
1. Cửa sổ **LANDrop Setup** mở lên với cấu hình mặc định:
   - **Display Name**: `Peer A`
   - **Local P2P Port**: `5001`
   - **Discovery Server**: `localhost:8888`
2. Bấm nút **"Connect & Join Network"** $\rightarrow$ Hệ thống đăng ký vào Discovery và mở Dashboard chính.

#### Terminal 3: Khởi động Peer B (Port 5002)
```bash
cd /Users/theanhmac/Project/LANDrop
./start-peerB.sh
```
1. Cửa sổ **LANDrop Setup** mở lên với cấu hình mặc định:
   - **Display Name**: `Peer B`
   - **Local P2P Port**: `5002`
   - **Discovery Server**: `localhost:8888`
2. Bấm nút **"Connect & Join Network"** $\rightarrow$ Mở Dashboard chính của Peer B.

#### Bắt đầu Chat P2P giữa Peer A và Peer B:
1. Trên cửa sổ **Peer B**, bấm vào menu **Peers** ở thanh điều hướng bên trái.
2. Danh sách sẽ hiển thị **Peer A** (trạng thái Online tại `127.0.0.1:5001`).
3. Bấm nút **"Connect"** màu tím bên cạnh Peer A.
4. Màn hình sẽ tự động chuyển sang tab **Messages** $\rightarrow$ Bạn có thể gửi tin nhắn qua lại tức thì hai chiều giữa 2 cửa sổ!

---

### 5. Cách Chạy Trực Tiếp Bằng Lệnh Maven

#### A. Khi đang đứng tại thư mục gốc `LANDrop`:
* **Discovery Server**:
  ```bash
  mvn -pl backend exec:java -Dexec.mainClass="com.landrop.discovery.DiscoveryServer"
  ```
* **Peer A (Port 5001)**:
  ```bash
  mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5001 --display-name=PeerA"
  ```
* **Peer B (Port 5002)**:
  ```bash
  mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5002 --display-name=PeerB"
  ```

#### B. Khi đang đứng bên trong thư mục con:
* **Từ thư mục `backend/`**:
  ```bash
  cd backend
  mvn exec:java -Dexec.mainClass="com.landrop.discovery.DiscoveryServer"
  ```
* **Từ thư mục `frontend/`**:
  ```bash
  cd frontend
  # Lưu ý: KHÔNG dùng cờ -pl frontend khi đã ở bên trong thư mục frontend/
  mvn javafx:run -Djavafx.args="--peer-port=5001 --display-name=PeerA"
  ```

---

### 6. Cách Chạy Chế Độ Dòng Lệnh CLI (Terminal Chat)
Nếu muốn chạy chat P2P không cần giao diện đồ họa:

* **Peer 1 (Server mode - Alice, Port 5001)**:
  ```bash
  mvn -pl backend exec:java -Dexec.mainClass="com.landrop.chat.ChatCli" -Dexec.args="server 5001 Alice"
  ```
* **Peer 2 (Client mode - Bob, kết nối tới Alice)**:
  ```bash
  mvn -pl backend exec:java -Dexec.mainClass="com.landrop.chat.ChatCli" -Dexec.args="client localhost 5001 Bob"
  ```

---

### 7. Xử Lý Các Sự Cố Thường Gặp (Troubleshooting)

#### Lỗi 1: `Address already in use`
* **Nguyên nhân**: Cổng `5001`, `5002` hoặc `8888` đang bị một tiến trình Java cũ chiếm giữ.
* **Cách khắc phục**: Chạy lệnh sau trong Terminal để giải phóng toàn bộ các cổng này:
  ```bash
  kill -9 $(lsof -t -i:5001 -i:5002 -i:8888) 2>/dev/null
  ```

#### Lỗi 2: `Could not find the selected project in the reactor: frontend`
* **Nguyên nhân**: Terminal đang đứng bên trong thư mục `frontend/` nhưng lại gõ lệnh có cờ `-pl frontend`. Cờ `-pl` chỉ hoạt động từ thư mục gốc.
* **Cách khắc phục**:
  * Nếu đang ở trong `frontend/`: bỏ `-pl frontend`, chỉ cần gõ `mvn javafx:run ...`.
  * Hoặc gõ `cd ..` để trở về thư mục gốc `LANDrop` rồi chạy lệnh.

#### Lỗi 3: `No plugin found for prefix 'javafx'`
* **Cách khắc phục**: Plugin JavaFX đã được cấu hình trong `<pluginManagement>` của root `pom.xml`. Hãy đảm bảo bạn đã chạy `mvn clean install` từ thư mục gốc một lần để cập nhật.

---

### 8. Chuyển File P2P Phân Đoạn & Kiểm Tra Toàn Vẹn SHA-256 (Phase 7 Guide)

LANDrop hỗ trợ truyền file phân đoạn (Chunk-based) tuần tự, kiểm tra tính toàn vẹn bằng mã băm SHA-256 dạng streaming trực tiếp giữa 2 peer:

```mermaid
sequenceDiagram
    autonumber
    actor PeerB as Peer B (Receiver)
    actor PeerA as Peer A (Sender)

    Note over PeerA: Tính trước SHA-256 (Streaming) & chia chunk (1 MiB)
    PeerA->>PeerB: FILE_OFFER (transferId, fileName, fileSize, chunkSize, totalChunks, fileSha256)
    Note over PeerB: Kiểm tra tính hợp lệ của metadata & Hiển thị thẻ xác nhận
    PeerB->>PeerA: FILE_REQUEST (transferId, accepted=true)

    loop Tải từng chunk tuần tự (Pull-based)
        PeerB->>PeerA: CHUNK_REQUEST (transferId, chunkIndex=0)
        PeerA-->>PeerB: CHUNK_RESPONSE #0 [1B idLen][transferId][4B idx][4B len][chunkData]
        Note over PeerB: Ghi vào file tạm .landrop.part tại offset tương ứng
        PeerB->>PeerA: CHUNK_REQUEST (transferId, chunkIndex=1)
        PeerA-->>PeerB: CHUNK_RESPONSE #1 [1B idLen][transferId][4B idx][4B len][chunkData]
        Note over PeerA,PeerB: Cập nhật Progress: Chunk X/Y, %, Tốc độ (MB/s)
    end

    Note over PeerB: Đổi trạng thái: VERIFYING<br/>Tính SHA-256 file tạm & đối soát với hash người gửi
    Note over PeerB: Đổi tên nguyên tử: .landrop.part -> file gốc
    PeerB->>PeerA: TRANSFER_ACK (transferId, success=true, transferredBytes)
    Note over PeerA,PeerB: Đổi trạng thái: COMPLETED (Hiển thị SHA-256 đã xác minh)
```

#### Cấu Trúc Các Gói Tin Điều Khiển & Dữ Liệu (Packet Layouts):
1. **`FILE_OFFER` (Code 5, JSON)**:
   ```json
   {
     "transferId": "41a996f0-4fa8-4b71-9ce1-8dfb8417c805",
     "fileName": "document.pdf",
     "fileSize": 10485760,
     "senderPeerId": "peer-a-uuid",
     "senderDisplayName": "Peer A",
     "chunkSize": 1048576,
     "totalChunks": 10,
     "fileSha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
   }
   ```
2. **`FILE_REQUEST` (Code 6, JSON)**:
   `{"transferId": "...", "accepted": true, "reason": ""}`
3. **`CHUNK_REQUEST` (Code 7, JSON)**:
   `{"transferId": "...", "chunkIndex": 0}`
4. **`CHUNK_RESPONSE` (Code 8, Binary Framing)**:
   `[1B transferIdLen][N bytes transferId][4B chunkIndex (int)][4B chunkLength (int)][M bytes chunkData]`
5. **`TRANSFER_ACK` (Code 9, JSON)**:
   `{"transferId": "...", "success": true, "transferredBytes": 10485760, "message": "Transfer completed successfully"}`

#### Các tính năng an toàn & độ tin cậy cốt lõi (Core Safety & Integrity):
* **Kiểm tra tính toàn vẹn SHA-256 (Streaming Hash)**:
  * Người gửi tính hash toàn bộ file trước khi phát thông điệp `FILE_OFFER`.
  * Người nhận sau khi thu thập đủ tất cả các chunk sẽ tính lại SHA-256 của file tạm. Nếu mã băm khớp 100%, file mới được xác nhận hợp lệ.
  * Nếu mã băm không khớp, file tạm lập tức bị xóa bỏ và chuyển trạng thái sang `FAILED`.
  * Quá trình băm chạy luồng ảo (`Virtual Thread`) với bộ đệm luồng, không nạp toàn bộ file vào RAM $\rightarrow$ an toàn với file nhiều GB.
* **Tập tin tạm (`.landrop.part`) & Đổi tên nguyên tử (`Atomic Move`)**:
  * Dữ liệu đang nhận được ghi trực tiếp vào `filename.landrop.part`.
  * Chỉ khi xác minh SHA-256 thành công, file mới được đổi tên nguyên tử thành tên tệp chính thức, không để lộ tệp tải dở dang.
* **Chống gói tin trùng lặp (`BitSet De-duplication`)**:
  * Người nhận theo dõi các chunk đã nhận qua cấu trúc `BitSet`, bỏ qua các gói tin trùng lặp mà không gây sai lệch dữ liệu.
* **Kiểm tra tính nhất quán tệp nguồn (Sender Consistency Check)**:
  * Người gửi kiểm tra dung lượng và sự tồn tại của tệp nguồn trước khi đọc từng chunk, phát hiện kịp thời nếu file bị can thiệp giữa chừng.
* **Hỗ trợ tệp 0-byte**:
  * Tệp rỗng (0 byte) được xử lý trực tiếp (`totalChunks = 0`), tính mã băm chuẩn của tệp rỗng và hoàn tất ngay lập tức mà không cần gửi `CHUNK_REQUEST`.
* **Trò chuyện song song không ngắt quãng**:
  * Các gói tin `CHAT_MESSAGE` và `PING`/`PONG` được xen kẽ nhịp nhàng trên cùng kết nối TCP nhờ cơ chế khóa ghi luồng an toàn (`writeLock`).

---

## 📁 Cấu Trúc Dự Án (Project Structure)

```text
LANDrop/
├── pom.xml                 # Root Maven Aggregator POM
├── README.md               # Tài liệu hướng dẫn dự án
├── start-discovery.sh      # Script bật Discovery Server (Port 8888)
├── start-peerA.sh          # Script bật Peer A UI (Port 5001)
├── start-peerB.sh          # Script bật Peer B UI (Port 5002)
│
├── backend/                # Module Backend (P2P Core & Networking Engine)
│   ├── pom.xml
│   ├── README.md
│   └── src/main/java/com/landrop/
│       ├── network/        # TCP raw socket Server & Client
│       ├── protocol/       # Binary TCP Packet, Framing, Encoder, Decoder (V1)
│       ├── peer/           # PeerConnection, PeerListener, Handshake
│       ├── chat/           # ChatService, ChatMessage, ChatCli
│       ├── discovery/      # DiscoveryServer, DiscoveryClient, PeerRegistry
│       └── transfer/       # FileTransferService, FileChunk, ChunkRequest/Response, SHA-256 Utils
│
└── frontend/               # Module Frontend (JavaFX Desktop UI)
    ├── pom.xml
    └── src/main/
        ├── java/com/landrop/ui/
        │   ├── MainApp.java           # JavaFX Application Entry Point
        │   ├── controllers/           # Setup, Main, Dashboard, Peers, Chat, Transfers, Settings
        │   ├── models/                # UI Data models & Observable properties
        │   ├── services/              # UIStateService (Bridge Network & JavaFX Thread)
        │   └── utils/                 # AlertUtils, UI Helpers
        └── resources/com/landrop/ui/
            ├── *.fxml                 # Giao diện FXML (Setup, Main, Peers, Chat, Transfers...)
            └── styles.css             # Theme màu sáng hiện đại (Indigo/Slate)
```

---

## 🧩 Tiến Độ Các Phase

* [x] **Phase 1**: TCP Socket Communication (PeerServer / PeerClient)
* [x] **Phase 2**: Custom Binary TCP Protocol (Packet, Encoder, Decoder, Framing, RequestID)
* [x] **Phase 3**: Real-time Bidirectional Chat (ChatService, Async Reader, ChatCli, `/ping`, `/help`, `/quit`)
* [x] **Phase 4**: Centralized Discovery Server (PeerRegistry, Heartbeat, Timeout Eviction, DiscoveryClient)
* [x] **Phase 5**: JavaFX Desktop User Interface (Modern Light Theme, Setup Screen, Dashboard, Peer Discovery View, Real-time Chat UI)
* [x] **Phase 6**: Basic P2P File Transfer (Direct TCP Streaming, 64KB NIO Chunks, Safe Path Resolution, In-Chat Transfer Cards, Transfers History View)
* [x] **Phase 7**: Chunked P2P File Transfer + SHA-256 Integrity Verification (Sequential 1 MiB Chunk Streaming, CHUNK_REQUEST/RESPONSE, Streaming SHA-256 Verification, Partial Files .part, BitSet De-duplication)
* [ ] **Phase 8**: Resume & Retry Strategy (Chunk Bitmap Persistence, Re-requesting Missing Chunks - Giai đoạn tiếp theo)
