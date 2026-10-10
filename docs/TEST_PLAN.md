# Kế hoạch kiểm thử LANDrop

## Browser P2P v2

- `npm --prefix frontend test`: API/realtime/WebRTC và contract engine v2; kiểm tra outbox 100 tin, ACK thất lạc, lưu trữ lỗi, consent/reject, cancel hai đầu, block lỗi, COMPLETE replay, pause ACK, file nguồn thay đổi và offset >4 GiB.
- `npm run p2p:check`: bundle và worker của Next.js/Java phải khớp source.
- `npm run test:p2p:browser`: hai Chrome context cô lập, WebRTC/IndexedDB/OPFS thật, pause/reload/resume, full hash, tải lại tệp sau reload và ACK chat khi truyền. Chọn `LANDROP_TEST_MIB`; mặc định 128 MiB.
- `npm run test:p2p:live`: Next.js ↔ `/client`, gateway/PostgreSQL test thật, HTTPS/WSS, consent, so byte tải xuống, renew vượt TTL ban đầu và screenshot desktop/mobile. Cần backend/database test riêng; cấu hình tại [P2P_LAN](guides/P2P_LAN.md).
- TLS proxy integration trong `npm test` cần `LANDROP_TEST_CERT`/`LANDROP_TEST_KEY`; báo skip khi thiếu fixture.

Kết quả đã chạy tại [P2P_IMPLEMENTATION_REPORT](architecture/P2P_IMPLEMENTATION_REPORT.md). Nghiệm thu hai máy vật lý, Chrome ↔ Edge, 30 phút và 10 GiB phải có số đo riêng; loopback không thay thế bài LAN.

Repository gồm backend Maven và frontend Next.js. Không dùng số test hoặc bảng PASS của giai đoạn JavaFX làm trạng thái nghiệm thu hiện tại.

## Kiểm tra tự động

Chạy từ thư mục gốc:

```powershell
npm test
npm --prefix frontend test
node frontend/node_modules/typescript/bin/tsc --project frontend/tsconfig.json --noEmit --incremental false
npm run build
mvn test "-Dlandrop.database.enabled=false" "-Dtest=*,!PersistentChatTest,!FileManagementTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

| Kiểm tra | Phạm vi và giới hạn |
| --- | --- |
| Node tests | Launcher server, cấu hình, lifecycle, readiness và client đi kèm: auth/signaling, chuyển peer, ICE sớm, tệp thiếu byte/ngắt kết nối |
| Frontend API tests | HTTP/error/cancellation, DTO, refresh đồng thời và đổi phiên, pagination, folder cycles và payload di chuyển metadata |
| TypeScript | Import, kiểu dữ liệu, cú pháp; chưa xác minh API thật |
| Frontend WebRTC tests | Chat/ACK không trùng tin, dọn typing, nhận đủ byte, tệp rỗng, chống gửi đồng thời và mất kênh; transport giả lập |
| Next.js build | Biên dịch route và đóng gói; chưa chứng minh dữ liệu thật |
| Maven regression không DB | LAN, packet/crypto, transfer, auth in-memory, server/control và suite không cần PostgreSQL |

Regression không DB loại `PersistentChatTest` và `FileManagementTest`; một số suite PostgreSQL/live tự bỏ qua khi thiếu cấu hình. Báo cáo test/skips theo Surefire của lần chạy thực tế.

Trong môi trường chặn tạo process con, chạy launcher tests bằng `node scripts/start-server.test.mjs` hoặc (Node hỗ trợ tùy chọn này) `node --test --test-isolation=none scripts/start-server.test.mjs`. Cấu hình build bằng worker threads nằm trong [frontend README](../frontend/README.md). Việc đổi chế độ worker không thay thế kiểm tra UI trong browser hay E2E PostgreSQL.

## PostgreSQL

Chỉ dùng database dành riêng cho test. Backend có thể đọc `DATABASE_URL` từ môi trường hoặc `.env`; một số suite mặc định dùng URL này khi có sẵn.

```powershell
mvn -pl backend test "-Dlandrop.test.postgres=true" "-Dtest=WorkspacePostgresTest,PersistentChatPostgresTest,FileManagementPostgresTest,RealtimePostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Đối chiếu setup/teardown từng suite trước khi chạy. Xem [harness realtime](guides/MULTI_DEVICE.md).

## Kiểm tra thủ công khi hoàn thiện tích hợp

| Nhóm | Tình huống |
| --- | --- |
| Khởi động | Không trùng cổng; DB mới đã migrate; DB lỗi; HTTP/WS port conflict; shutdown |
| Auth/device | Register/login thật, refresh, đổi tài khoản, logout/revoke, nhiều thiết bị |
| Workspace/chat | Quyền thành viên, kênh riêng, paging, retry không trùng tin, reconnect |
| File metadata | Register, rename/move, đích thuộc workspace khác, chống cycle, xóa thư mục rỗng, parent bị xóa bên ngoài, quyền uploader/creator/admin |
| Transfer LAN | File lớn, SHA-256, pause/resume, mất kết nối, failover, restart, ZIP |
| Browser/WebRTC | Hai trình duyệt truyền nội dung thật, signaling, ICE/DataChannel, backpressure, hủy |
| Admin | Phân quyền, tìm kiếm server và paging, CRUD user/workspace, membership, tự hạ quyền/xóa tài khoản, owner, revoke/disconnect, cấu hình và log |

Mock auth/data và thanh tiến trình giả chưa đáp ứng tiêu chí tích hợp. Kết quả đợt dọn cấu trúc nằm trong [repository report](REPOSITORY_REPORT.md).

## Database persistence regression

See [DATABASE_PERSISTENCE.md](guides/DATABASE_PERSISTENCE.md). Run `ServerBootstrapPersistenceTest` and `DatabaseConnectionConfigTest` in the default suite. Run `ServerPersistencePostgresTest` with `landrop.test.postgres=true` and `TEST_DATABASE_URL` targeting a dedicated test database; it verifies HTTP-to-SQL persistence across restarts, atomic registration/refresh/enrollment failures, concurrent refresh and migration history.
