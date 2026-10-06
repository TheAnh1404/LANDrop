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

## Backend ở terminal thứ nhất

```powershell
$env:PORT = '3100'
$env:WS_PORT = '3101'
npm run server:backend
```

Đổi backend HTTP từ mặc định 3000 sang 3100 để chạy cùng Next.js. Health tại `http://localhost:3100/health`; admin HTML Java tại `http://localhost:3100/admin`. Health phản hồi không bảo đảm mọi API phụ thuộc database khả dụng.

## Frontend ở terminal thứ hai

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

**Truyền bytes giữa hai trình duyệt chưa được triển khai.** Backend dùng TCP/discovery; WebSocket control chưa phải signaling WebRTC browser. Xem [CLI_DEMO](CLI_DEMO.md) để chạy truyền LAN bằng CLI.

Từ máy LAN khác, địa chỉ backend phải là IP máy server, ví dụ `http://192.168.1.10:3100`; localhost luôn chỉ máy đang mở trình duyệt. Kiểm tra bind address/firewall khi không truy cập được.

## Kiểm tra tự động

```powershell
npm test
npm --prefix frontend test
node frontend/node_modules/typescript/bin/tsc --project frontend/tsconfig.json --noEmit --incremental false
```

Xem [báo cáo tích hợp](../reports/FRONTEND_BACKEND_INTEGRATION.md) và [TEST_PLAN](../TEST_PLAN.md) để biết phạm vi kiểm tra.

Nếu build gặp `spawn EPERM`, dùng tùy chọn worker threads đã mô tả trong [frontend README](../../frontend/README.md). Bản preview kiểm tra ngày 06/10 chạy ở cổng 3006 với output `.next-verify`; cổng 3000 vẫn là mặc định khi tự chạy ứng dụng.
