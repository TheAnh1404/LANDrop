# Chạy LANDrop backend và frontend

Yêu cầu JDK 21, Maven 3.9+, Node.js 20+. Chạy các lệnh từ thư mục gốc repository.

## Chuẩn bị

```powershell
npm --prefix frontend ci
mvn -pl backend -am package -DskipTests
```

Chép `.env.example` thành `.env` và cấu hình PostgreSQL/JWT theo [DATABASE](../architecture/DATABASE.md). Áp dụng migrations trước khi dùng API cần database:

```powershell
mvn -pl backend compile exec:java "-Dexec.mainClass=com.landrop.database.DatabaseCli" "-Dexec.args=migrate"
```

## 1. Khởi chạy Server (LANDrop Server Desktop App)

### Cách A: Chạy độc lập Server & Bảng Điều Khiển Web (Control Center)
Mặc định server lắng nghe tại cổng `3000` (HTTP) và `3001` (WebSocket). Lệnh dưới mở LANDrop Server Desktop và tự khởi động backend. Chia sẻ URL/QR trên trang Tổng quan để người dùng vào `/client`; nút Quản trị web mở `/admin`. Trang gốc HTTP là portal chọn điểm truy cập. Xem [hướng dẫn bảng điều khiển desktop](SERVER_DESKTOP.md).

```powershell
npm run server
```

- Nếu muốn chạy không có giao diện GUI (Headless / Docker / Background):
  ```powershell
  npm run server:headless
  ```

### Cách B: Chạy cùng Web Workspace Frontend (Next.js) trên cùng máy tính
Vì giao diện Next.js Frontend mặc định sử dụng cổng `3000`, ta đổi cổng Backend sang `3100` và WebSocket sang `3101` để tránh xung đột cổng:

**Terminal 1 (Backend):**
```powershell
$env:PORT = '3100'
$env:WS_PORT = '3101'
npm run server:backend
```

**Terminal 2 (Frontend Next.js):**
```powershell
npm run dev
```

Mở `http://localhost:3000/connect`, nhập `http://localhost:3100`. Đăng ký/đăng nhập, tạo hoặc chọn workspace server trả về. Không dùng ID fixture như `ws_landrop_dev` với server thật. Admin Next.js tại `/admin` cần tài khoản SYSTEM_ADMIN.

Để xem giao diện khi chưa cấu hình database, chọn **Khám phá bản demo**. Dữ liệu mẫu và mô phỏng được gắn nhãn, không gọi API thật.

Production:

```powershell
npm run build
npm --prefix frontend run start
```

## Kiểm tra chức năng

- Auth: đăng ký, login thường/admin, refresh và logout.
- Workspace: tạo/chọn nhóm, sửa thông tin, thành viên và tạo kênh.
- Chat: gửi/reply/sửa/xóa; lịch sử cursor và đồng bộ REST mỗi 5 giây.
- Tài liệu: thư mục, metadata, checksum/người đăng ký, đổi tên/di chuyển, xóa file hoặc thư mục rỗng.
- Admin: user/workspace/client/session/log; quản lý thông tin và quyền tài khoản, workspace/thành viên, khóa user, thu hồi phiên, ngắt client, cấu hình server.
- Transfer: tra cứu yêu cầu tồn tại bằng UUID; demo chỉ mô phỏng tiến trình.

Backend hỗ trợ điều phối WebRTC qua gateway chung theo [contract REALTIME-02](../api/REALTIME_02_SIGNALING_CONTRACT.md); server chỉ relay signaling, không xử lý file binary. Việc truyền bytes phụ thuộc triển khai client. Xem [CLI_DEMO](CLI_DEMO.md) để chạy truyền LAN bằng CLI.

Từ máy LAN khác, địa chỉ backend phải là IP máy server, ví dụ `http://192.168.1.10:3100`; localhost luôn chỉ máy đang mở trình duyệt. Kiểm tra bind address/firewall khi không truy cập được.

## Kiểm tra tự động

```powershell
npm test
npm --prefix frontend test
node frontend/node_modules/typescript/bin/tsc --project frontend/tsconfig.json --noEmit --incremental false
```

Xem [báo cáo tích hợp](../reports/FRONTEND_BACKEND_INTEGRATION.md) và [TEST_PLAN](../TEST_PLAN.md) để biết phạm vi kiểm tra.

Nếu build gặp `spawn EPERM`, dùng tùy chọn worker threads đã mô tả trong [frontend README](../../frontend/README.md). Bản preview kiểm tra ngày 06/10 chạy ở cổng 3006 với output `.next-verify`; cổng 3000 vẫn là mặc định khi tự chạy ứng dụng.
