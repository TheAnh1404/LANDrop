# LANDrop

LANDrop gồm backend Java 21 cho LAN P2P, xác thực, workspace, chat và quản trị; frontend hiện tại là Next.js 14 + React + TypeScript. Maven chỉ quản lý `backend/`.

**Trạng thái frontend:** giao diện đã thống nhất, nối REST API xác thực, workspace, chat, file metadata và quản trị. Chế độ demo được chọn riêng; dữ liệu mẫu không thay thế lỗi server. Truyền nội dung file giữa trình duyệt cần bổ sung transport. Xem [báo cáo tích hợp frontend/backend](docs/reports/FRONTEND_BACKEND_INTEGRATION.md) và [báo cáo cấu trúc/file lớn](docs/REPOSITORY_REPORT.md).

## Chạy giao diện web

Yêu cầu Node.js 20+ và npm. Từ thư mục gốc:

```powershell
npm --prefix frontend ci
npm run dev
```

Giao diện tại `http://localhost:3000`. `npm run client` và `npm run web` là alias chạy Next.js. Frontend không còn là module JavaFX.

```powershell
npm run build
npm --prefix frontend run start
```

## Chạy LANDrop Server (Desktop App)

Yêu cầu JDK 21 và Maven 3.9+. Có thể thay `mvn` bằng `.\mvnw.cmd` trên Windows hoặc `./mvnw` trên Linux/macOS.

```powershell
mvn -pl backend -am package -DskipTests
npm run server
```

Lệnh `npm run server` sẽ khởi chạy **LANDrop Server Desktop App** (ứng dụng máy tính có cửa sổ GUI hiện đại):
- **Cấp URL & Mã QR cho Client:** Hiển thị nổi bật địa chỉ URL trong mạng LAN (ví dụ: `http://192.168.1.15:3000`) cùng mã **QR Code** để các thiết bị khác (điện thoại, máy tính khác) quét hoặc mở trình duyệt truy cập ngay lập tức.
- **Bảng điều khiển máy chủ:** Bật/Tắt server, xem danh sách client kết nối thời gian thực, tiến trình truyền file P2P, realtime log console, và hỗ trợ thu nhỏ xuống khay hệ thống (System Tray).
- **Quản trị tích hợp:** quản lý tài khoản/quyền, thu hồi phiên, quản lý workspace/thành viên, xem dữ liệu PostgreSQL và nhật ký ngay trong desktop. Admin web đã được gỡ. Xem [hướng dẫn desktop](docs/guides/SERVER_DESKTOP.md).
- **Chế độ Headless/CLI:** Dành cho máy chủ Docker / CI / không màn hình:
  ```powershell
  npm run server:headless
  ```

Tạo `.env` từ [.env.example](.env.example), cấu hình PostgreSQL và JWT secrets để dùng tài khoản, workspace và chat bền vững. Áp dụng SQL migrations trước:

```powershell
mvn -pl backend compile exec:java "-Dexec.mainClass=com.landrop.database.DatabaseCli" "-Dexec.args=migrate"
```

Xem [hướng dẫn database](docs/architecture/DATABASE.md). `.env` là cấu hình cá nhân, không đưa vào Git. `prisma/schema.prisma` mô tả schema; Java runtime dùng JDBC/HikariCP và SQL migrations.

## Kiểm tra

```powershell
npm test
npm --prefix frontend test
node frontend/node_modules/typescript/bin/tsc --project frontend/tsconfig.json --noEmit --incremental false
mvn test "-Dlandrop.database.enabled=false" "-Dtest=*,!PersistentChatTest,!FileManagementTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Regression trên không dùng database. Các suite PostgreSQL cần database dành riêng cho test; một số suite opt-in cần `-Dlandrop.test.postgres=true`. Xem [kế hoạch kiểm thử](docs/TEST_PLAN.md).

## Cấu trúc

```text
backend/                    Java backend, desktop server, tests, SQL migrations
frontend/app/               Các route Next.js: auth, workspace
frontend/components/        Component giao diện và layout
frontend/lib/               API adapters, session, workspace data, demo transfer
scripts/                    Launcher backend và test Node
scripts/demo/               Công cụ CLI auth, discovery và realtime
docs/api/                   Hợp đồng backend REST/WebSocket
docs/architecture/          LAN core, giao thức TCP, database
docs/guides/                Demo CLI, test đa thiết bị, benchmark
prisma/schema.prisma        Mô tả schema PostgreSQL
.mvn/wrapper/               Maven Wrapper
compose.yaml                Backend và PostgreSQL chạy bằng Docker Compose
```

Chỉ dùng `compose.yaml` cho Compose. Dockerfile đóng gói backend; chưa đóng gói Next.js. Database mới cần áp dụng migrations trước khi dùng API phụ thuộc database.

- [Danh mục tài liệu](docs/README.md)
- [Hướng dẫn khởi chạy hệ thống (Run Guide)](docs/guides/RUN_GUIDE.md)
- [Backend và các entrypoint](backend/README.md)
- [Frontend](frontend/README.md)
- [Demo LAN qua CLI](docs/guides/CLI_DEMO.md)
- [Báo cáo dọn dẹp và chức năng file lớn](docs/REPOSITORY_REPORT.md)
