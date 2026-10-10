# Danh mục tài liệu LANDrop

Bắt đầu từ [README gốc](../README.md). Tài liệu được chuẩn hóa và tổ chức theo chức năng kỹ thuật của hệ thống.

## API backend

| Tài liệu | Khi cần dùng |
| --- | --- |
| [AUTH](api/AUTH.md) | Register/login, access/refresh token và phiên |
| [SERVER_AUTH_AND_DEVICES](api/SERVER_AUTH_AND_DEVICES.md) | Identity, thiết bị và control connection của server tích hợp |
| [WORKSPACES](api/WORKSPACES.md) | Workspace, thành viên và phân quyền |
| [REALTIME_CHAT](api/REALTIME_CHAT.md) | WebSocket auth/chat harness, lịch sử và retry |
| [REALTIME_01_CONTRACT](api/REALTIME_01_CONTRACT.md) | Contract duy nhất cho gateway tích hợp và browser realtime chat |
| [REALTIME_02_SIGNALING_CONTRACT](api/REALTIME_02_SIGNALING_CONTRACT.md) | Contract WebRTC signaling, transfer session, authorization và cleanup |
| [P2P_DATA_CHANNEL](api/P2P_DATA_CHANNEL.md) | Chat trực tiếp, xác nhận nhận tin, truyền tệp, backpressure và giới hạn browser |
| [P2P_DATA_CHANNEL_V2](api/P2P_DATA_CHANNEL_V2.md) | Engine chung: IndexedDB/outbox, SHA-256, receipt, checkpoint OPFS và resume |
| [TEAM_CHAT](api/TEAM_CHAT.md) | Kênh, tin nhắn, reply/edit/delete và sự kiện |
| [FILES_AND_TRANSFERS](api/FILES_AND_TRANSFERS.md) | File/thư mục, metadata và transfer coordination |
| [ADMIN_CONTROL](api/ADMIN_CONTROL.md) | Quản trị, client/session, cấu hình và log |

## Kiến trúc và hướng dẫn

| Tài liệu | Phạm vi |
| --- | --- |
| [LAN_CORE](architecture/LAN_CORE.md) | Engine TCP P2P, discovery và swarm |
| [LAN_PROTOCOL](architecture/LAN_PROTOCOL.md) | Framing nhị phân, message, chunk/resume TCP LAN |
| [DATABASE](architecture/DATABASE.md) | JDBC, schema và SQL migration |
| [P2P_UPGRADE_PLAN](architecture/P2P_UPGRADE_PLAN.md) | Kế hoạch LAN: chat ổn định, truyền tệp lớn, checksum và pause/resume |
| [P2P_IMPLEMENTATION_REPORT](architecture/P2P_IMPLEMENTATION_REPORT.md) | Kết quả triển khai năm giai đoạn, bằng chứng browser, test và giới hạn nghiệm thu |
| [P2P_LAN](guides/P2P_LAN.md) | Cài HTTPS/WSS, truyền tệp lớn, khôi phục và chạy kiểm thử browser |
| [RUN_GUIDE](guides/RUN_GUIDE.md) | Hướng dẫn chạy Frontend, Backend, Server & Client (Browser & CLI) |
| [SERVER_DESKTOP](guides/SERVER_DESKTOP.md) | Bảng điều khiển desktop, URL/QR client, thiết bị, signaling và nhật ký |
| [REALTIME_01_LAN_INTEGRATION](guides/REALTIME_01_LAN_INTEGRATION.md) | Đồng bộ Server/AGY, test hai client và checklist hai máy LAN |
| [CLI_DEMO](guides/CLI_DEMO.md) | Demo discovery/peer backend CLI |
| [MULTI_DEVICE](guides/MULTI_DEVICE.md) | Harness chat xác thực nhiều thiết bị |
| [PERFORMANCE](guides/PERFORMANCE.md) | Benchmark TCP loopback lịch sử, không phải tốc độ frontend/WebRTC |
| [TEST_PLAN](TEST_PLAN.md) | Kiểm tra tự động và thủ công hiện tại |


- [Database persistence and recovery](guides/DATABASE_PERSISTENCE.md) ? automatic migrations, authentication transactions, browser enrollment and real PostgreSQL verification.
