# Phân tích backend và tổ chức lại frontend LANDrop

Ngày: 05–06/10/2026. Phạm vi: source hiện tại; chuẩn hóa 20 route frontend và nối những REST API phù hợp. Đây là báo cáo triển khai, không phải xác nhận end-to-end trên PostgreSQL thật.

Đợt tiếp tục ngày 06/10 bổ sung CRUD user/workspace và membership từ admin, đổi tên/di chuyển file và thư mục, xóa thư mục rỗng, tìm kiếm admin trên server và kiểm tra các tình huống refresh token đồng thời. Tách danh sách/editor admin khỏi file tổng hợp để dễ bảo trì.

## Backend: phân chia chức năng

| Nhóm | Source chính | Vai trò với frontend |
| --- | --- |
| Khởi động/control | `backend/src/main/java/com/landrop/server/bootstrap/ServerBootstrap.java`, `server/http/FoundationHttpServer.java` | Gắn các HTTP/WebSocket module; health/status và runtime connections |
| Auth | `auth/server/AuthHttpHandler.java`, `auth/service/AuthService.java` | Register/login/refresh/logout trong server tích hợp; AuthHttpServer là entrypoint riêng |
| Workspace | `workspace/http/WorkspaceHttpServer.java` | Workspace, thành viên, role và tạo/danh sách channel |
| Chat | `chat/server/ChatHttpServer.java`, `chat/service/ChannelChatService.java` | History cursor, idempotent create/reply, edit/delete và broadcast |
| File | `file/server/FileHttpServer.java`, `file/service/` | Folder/file metadata và điều phối lifecycle transfer |
| Admin | `admin/server/AdminHttpServer.java`, `admin/service/` | Chỉ số, users, workspaces, sessions, clients, logs và config |
| Nội dung LAN | `transfer/`, `discovery/`, `protocol/` | TCP chunks/resume/checksum và discovery; không phải transport trình duyệt |
| Database | `database/`, `backend/src/main/resources/db/migration/` | JDBC/HikariCP và SQL migrations; Prisma là bản mô tả schema |

Backend có nhiều contract qua các giai đoạn: workspace/file thường trả `{data: ...}`, chat history có `{items, nextCursor}`, admin nhiều endpoint trả object/array trực tiếp. Frontend đã đọc từng contract tương ứng, không áp chung một envelope.

HTTP và WebSocket mặc định dùng 3000/3001, trùng frontend 3000. Khi chạy chung dùng backend 3100/3101. Auth/workspace/chat/file/admin phụ thuộc database ở các mức khác nhau; standalone in-memory control không cung cấp đầy đủ chức năng cộng tác bền vững.

Đã thêm CORS GET/OPTIONS cho health/status và các alias `/api/health`, `/api/status`, để màn hình connect gọi từ origin Next.js. Các API còn lại giữ middleware xác thực/quyền server.

## UI và API đã triển khai

| Màn hình | API/thao tác |
| --- | --- |
| Connect | `GET /health`, chuẩn hóa origin, hiện lỗi, chọn demo riêng |
| Login/register | `/api/auth/login`, `/register`, `/refresh`, `/logout`; admin `/api/admin/auth/login` |
| Workspace list | `GET/POST /api/workspaces`, phân trang và điều hướng bằng ID trả về |
| Overview | Detail workspace, số dữ liệu đã tải, channels, members và file mới |
| Channels | `GET/POST /api/workspaces/:id/channels` |
| Chat | `GET /api/channels/:id`, history, POST messages/reply, PATCH/DELETE `/api/messages/:id` |
| Files | List/register metadata, list/create folders, PATCH đổi tên/di chuyển, DELETE file và thư mục rỗng, checksum/details |
| Members | List/add bằng email, đổi ADMIN/MEMBER, loại thành viên theo quyền |
| Settings | PATCH workspace, DELETE workspace hoặc POST leave theo vai trò |
| Transfers | GET theo UUID và POST cancel khi là người tham gia; mô phỏng riêng ở demo |
| Admin overview | Dashboard và server info |
| Admin lists/editors | Users/workspaces/clients/sessions/logs; tìm kiếm trên server cho users/workspaces/logs; identity, cập nhật tên/quyền và xóa user, revoke-all, CRUD workspace/membership, khóa/mở user, revoke session, disconnect client |
| Admin config | GET/PUT server config, hiển thị current/pending/requiresRestart |
| Admin transfers | Tổng activeTransfers từ dashboard; nêu rõ chưa có API list global |

