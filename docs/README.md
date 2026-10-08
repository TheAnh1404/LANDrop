# Danh mục tài liệu LANDrop

Bắt đầu từ [README gốc](../README.md) và [báo cáo cấu trúc/file lớn](REPOSITORY_REPORT.md). Tài liệu chia theo chức năng; số phase được bỏ khỏi tên file để dễ tìm, nội dung đặc tả backend được giữ.

## Tích hợp frontend hiện tại

[Báo cáo frontend/backend](reports/FRONTEND_BACKEND_INTEGRATION.md) mô tả cấu trúc UI mới, API đã nối, giới hạn transport và chức năng các file quan trọng.

## API backend

| Tài liệu | Khi cần dùng |
| --- | --- |
| [AUTH](api/AUTH.md) | Register/login, access/refresh token và phiên |
| [SERVER_AUTH_AND_DEVICES](api/SERVER_AUTH_AND_DEVICES.md) | Identity, thiết bị và control connection của server tích hợp |
| [WORKSPACES](api/WORKSPACES.md) | Workspace, thành viên và phân quyền |
| [REALTIME_CHAT](api/REALTIME_CHAT.md) | WebSocket auth/chat harness, lịch sử và retry |
| [REALTIME_01_CONTRACT](api/REALTIME_01_CONTRACT.md) | Contract duy nhất cho gateway tích hợp và browser realtime chat |
| [TEAM_CHAT](api/TEAM_CHAT.md) | Kênh, tin nhắn, reply/edit/delete và sự kiện |
| [FILES_AND_TRANSFERS](api/FILES_AND_TRANSFERS.md) | File/thư mục, metadata và transfer coordination |
| [ADMIN_CONTROL](api/ADMIN_CONTROL.md) | Quản trị, client/session, cấu hình và log |

Các đặc tả được viết qua nhiều giai đoạn. Khi tích hợp client mới, đối chiếu đúng entrypoint và handler Java; không giả định mọi envelope/event thuộc cùng một server. Chúng chưa chứng nhận Next.js đã hỗ trợ các API này.

## Kiến trúc và hướng dẫn

| Tài liệu | Phạm vi |
| --- | --- |
| [LAN_CORE](architecture/LAN_CORE.md) | Engine TCP P2P, discovery và swarm |
| [LAN_PROTOCOL](architecture/LAN_PROTOCOL.md) | Framing nhị phân, message, chunk/resume TCP LAN |
| [DATABASE](architecture/DATABASE.md) | JDBC, schema và SQL migration |
| [RUN_GUIDE](guides/RUN_GUIDE.md) | Hướng dẫn chạy Frontend, Backend, Server & Client (Browser & CLI) |
| [REALTIME_01_LAN_INTEGRATION](guides/REALTIME_01_LAN_INTEGRATION.md) | Đồng bộ Server/AGY, test hai client và checklist hai máy LAN |
| [CLI_DEMO](guides/CLI_DEMO.md) | Demo discovery/peer backend CLI |
| [MULTI_DEVICE](guides/MULTI_DEVICE.md) | Harness chat xác thực nhiều thiết bị |
| [PERFORMANCE](guides/PERFORMANCE.md) | Benchmark TCP loopback lịch sử, không phải tốc độ frontend/WebRTC |
| [TEST_PLAN](TEST_PLAN.md) | Kiểm tra tự động và thủ công hiện tại |

Báo cáo phase, nghiệm thu và hướng dẫn desktop đã lỗi thời được loại bỏ. Danh sách và lý do nằm trong [REPOSITORY_REPORT](REPOSITORY_REPORT.md); bản gốc đã commit có thể tra bằng Git history.

## Báo cáo backend hiện có

Bộ báo cáo ngày 05/10/2026 được tạo trong một luồng công việc khác khi đang dọn repository, nên được giữ nguyên:

- [Phân tích backend](reports/LANDrop-BACKEND-REPORT-2026-10-05.md)
- [Danh mục chức năng từng file backend](reports/LANDrop-BACKEND-FILE-CATALOG-2026-10-05.md)
- [Inventory CSV](reports/LANDrop-BACKEND-FILE-INVENTORY-2026-10-05.csv)
- [Kết quả test CSV](reports/LANDrop-BACKEND-TEST-RESULTS-2026-10-05.csv) và [log tương ứng](reports/LANDrop-BACKEND-TEST-LOG-2026-10-05.txt)
- [Bản PDF](../output/pdf/LANDrop-BACKEND-REPORT-2026-10-05.pdf)
