# Hướng dẫn Khởi chạy LANDrop (Frontend, Backend, Server & Client)

Tài liệu này hướng dẫn chi tiết cách chạy toàn bộ hệ thống LANDrop trên một máy tính hoặc trong mạng nội bộ (LAN).

---

## 1. Phân bổ Cổng Mạng (Tránh Xung Đột)

Khi chạy cả Frontend và Backend trên cùng một máy tính:

| Thành phần | Giao thức | Cổng mặc định | Cổng khuyến nghị khi chạy chung | URL Truy cập |
| :--- | :--- | :--- | :--- | :--- |
| **Frontend Web** (Next.js) | HTTP | `3000` | **`3000`** | `http://localhost:3000` |
| **Backend REST & Health** | HTTP | `3000` | **`3100`** | `http://localhost:3100/health` |
| **Backend WebSocket / Signaling** | WebSocket | `3001` | **`3101`** | `ws://localhost:3101` |
| **Backend Admin HTML** | HTTP | `/admin` (cổng HTTP) | `/admin` cổng `3100` | `http://localhost:3100/admin` |
| **P2P File Transfer** | WebRTC DataChannel | Ngẫu nhiên (P2P Mesh) | N/A (Trực tiếp giữa 2 browser) | Tự động đàm phán qua WebRTC |

---

## 2. Chuẩn bị Môi trường

Yêu cầu hệ thống:
- **Node.js**: Phiên bản 20 trở lên (`node -v`).
- **npm**: Phiên bản 9 trở lên (`npm -v`).
- **JDK**: Java 21 LTS (`java -version`).
- **Maven**: Maven 3.9+ (`mvn -version` hoặc dùng wrapper `.\mvnw.cmd`).

Cài đặt thư viện frontend từ thư mục gốc:
```powershell
npm --prefix frontend install
```

---

## 3. Khởi chạy Backend Server (Terminal 1)

Mở cửa sổ PowerShell thứ nhất:

```powershell
# Thiết lập cổng để không xung đột với cổng 3000 của frontend
$env:PORT = "3100"
$env:WS_PORT = "3101"

# Khởi chạy backend
npm run server:backend
```

Khi server khởi động thành công, thông tin trạng thái sẽ hiển thị:
```text
==================================================
LANDrop Server (FOUNDATION-03A Control Center)
==================================================
Status:             RUNNING
Host:               0.0.0.0
Port (HTTP):        3100
Port (WebSocket):   3101
LAN URL (Health):   http://<IP_LAN>:3100/health
WebSocket URL:      ws://<IP_LAN>:3101
Control Center URL: http://<IP_LAN>:3100/admin
==================================================
```

Kiểm tra sức khỏe server:
- Mở trình duyệt truy cập: `http://localhost:3100/health`
- Bảng điều khiển quản trị: `http://localhost:3100/admin`

---

## 4. Khởi chạy Frontend Web Client (Terminal 2)

Mở cửa sổ PowerShell thứ hai:

### Chế độ Phát triển (Development):
```powershell
npm run dev
```
Giao diện sẽ chạy tại: **`http://localhost:3000`**.

### Chế độ Sản xuất (Production):
```powershell
npm run build
npm --prefix frontend run start
```

---

## 5. Trải nghiệm & Kiểm thử P2P Trình duyệt (Browser-to-Browser)

Để trải nghiệm truyền file trực tiếp giữa 2 máy hoặc 2 phiên duyệt web:

1. **Phiên A (Người gửi)**:
   - Mở trình duyệt tại `http://localhost:3000`.
   - Vào `/connect`, nhập server address: `localhost:3100`.
   - Vào `/auth/login`, đăng nhập với tài khoản người gửi (ví dụ: `theanh@landrop.io`).
   - Vào không gian làm việc `/w/ws_landrop_dev`.

2. **Phiên B (Người nhận)**:
   - Mở cửa sổ **Ẩn danh (Incognito)** hoặc trình duyệt khác tại `http://localhost:3000`.
   - Vào `/connect`, kết nối tới cùng server: `localhost:3100`.
   - Đăng nhập với tài khoản người nhận (ví dụ: `ken@landrop.io`).
   - Vào cùng không gian làm việc `/w/ws_landrop_dev`.

3. **Thực hiện truyền tệp**:
   - Tại Phiên A: Bấm nút **Send File**, chọn một tệp bất kỳ trên ổ cứng và chọn người nhận là phiên của Ken Nguyen. Bấm **Send Transfer**.
   - Tại Phiên B: Pop-up **Incoming Transfer Request** sẽ tự động hiển thị tức thì.
   - Bấm **Accept** tại Phiên B:
     - Kênh WebRTC `RTCDataChannel` được kết nối trực tiếp.
     - Dữ liệu tệp được phân mảnh 64 KB và truyền trực tiếp ngang hàng giữa 2 trình duyệt (máy chủ không lưu và không relay nội dung file).
     - Khi nhận đủ 100%, trình duyệt tự đối soát mã băm SHA-256 và kích hoạt lưu file xuống máy tính.

---

## 6. (Tùy chọn) Chạy CLI TCP Peer Client

Nếu cần kiểm thử tầng mạng TCP socket cấp thấp độc lập với trình duyệt:

1. **Chạy Discovery Server (Cổng 8888)**:
   ```powershell
   mvn -pl backend exec:java "-Dexec.mainClass=com.landrop.discovery.DiscoveryServer" "-Dexec.args=8888"
   ```

2. **Chạy Peer Alice (Cổng 5001)**:
   ```powershell
   mvn -pl backend exec:java "-Dexec.mainClass=com.landrop.network.Main" "-Dexec.args=peer 5001 localhost 8888 Alice"
   ```

3. **Chạy Peer Bob (Cổng 5002)**:
   ```powershell
   mvn -pl backend exec:java "-Dexec.mainClass=com.landrop.network.Main" "-Dexec.args=peer 5002 localhost 8888 Bob"
   ```

Các lệnh trong giao diện CLI:
- `/peers`: Liệt kê các máy tính trực tuyến.
- `/connect <peerId>`: Kết nối TCP socket trực tiếp tới peer.
- `/help`: Xem toàn bộ hướng dẫn lệnh.
- `/quit`: Thoát chương trình.