Chat đọc 50 tin mới nhất, sắp xếp lại theo thời gian, tải tin cũ bằng cursor và polling 5 giây khi tab hiển thị. Giữ clientMessageId khi retry cùng nội dung để tránh gửi trùng. Request tải dữ liệu được hủy khi đổi workspace/kênh. Quyền JWT decode chỉ dùng để hiển thị UI; backend mới quyết định cho phép thao tác.

UI dùng sidebar navy, accent teal, card/table/form thống nhất, navigation responsive ở 800px/500px, loading/error/empty và retry. Dialog có Escape, focus trap và trả focus. Demo không tự bật sau lỗi HTTP, không trình bày metadata như file đã tải.

## Cấu trúc frontend và điểm sửa trong tương lai

| File/nhóm | Chức năng | Khi cần sửa |
| --- | --- |
| `frontend/app/**/page.tsx` | Wrapper route rất nhỏ | Thêm route hoặc đổi mapping tới screen |
| `components/system/app-shell.tsx` | Auth gate, sidebar/header, breadcrumb và điều hướng | Thêm mục menu, đổi bố cục toàn app |
| `components/system/primitives.tsx` | Action, form field, panel, tag, feedback, dialog | Chuẩn hóa component dùng chung |
| `app/theme.css` | Tokens/style và responsive | Thay màu, khoảng cách, bố cục/breakpoint |
| `components/screens/admin.tsx` | Overview/config/transfers và re-export route list | Sửa chỉ số và cấu hình server |
| `components/screens/admin-lists.tsx`, `admin-editors.tsx` | Resource lists, server search, user identity/CRUD và workspace/member management | Mở rộng filter và workflow quản trị |
| `components/screens/chat.tsx`, `message-actions.tsx` | History/composer/reply/edit/delete | Nối realtime sau khi thống nhất WS auth/event |
| `components/screens/files.tsx`, `metadata-editor.tsx` | Folder tree/search, register/details/delete và rename/move metadata | Bổ sung source availability hoặc transport thật |
| `components/screens/access.tsx` | Connect/login/register/landing | Thay onboarding và validation |
| `components/screens/members.tsx`, `settings.tsx` | Quyền/thành viên và workspace settings | Mở rộng policy workspace |
| `lib/session/session-provider.tsx`, `lib/api/authorized.ts` | Session restore, refresh single flight, logout và server chọn; chặn kết quả phiên cũ | Thay chiến lược token/storage hoặc xử lý trạng thái auth |
| `lib/api/folders.ts`, `admin-models.ts` | Folder paths/cycle checks/PATCH payload và DTO admin | Khi backend đổi contract metadata/admin |
| `lib/api/client.ts`, `models.ts`, `pagination.ts` | HTTP/errors/DTO/pagination | Khi backend đổi contract |
| `lib/data/workspace-provider.tsx`, `use-workspaces.ts` | Workspace-scoped loader, mutations và dữ liệu demo | Cache, pagination và invalidation |
| `lib/mock-data.ts` | Fixture lớn cho demo | Chỉ thay ví dụ demo; không đưa vào live API |
| `lib/transfer/demo-provider.tsx` | Timer mô phỏng rõ nhãn | Demo UI tiến trình; transport thật cần service khác |
| `frontend/package-lock.json` | Khóa dependencies | Cập nhật bằng npm, không chỉnh bằng tay |

Đã bỏ 23 file nguồn không còn import: 8 layout cũ, 11 UI component cũ, fake auth context, realtime client cũ, WebRTC service cũ và transfer context cũ. Thêm một script sinh route tạm đã bị bỏ: tổng 24 file dọn trong đợt UI. Báo cáo dọn repository trước đó là snapshot lịch sử, không phản ánh kích thước route sau khi tách screen.

Các file frontend lớn nhất sau đợt 06/10 (KiB = 1024 bytes):

