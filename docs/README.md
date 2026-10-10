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
| [TEAM_CHAT](api/TEAM_CHAT.md) | Kênh, tin nhắn, reply/edit/delete và sự kiện |
| [FILES_AND_TRANSFERS](api/FILES_AND_TRANSFERS.md) | File/thư mục, metadata và transfer coordination |
| [ADMIN_CONTROL](api/ADMIN_CONTROL.md) | Quản trị, client/session, cấu hình và log |

## Kiến trúc và hướng dẫn

| Tài liệu | Phạm vi |
| --- | --- |
| [LAN_CORE](architecture/LAN_CORE.md) | Engine TCP P2P, discovery và swarm |
| [LAN_PROTOCOL](architecture/LAN_PROTOCOL.md) | Framing nhị phân, message, chunk/resume TCP LAN |
| [DATABASE](architecture/DATABASE.md) | JDBC, schema và SQL migration |
| [RUN_GUIDE](guides/RUN_GUIDE.md) | Hướng dẫn chạy Frontend, Backend, Server & Client (Browser & CLI) |
| [SERVER_DESKTOP](guides/SERVER_DESKTOP.md) | Bảng điều khiển desktop, URL/QR client, thiết bị, signaling và nhật ký |
| [REALTIME_01_LAN_INTEGRATION](guides/REALTIME_01_LAN_INTEGRATION.md) | Đồng bộ Server/AGY, test hai client và checklist hai máy LAN |
| [CLI_DEMO](guides/CLI_DEMO.md) | Demo discovery/peer backend CLI |
| [MULTI_DEVICE](guides/MULTI_DEVICE.md) | Harness chat xác thực nhiều thiết bị |
| [PERFORMANCE](guides/PERFORMANCE.md) | Benchmark TCP loopback lịch sử, không phải tốc độ frontend/WebRTC |
| [TEST_PLAN](TEST_PLAN.md) | Kiểm tra tự động và thủ công hiện tại |

