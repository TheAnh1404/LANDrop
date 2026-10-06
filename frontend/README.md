# LANDrop web frontend

Next.js 14 App Router, React 18, TypeScript strict và Tailwind CSS. Frontend chạy độc lập với Maven.

```powershell
npm --prefix frontend ci
npm run dev
```

Mở `http://localhost:3000/connect`, nhập backend `http://localhost:3100`, rồi đăng nhập. Auth/workspace/chat/file API cần PostgreSQL đã migrate. Nút **Khám phá bản demo** mở dữ liệu mẫu riêng; lỗi API không tự chuyển sang demo.

Có thể chép [.env.example](.env.example) thành `frontend/.env.local` để đặt `NEXT_PUBLIC_API_URL`. Giá trị này là địa chỉ công khai, không chứa credentials.

| Thư mục/file | Công dụng |
| --- | --- |
| `app/` | Route mỏng, layout gắn session/workspace và kiểm tra quyền |
| `components/system/` | App shell, responsive navigation, nút/form/modal/bảng và trạng thái chung |
| `components/screens/` | UI và thao tác từng chức năng: access, workspace, chat, files, members, settings, admin, transfers |
| `app/theme.css` | Màu sắc, khoảng cách, bố cục và breakpoint dùng chung |
| `lib/api/` | HTTP JSON, timeout/cancellation, DTO adapters và phân trang |
| `lib/api/authorized.ts` | Refresh token dùng chung cho request đồng thời; chặn kết quả của phiên cũ |
| `lib/api/folders.ts` | Breadcrumb, kiểm tra đích di chuyển và payload PATCH metadata |
| `components/screens/admin-lists.tsx`, `admin-editors.tsx` | Tìm kiếm trên server, CRUD user/workspace và quản lý thành viên |
| `lib/session/` | Login/register/admin login, refresh/logout; access token trong bộ nhớ, refresh token trong sessionStorage |
| `lib/data/` | Tải dữ liệu workspace, phân trang, retry và hủy request khi đổi workspace |
| `lib/transfer/demo-provider.tsx` | Mô phỏng tiến trình chỉ trong demo; không truyền bytes |
| `lib/mock-data.ts` | Fixture dùng riêng trong demo |
| `tests/api.test.mjs` | Kiểm tra HTTP/error/DTO/cancellation/origin/phân trang |

Các component, auth context, WebSocket và WebRTC service cũ không còn được dùng đã bị bỏ. Chat hiện đồng bộ REST mỗi 5 giây, hỗ trợ gửi/reply/sửa/xóa và lịch sử cursor. Metadata file chưa cung cấp khả năng tải nội dung; engine TCP cần cầu nối trình duyệt.

```powershell
npm --prefix frontend test
node frontend/node_modules/typescript/bin/tsc --project frontend/tsconfig.json --noEmit --incremental false
npm run build
npm --prefix frontend run start
```

Dev/production dùng cổng 3000. Có thể kiểm tra build riêng bằng `$env:LANDROP_NEXT_OUTPUT='.next-verify'`; dùng cùng biến khi chạy bản build đó. `node_modules/`, `.next/`, `.next-check/`, `.next-verify/`, `/tmp/` là output sinh lại được.

Nếu môi trường không cho phép tạo process con (`spawn EPERM`), bản Next.js hiện tại có thể build bằng worker threads:

```powershell
$env:LANDROP_NEXT_OUTPUT = '.next-verify'
$env:LANDROP_BUILD_WORKERS = 'threads'
npm run build
```

Đây là cấu hình tùy chọn; build thông thường giữ nguyên các thiết lập Next.js mặc định. Các thao tác đổi tên/di chuyển file và thư mục đã nối PATCH API; xóa thư mục trong UI chỉ cho phép khi thư mục rỗng.

Xem [báo cáo tích hợp](../docs/reports/FRONTEND_BACKEND_INTEGRATION.md) để biết API đã nối và các giới hạn.