| File | KiB | Chức năng và điểm bảo trì |
| --- | ---: | --- |
| [theme.css](../../frontend/app/theme.css) | 22,2 | Tokens, responsive layout và styles dùng chung. |
| [mock-data.ts](../../frontend/lib/mock-data.ts) | 22,5 | Fixture chỉ cho demo; không bỏ nếu còn chế độ demo. |
| [admin-editors.tsx](../../frontend/components/screens/admin-editors.tsx) | 13,9 | Quản lý thông tin/quyền/tài khoản và workspace/membership; bước xác nhận thao tác. |
| [admin-lists.tsx](../../frontend/components/screens/admin-lists.tsx) | 13,6 | Bảng admin, tìm kiếm server, paging và thao tác resource. |
| [files.tsx](../../frontend/components/screens/files.tsx) | 12,6 | Kho tài liệu, folder tree, register/details/delete; editor metadata được tách riêng. |
| [admin.tsx](../../frontend/components/screens/admin.tsx) | 11,7 | Overview/config/transfers; danh sách đã tách khỏi file này. |
| [chat.tsx](../../frontend/components/screens/chat.tsx) | 11,2 | History cursor/polling/composer/reply; edit/delete ở message-actions.tsx. |
| [app-shell.tsx](../../frontend/components/system/app-shell.tsx) | 8,4 | Navigation, role gate, header và menu mobile dùng chung. |
| [session-provider.tsx](../../frontend/lib/session/session-provider.tsx) | 6,8 | Session/server state; HTTP auth/retry nằm ở authorized.ts. |

## Giới hạn và việc phát triển tiếp

1. Chưa truyền bytes browser-to-browser hoặc browser-to-TCP. Cần chốt signaling/auth, availability của nguồn, persistence nhận file, backpressure/checksum/resume; không khôi phục simulation vào live.
2. Chat dùng REST polling; chưa nối các harness WebSocket có contract và cách bind auth khác nhau.
3. Presence/thiết bị nguồn chưa xác nhận từ REST member/file DTO; UI hiện rõ chưa xác nhận. Số lượng là dữ liệu thực đã tải.
4. UI đã bổ sung admin CRUD workspace/membership, user identity/update/delete/revoke-all và folder/file rename/move. Các thao tác này vẫn cần acceptance với DB test; admin member/identity API có giới hạn 500 bản ghi, chưa có phân trang cho phần vượt giới hạn. UI chặn xóa thư mục không rỗng; backend chỉ soft-delete thư mục, nên cần kiểm tra cả thay đổi từ client khác.
5. Health chưa phải readiness database. Cần chạy acceptance trên DB test đã migrate và hai tài khoản/device thực.
6. Access token giữ trong memory; refresh trong sessionStorage theo tab. UI không thay backend authorization. Deployment khác origin/HTTPS cần cấu hình origin và transport phù hợp.

## Kiểm tra

- TypeScript strict: đạt sau thay đổi.
- Production Next.js: đạt bằng worker threads; 20 route app và trang not-found được sinh/biên dịch. Chế độ build tạo process con bị sandbox chặn với `spawn EPERM`; cấu hình thread là tùy chọn, không thay thiết lập mặc định khi chạy ngoài môi trường này.
- Frontend API tests: 29 passed, 0 failed/skipped; thêm concurrent/late 401, hủy waiter khi shared refresh, đổi server/logout khi request đang chạy, refresh outage/revocation, folder cycles/missing parent và payload PATCH.
- Launcher: 17 passed, 0 failed/skipped qua `node --test --test-isolation=none scripts/start-server.test.mjs`; `npm test` mặc định bị chặn spawn trong môi trường này.
- Backend `LandropServerFoundationTest` ở đợt 05/10: 17 tests, 0 failure/error/skipped sau kiểm tra CORS/preflight.
- Backend admin ngày 06/10: `AdminControlCenterTest` tổng 11, 7 passed, 4 skipped, 0 failure/error; skips cần PostgreSQL cho lifecycle/credential/route. Chạy với database tắt và `forkCount=0`, log `tmp/backend-admin-tests.log`.
- Production HTTP ngày 06/10: 20/20 route và 37/37 JS/CSS asset trả 200 tại `http://localhost:3006`, HTML dùng `lang="vi"`. Đây là kiểm tra HTTP/SSR/assets, chưa kiểm chứng hydration hoặc thao tác UI. Kết quả `tmp/frontend-http-smoke-2026-10-06.json`.
- Chưa chạy E2E với PostgreSQL thật; không dùng `.env` để thay đổi dữ liệu cá nhân.
- Công cụ trình duyệt không cung cấp browser khả dụng trong phiên này; chưa có kiểm tra trực quan/screenshot. Các breakpoint cần được rà khi mở app.

Chạy backend 3100/3101 và frontend 3000 theo [RUN_GUIDE](../guides/RUN_GUIDE.md). Xem [báo cáo backend chi tiết](LANDrop-BACKEND-REPORT-2026-10-05.md) và [catalog file backend](LANDrop-BACKEND-FILE-CATALOG-2026-10-05.md) để tra các file engine lớn.
