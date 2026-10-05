# LANDrop

LANDrop gồm backend Java 21 cho LAN P2P, xác thực, workspace, chat và quản trị; frontend hiện tại là Next.js 14 + React + TypeScript. Maven chỉ quản lý `backend/`.

**Trạng thái frontend:** nhiều trang sử dụng `frontend/lib/mock-data.ts`; đăng nhập và tiến trình truyền file còn có mô phỏng. Chạy được giao diện không có nghĩa là đã tích hợp đầy đủ với backend. Xem [báo cáo cấu trúc và các file lớn](docs/REPOSITORY_REPORT.md).

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

## Chạy backend

Yêu cầu JDK 21 và Maven 3.9+. Có thể thay `mvn` bằng `.\mvnw.cmd` trên Windows hoặc `./mvnw` trên Linux/macOS.

```powershell
mvn -pl backend -am package -DskipTests
npm run server:backend
```

Backend mặc định dùng HTTP `3000`, WebSocket `3001`, admin HTML tại `/admin`. Khi chạy cùng frontend trên một máy, đổi backend sang HTTP `3100`, WebSocket `3101`:

```powershell
$env:PORT = '3100'
$env:WS_PORT = '3101'
npm run server:backend
```

`npm run server` mở thêm admin HTML của backend; `npm run server:admin` mở trang của server đã chạy. Admin Next.js tại `/admin` là giao diện riêng, hiện còn dùng dữ liệu giả.

Tạo `.env` từ [.env.example](.env.example), cấu hình PostgreSQL và JWT secrets để dùng tài khoản, workspace và chat bền vững. Áp dụng SQL migrations trước:

```powershell
mvn -pl backend compile exec:java "-Dexec.mainClass=com.landrop.database.DatabaseCli" "-Dexec.args=migrate"
```

Xem [hướng dẫn database](docs/architecture/DATABASE.md). `.env` là cấu hình cá nhân, không đưa vào Git. `prisma/schema.prisma` mô tả schema; Java runtime dùng JDBC/HikariCP và SQL migrations.

## Kiểm tra

```powershell
npm test
node frontend/node_modules/typescript/bin/tsc --project frontend/tsconfig.json --noEmit --incremental false
mvn test "-Dlandrop.database.enabled=false" "-Dtest=*,!PersistentChatTest,!FileManagementTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Regression trên không dùng database. Các suite PostgreSQL cần database dành riêng cho test; một số suite opt-in cần `-Dlandrop.test.postgres=true`. Xem [kế hoạch kiểm thử](docs/TEST_PLAN.md).

## Cấu trúc

```text
backend/                    Java backend, tests, admin HTML, SQL migrations
frontend/app/               Các route Next.js: auth, workspace, admin
frontend/components/        Component giao diện và layout
frontend/lib/               Types, mock data, auth, realtime, transfer, WebRTC
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
