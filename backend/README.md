# LANDrop backend

Backend Java 21 dùng Maven, virtual threads, TCP sockets, HTTP server, Java-WebSocket và PostgreSQL qua JDBC/HikariCP. Chạy lệnh từ thư mục gốc repository.

## Build và kiểm tra

```powershell
mvn -pl backend -am package -DskipTests
mvn -pl backend test "-Dlandrop.database.enabled=false" "-Dtest=*,!PersistentChatTest,!FileManagementTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

JAR nằm tại `backend/target/landrop-0.1.0-SNAPSHOT.jar`, không chứa toàn bộ dependency. Launcher Maven và Dockerfile chuẩn bị classpath phù hợp.

## Entry point

| Class | Công dụng | Cách chạy |
| --- | --- | --- |
| `com.landrop.server.LandropServerMain` | Server tích hợp HTTP, auth, workspace, chat, control WebSocket và admin | `npm run server:backend` |
| `com.landrop.auth.server.AuthHttpServer` | Server auth/chat cho harness realtime | `scripts/demo/start-auth.*` |
| `com.landrop.discovery.DiscoveryServer` | Registry peer TCP trong LAN | `scripts/demo/start-discovery.*` |
| `com.landrop.network.Main` | CLI peer TCP và demo mạng | [CLI demo](../docs/guides/CLI_DEMO.md) |
| `com.landrop.realtime.cli.RealtimeClient` | Đăng nhập, lịch sử chat, refresh/reconnect nhiều thiết bị | [Multi-device](../docs/guides/MULTI_DEVICE.md) |
| `com.landrop.database.DatabaseCli` | Công cụ migration/database | [Database](../docs/architecture/DATABASE.md) |

Hai server realtime phục vụ các đường khởi chạy và test khác nhau; không gộp chúng chỉ vì tên gần giống nhau. Nội dung file LAN nằm ở engine TCP trong `transfer/`, không đi qua server control.

## Nhóm mã

| Nhóm | Trách nhiệm |
| --- | --- |
| `server/`, `admin/` | Bootstrap, health, registry client, control transfer, quản trị và log |
| `auth/`, `device/`, `workspace/` | Tài khoản, phiên, đăng ký thiết bị, workspace và thành viên |
| `chat/`, `file/`, `realtime/` | Chat bền vững, metadata file/thư mục, điều phối transfer và sự kiện |
| `database/` | Cấu hình, pool JDBC, repository, service và migration runner |
| `discovery/`, `network/`, `peer/`, `protocol/`, `security/`, `transfer/` | LAN discovery, TCP, framing, crypto, chunking, resume, swarm |
| `src/main/resources/admin/` | Trang admin HTML do backend phục vụ |
| `src/main/resources/db/migration/` | SQL migrations V1–V7; giữ tên và thứ tự |
| `src/test/java/` | Unit/integration test, PostgreSQL suites và benchmark |

Xem [danh mục API](../docs/README.md) và [báo cáo file lớn](../docs/REPOSITORY_REPORT.md).
