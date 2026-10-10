# LANDrop 🚀

> **High-Performance LAN P2P File Sharing & Collaborative Realtime Workspace**  
> Nền tảng truyền tệp ngang hàng (P2P) siêu tốc độ đường truyền nội mạng và không gian làm việc cộng tác thời gian thực không phụ thuộc vào điện toán đám mây.

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Next.js 14](https://img.shields.io/badge/Next.js-14-black?style=for-the-badge&logo=next.js&logoColor=white)](https://nextjs.org/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-3178C6?style=for-the-badge&logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![WebRTC](https://img.shields.io/badge/WebRTC-P2P_DataChannel-333333?style=for-the-badge&logo=webrtc&logoColor=white)](https://webrtc.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)

---

## 📌 Giới thiệu tổng quan

**LANDrop** được xây dựng với mục tiêu giải quyết triệt để bài toán chia sẻ tệp dung lượng lớn và giao tiếp tức thì giữa các thiết bị trong cùng mạng cục bộ (LAN/Wi-Fi) mà **không tiêu tốn băng thông Internet**, **không giới hạn dung lượng tệp** và **bảo mật riêng tư tuyệt đối (Zero-Cloud)**.

Hệ thống sở hữu kiến trúc **Dual-Engine (Hai tầng động cơ song song)**:
1. **Native Java 21 TCP P2P Engine:** Động cơ truyền tải trực tiếp qua socket nhị phân, lập lịch đa nguồn tải (Multi-peer Swarm), kiểm tra toàn vẹn băm SHA-256 và tối ưu I/O với Java 21 Virtual Threads & `FileChannel`.
2. **Browser-First WebRTC DataChannel Engine:** Trải nghiệm chia sẻ tệp và hội thoại không cần cài đặt ứng dụng client. Người dùng chỉ cần mở trình duyệt trên điện thoại hoặc máy tính để quét radar LAN, kéo thả truyền tệp dung lượng không giới hạn và nhắn tin mã hóa đầu cuối.

---

## ✨ Tính năng nổi bật

### ⚡ 1. Truyền tệp siêu tốc (LAN Wire Speed)
- **Tối ưu hóa Event-Driven Backpressure:** Khắc phục triệt để điểm nghẽn polling trễ nhân tạo, sử dụng sự kiện phần cứng `bufferedamountlow` với ngưỡng đệm 1 MB và kích thước chunk 64 KB, giải phóng toàn bộ băng thông card mạng (50 – 110+ MB/s trên hạ tầng Gigabit LAN).
- **Đọc tệp khối lớn (1 MB Batched Read):** Giảm hơn 93% số lệnh gọi hệ thống (I/O syscalls) bằng cách đọc khối nhị phân lớn từ File API trước khi băm luồng.
- **Bảo vệ bộ nhớ chống OOM (Chunk Coalescing):** Cơ chế gom nhóm chunk vào Blob định kỳ ở phía nhận, đảm bảo truyền tải an toàn các tệp kích thước lớn (nhiều GB) mà không gây tràn RAM trình duyệt.
- **Giao diện mượt mà 60 FPS:** Bộ điều tiết (Throttle) 10 Hz giới hạn tần số cập nhật tiến độ, chấm dứt hoàn toàn hiện tượng đóng băng DOM/React khi truyền hàng trăm nghìn gói tin.

### 💬 2. Chat P2P Realtime & Đồng bộ tức thì
- **Độ ổn định cao với Outbox Queue & Retry ACK:** Tin nhắn gửi đi khi kênh chưa sẵn sàng hoặc mạng chập chờn sẽ được xếp hàng đợi và tự động thử lại (tối đa 3 lần) cho tới khi nhận phản hồi ACK.
- **Trạng thái phát tin trực quan:** Hiển thị rõ ràng trạng thái tin nhắn: `🕒` (Đang chờ gửi), `✓` (Đã gửi đi), `✓✓` (Đã nhận thành công).
- **Keep-Alive Heartbeat:** Chu kỳ PING/PONG 10 giây giữ thông cổng NAT router và liên tục cập nhật độ trễ (RTT) theo thời gian thực.
- **Lưu vết hội thoại cục bộ (Local Persistence):** Tự động lưu lịch sử tin nhắn mã hóa theo từng không gian làm việc (Workspace) và thiết bị đối tác (Peer ID) trên thiết bị.

### 🖥️ 3. LANDrop Server Desktop App (GUI Hiện Đại)
- **Giao diện FlatLaf Dark UI:** Ứng dụng Desktop Java Swing hiện đại tích hợp khay hệ thống (System Tray).
- **Cấp mã QR & URL tức thì:** Tự động nhận diện dải IP LAN nội bộ và tạo mã QR giúp thiết bị di động quét và truy cập Web App ngay lập tức.
- **Giám sát thời gian thực:** Đồng hồ đo tốc độ truyền tệp, danh sách thiết bị đang kết nối (Connected Clients), bảng điều khiển quản trị người dùng, workspace và nhật ký hệ thống.
- **Chế độ Headless:** Hỗ trợ chạy dòng lệnh gọn nhẹ cho máy chủ Ubuntu/Debian, Docker và hạ tầng CI/CD.

### 🏢 4. Không gian làm việc cộng tác (Workspaces & Security)
- **Bảo mật chuẩn Enterprise:** Xác thực JWT Access/Refresh tokens tự động xoay vòng, băm mật khẩu với SHA-256 + Salt.
- **Cơ sở dữ liệu bền vững:** Tích hợp PostgreSQL 16 với Connection Pooling HikariCP hiệu năng cao và tự động hóa migration cơ sở dữ liệu.
- **Hỗ trợ đa tab độc lập:** Tự động đăng ký và phân biệt danh tính phiên (`clientInstanceId`) trên từng tab trình duyệt của người dùng.

---

## 🏗️ Kiến trúc hệ thống

```text
               +--------------------------------------------------------+
               |             LANDrop Server Gateway (Java 21)           |
               |  - REST APIs (Auth, Workspaces, Files, Admin)          |
               |  - WebSocket Gateway (Signaling Relay & Presence)      |
               |  - HikariCP Connection Pool + PostgreSQL 16 Database   |
               +---------------------------+----------------------------+
                                           |
                 WebRTC Signaling / Auth   |   WebRTC Signaling / Auth
                 WebSocket (Port 3001)     |   WebSocket (Port 3001)
                                           v
    +--------------------------------+           +--------------------------------+
    |         Client A (Web/PC)      |  WebRTC   |       Client B (Web/Mobile)    |
    |  - Next.js 14 / Bundled Client |<=========>|  - Next.js 14 / Bundled Client |
    |  - RTCDataChannel (SCTP 64 KB) |   Direct  |  - RTCDataChannel (SCTP 64 KB) |
    |  - Outbox Queue + ACK Retry    |    P2P    |  - Outbox Queue + ACK Retry    |
    |  - Event Backpressure (No-Wait)|  Channel  |  - Chunk Coalescing (No-OOM)   |
    +--------------------------------+           +--------------------------------+
                                           
========================================================================================
                  Native High-Throughput Java TCP Subsystem (LAN Core)
========================================================================================
    +----------------------+                       +----------------------+
    |    Java Peer A       |<=====================>|     Java Peer B      |
    |    - TCP Socket      |  Raw TCP LAN Socket   |     - TCP Socket     |
    |    - TCP_NODELAY     |  14-Byte Binary Frame |     - TCP_NODELAY    |
    |    - Virtual Threads |  SHA-256 Hash Verify  |     - Virtual Threads|
    +----------------------+                       +----------------------+
```

---

## 🛠️ Công nghệ sử dụng

| Tầng hệ thống | Công nghệ / Thư viện chính |
| :--- | :--- |
| **Backend Core** | Java 21 LTS, Virtual Threads, Java NIO, Java-WebSocket, FlatLaf (Dark Look & Feel), ZXing QR |
| **Database & ORM** | PostgreSQL 16, HikariCP Connection Pool, Flyway-style SQL Migrations, Prisma (Schema Modeling) |
| **Frontend Web** | Next.js 14 (App Router), React 18, TypeScript 5.6, Tailwind CSS, Lucide Icons |
| **Transport Protocols** | WebRTC RTCDataChannel (SCTP), TCP Raw Sockets, WebSocket (RFC 6455), HTTP/1.1 REST |
| **DevOps & Containers** | Docker, Docker Compose, Maven 3.9+, Node.js 20+ |

---

## 🚀 Hướng dẫn cài đặt & Khởi chạy

### Yêu cầu tiên quyết
- **Java Development Kit (JDK):** Phiên bản 21 trở lên.
- **Node.js:** Phiên bản 20.x trở lên cùng `npm`.
- **Maven:** Phiên bản 3.9+ (hoặc dùng script Maven Wrapper có sẵn).
- *(Tùy chọn)* **Docker & Docker Compose** để chạy nhanh toàn bộ hệ thống cùng PostgreSQL.

---

### Cách 1: Khởi chạy máy chủ nhanh (Khuyên dùng)

#### 1. Biên dịch và đóng gói Backend:
```powershell
mvn -pl backend -am package -DskipTests
```

#### 2. Khởi chạy Server Desktop GUI:
```powershell
npm run server
```
> Lệnh này sẽ mở cửa sổ **LANDrop Server Desktop**:
> - Hiển thị địa chỉ mạng LAN (ví dụ: `http://192.168.1.15:3000`) và mã **QR Code**.
> - Bạn có thể dùng điện thoại quét mã QR hoặc nhập địa chỉ trên bất kỳ máy nào trong mạng Wi-Fi để bắt đầu truyền tệp ngay lập tức.

#### 3. Khởi chạy ở chế độ Headless (Dành cho máy chủ Linux / CI / Docker):
```powershell
npm run server:headless
```

---

### Cách 2: Chạy Frontend Web (Next.js)

Để phát triển giao diện hoặc chạy riêng biệt Next.js:
```powershell
# Cài đặt dependencies
npm --prefix frontend ci

# Khởi chạy máy chủ dev
npm run dev
```
Truy cập ứng dụng tại: `http://localhost:3000`

---

### Cách 3: Triển khai bằng Docker Compose

Hệ thống cung cấp sẵn file `compose.yaml` tích hợp PostgreSQL 16 và Backend Server:

```powershell
# Khởi chạy container
docker compose up -d
```
Cổng dịch vụ:
- **Web & REST API:** `http://localhost:3000`
- **WebSocket Gateway:** `ws://localhost:3001`
- **Bundled Zero-Install Client:** `http://localhost:3000/client`

---

## ⚙️ Cấu hình môi trường (.env)

Sao chép file mẫu `.env.example` thành `.env` để tùy chỉnh thông số:

```ini
# Cấu hình cổng mạng
PORT=3000
WS_PORT=3001
HOST=0.0.0.0

# Cơ sở dữ liệu PostgreSQL
DATABASE_URL=jdbc:postgresql://localhost:5432/landrop?user=postgres&password=yourpassword

# Khóa bí mật mã hóa JWT Tokens
JWT_ACCESS_SECRET=your_super_secret_jwt_access_key_min_32_chars
JWT_REFRESH_SECRET=your_super_secret_jwt_refresh_key_min_32_chars

# Mật khẩu tài khoản quản trị viên ban đầu
ADMIN_PASSWORD=AdminPassWord@123
```

Thực hiện chạy Migration cơ sở dữ liệu:
```powershell
mvn -pl backend compile exec:java "-Dexec.mainClass=com.landrop.database.DatabaseCli" "-Dexec.args=migrate"
```

---

## 🧪 Kiểm thử hệ thống (Automated Testing)

Dự án được bao phủ toàn diện với hơn 50+ bài kiểm tra tích hợp và đơn vị:

```powershell
# 1. Kiểm tra kịch bản kiểm thử launcher server và client đi kèm (27 tests)
npm test

# 2. Kiểm tra bộ test WebRTC, Outbox Queue, ACK, File completion (12 tests)
npm --prefix frontend test

# 3. Kiểm tra TypeScript type safety
node frontend/node_modules/typescript/bin/tsc --project frontend/tsconfig.json --noEmit

# 4. Kiểm tra Backend Maven (Suite mạng P2P, Signaling Relay & Large File Transfer)
mvn -pl backend test "-Dtest=LargeFilePeerToPeerIntegrationTest,WebRTCSignalingRelayTest"
```

---

## 📂 Cấu trúc thư mục dự án

```text
LANDrop/
├── backend/                             # Java 21 Backend & Desktop Application
│   ├── src/main/java/com/landrop/
│   │   ├── admin/                       # Dịch vụ quản trị, bảo mật & bootstrap
│   │   ├── auth/                        # Xác thực JWT, mật khẩu & Refresh Token
│   │   ├── database/                    # HikariCP, Repositories & SQL Migrations
│   │   ├── discovery/                   # UDP/TCP Peer Registry & Discovery Engine
│   │   ├── peer/                        # TCP Peer Connection & Socket Engine
│   │   ├── server/                      # HTTP API, WebSocket Gateway & Desktop GUI
│   │   └── transfer/                    # Lập lịch đa luồng, băm SHA-256 & Swarm
│   └── src/main/resources/
│       ├── client/                      # Giao diện Web Client siêu nhẹ tích hợp sẵn
│       └── db/migration/                # Các file SQL Migration (V1 -> V6)
│
├── frontend/                            # Next.js 14 Single-Page Application
│   ├── app/                             # Next.js App Router (Auth, Workspaces, Transfers)
│   ├── components/screens/              # Giao diện Radar P2P, Chat Stream & Speedometer
│   └── lib/webrtc/                      # Động cơ WebRTC DataChannel, Buffer & ACK Retry
│
├── docs/                                # Hệ thống tài liệu kỹ thuật chi tiết
│   ├── api/                             # Đặc tả API REST, WebSocket & P2P DataChannel
│   ├── architecture/                    # Thiết kế kiến trúc mạng, giao thức nhị phân & DB
│   └── guides/                          # Hướng dẫn kiểm thử đa máy, benchmark & demo
│
├── scripts/                             # Scripts điều phối phát triển và launcher tests
├── compose.yaml                         # Cấu hình Docker Compose cho Production
└── pom.xml                              # Cấu hình Maven cha
```

---

## 🔒 Mô hình an toàn & Bảo mật

1. **Vận hành độc lập với Đám mây (Zero-Cloud Data Flow):** Dữ liệu truyền tải giữa các thiết bị đi trực tiếp qua kênh P2P mã hóa, không đi qua máy chủ bên thứ ba.
2. **Bảo mật tầng truyền dẫn (DTLS/SRTP):** Toàn bộ kênh WebRTC DataChannel đều được mã hóa ở mức phần cứng theo tiêu chuẩn DTLS.
3. **Mã hóa lưu trữ & Xác thực:** Mật khẩu được băm an toàn, phiên làm việc phân quyền chặt chẽ theo Workspace, phòng chống rò rỉ dữ liệu chéo giữa các tổ chức.

---

## 📚 Tài liệu kỹ thuật chuyên sâu

- [Danh mục tài liệu hoàn chỉnh](docs/README.md)
- [Đặc tả WebRTC P2P DataChannel](docs/api/P2P_DATA_CHANNEL.md)
- [Kiến trúc giao thức LAN TCP & Framing](docs/architecture/LAN_PROTOCOL.md)
- [Kiến trúc cơ sở dữ liệu & Persistence](docs/architecture/DATABASE.md)
- [Kế hoạch và quy trình kiểm thử](docs/TEST_PLAN.md)

---

## 📄 Bản quyền (License)

Dự án được phân phối dưới giấy phép mã nguồn mở. Mọi đóng góp và báo cáo lỗi (Pull Requests & Issues) đều được chào đón!
