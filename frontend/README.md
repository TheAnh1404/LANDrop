# LANDrop web frontend

Frontend dùng Next.js 14 App Router, React 18, TypeScript và Tailwind CSS. Đây là ứng dụng npm độc lập với Maven.

Từ thư mục gốc:

```powershell
npm --prefix frontend ci
npm run dev
npm run build
npm --prefix frontend run start
```

Dev và production server dùng cổng 3000. Khi chạy cùng backend, đổi cổng backend theo [README gốc](../README.md).

| Thư mục/file | Công dụng |
| --- | --- |
| `app/` | Route kết nối server, đăng nhập/đăng ký, workspace và admin |
| `components/ui/` | Button, modal, input, trạng thái kết nối và component chung |
| `components/layout/` | Sidebar, topbar, gửi/nhận file và transfer HUD |
| `lib/types.ts` | Kiểu dữ liệu phía giao diện |
| `lib/mock-data.ts` | Fixture UI; nhiều trang còn phụ thuộc |
| `lib/auth/` | Auth context, dữ liệu trình duyệt và đăng nhập mô phỏng |
| `lib/realtime/` | WebSocket client, listeners, heartbeat/reconnect và fallback |
| `lib/transfer/`, `lib/webrtc/` | State transfer và DataChannel; còn đường mô phỏng tiến trình |
| `package-lock.json` | Khóa phiên bản dependency cho `npm ci` |

Kiểm tra TypeScript:

```powershell
node frontend/node_modules/typescript/bin/tsc --project frontend/tsconfig.json --noEmit --incremental false
```

`node_modules/` là dependency cục bộ; `.next/` là output/cache sinh lại được. Không đưa vào Git. Giữ `next-env.d.ts` để TypeScript nhận kiểu Next.js.

Giao diện đang chuyển đổi: mock auth/metadata và mô phỏng transfer chưa chứng minh tích hợp API Java hoặc truyền file giữa hai trình duyệt. Hợp đồng để tích hợp nằm trong [danh mục tài liệu](../docs/README.md).
