# Báo cáo chuẩn hóa repository và chức năng các file lớn

Ngày rà soát: **05/10/2026**. Phạm vi: cấu trúc working tree tại `C:\TheAnhproject\LANDrop`. Dung lượng dưới đây tính theo KiB/MiB (1024 bytes), làm tròn một chữ số cho file nguồn. Số dòng chỉ dùng để nhận biết quy mô; không phải tiêu chí xóa file.

## 1. Kết quả dọn dẹp

- Loại **30 file lỗi thời/trùng lặp**, tổng dung lượng **158.555 bytes, khoảng 154,8 KiB**: 15 docs, 14 launcher/test/đóng gói JavaFX và một bản Compose trùng.
- Chuyển **13 tài liệu còn hữu ích** vào `docs/api/`, `docs/architecture/`, `docs/guides/`, đặt tên theo chức năng; chuyển 6 launcher backend còn dùng vào `scripts/demo/` và sửa đường dẫn chạy.
- Viết lại README gốc, README backend, demo CLI và kế hoạch test; thêm README frontend, danh mục docs và báo cáo này. Đồng bộ `AGENTS.md` với backend Maven + frontend Next.js.
- Loại cache `frontend/.next/cache/` (**64,73 MiB**) và output Maven `backend/target/` (**2,82 MiB**), tổng **67,55 MiB tại thời điểm dọn**; xóa các file tạm của đợt dọn dẹp. Luồng công việc khác sau đó tái tạo khoảng 2,04 MiB class trong `backend/target/`; phần đang dùng đó được giữ. Giữ production output Next.js vừa build để vẫn có thể chạy `npm --prefix frontend run start`.
- Dockerfile/POM đã bỏ tham chiếu JavaFX không còn tồn tại; root npm không còn lệnh test launcher JavaFX. `.gitignore` bổ sung log, compiler state, test output và `/tmp/`; Docker context loại frontend/dependency/cache vì image hiện chỉ chứa backend.

Việc chuyển frontend sang Next.js đã có trước đợt này. Không phục hồi frontend JavaFX hoặc xóa mã backend còn được entrypoint/test sử dụng. Các thay đổi và bộ báo cáo backend xuất hiện đồng thời từ luồng công việc khác được giữ nguyên.

## 2. Cấu trúc dùng về sau

```text
LANDrop/
  backend/
    src/main/java/com/landrop/    Engine LAN, API, server, auth, database
    src/main/resources/
      admin/index.html           Trang admin HTML độc lập
      db/migration/              SQL migrations V1-V7
    src/test/java/com/landrop/    Tests và benchmark
    pom.xml, README.md
  frontend/
    app/                         Route Next.js
    components/ui/               Component giao diện dùng chung
    components/layout/           Sidebar, topbar, hộp thoại, transfer HUD
    lib/                         Types, mock data, auth, realtime, transfer
    package.json, package-lock.json, cấu hình Next/TS/Tailwind
    README.md
  scripts/
    start-server.mjs              Launcher backend chính
    start-server.test.mjs         Test launcher
    demo/                        Auth/discovery/realtime CLI (.bat/.sh)
  docs/
    api/                         7 hợp đồng REST/WebSocket/backend
    architecture/                TCP LAN core, protocol, database
    guides/                      CLI demo, multi-device, benchmark
    reports/                     Bộ báo cáo backend hiện có ngày 05/10/2026
    README.md, TEST_PLAN.md, REPOSITORY_REPORT.md
  prisma/schema.prisma           Schema tham khảo
  output/pdf/                    Bản PDF báo cáo hiện có
  .mvn/wrapper/, mvnw, mvnw.cmd   Maven Wrapper
  .env.example                   Mẫu cấu hình
  compose.yaml, Dockerfile       Backend + PostgreSQL
  pom.xml, package.json          Điều phối Java và npm
```

`backend/target/` sinh lại bằng Maven; cache `.next/cache/` sinh lại khi chạy Next.js. `node_modules/`, `.next/`, log và `/tmp/` không thuộc mã nguồn cần commit. Cấu hình `.env`, session, `.vscode` và dữ liệu Git được giữ.

## 3. Những file đã bỏ và lý do

Tên trong bảng là đường dẫn **trước khi dọn**. Các file đã commit vẫn có bản gốc trong Git history, ví dụ `git log -- docs/CORE_BASELINE.md` hoặc `git show <commit>:docs/CORE_BASELINE.md`.

| File/nhóm file | Lý do loại bỏ |
| --- | --- |
| `docs/CORE_BASELINE.md`, `CORE_SCOPE.md`, `FINAL_ACCEPTANCE.md` | Mốc freeze/nghiệm thu core cũ, số test và frontend JavaFX không phản ánh checkout hiện tại |
| `docs/CORE-01B-CLIENT-AUDIT.md` | Audit các service/controller JavaFX đã bị thay thế |
| `docs/FOUNDATION-01B-CLIENT-CONTRACT.md`, `FOUNDATION-03B-CLIENT-CONTROL-COMPATIBILITY.md`, `FOUNDATION-04B-CLIENT-AUTH.md`, `PHASE-15B-AUTH.md` | Mô tả implementation Java client không còn trong project; quy tắc server tương ứng vẫn giữ trong API docs |
| `docs/PHASE-14B-API-CONTRACT.md` | Đề xuất API/DTO cho JavaFX chưa đối soát backend; được thay bằng các hợp đồng auth/workspace/chat/file đã triển khai |
| `docs/DEFENSE.md` | Nội dung thuyết trình phần core lặp lại kiến trúc/giao thức, nhiều nhận định bao quát đã cũ |
| `docs/EXPANSION_STATUS.md`, `USER_GUIDE.md` | Hướng dẫn Quick LAN Share, tray, JavaFX và portable desktop không còn đúng frontend hiện tại |
| `docs/PLAN_GROUP_CHAT_LARGE_FILE_SHARING.md`, `FLOW_REVIEW_AND_UPGRADE_PLAN.md`, `PRODUCTION_ROADMAP.md` | Plan/audit gắn với UI JavaFX hoặc baseline cũ; không dùng làm trạng thái hiện tại. Hướng cần kiểm tra lại nằm ở mục 7 và TEST_PLAN |
| `start-peerA.*` đến `start-peerD.*`, `start-peer-standalone.*` | 10 script gọi module frontend/JavaFX đã bị loại khỏi Maven |
| `package-windows.bat`, `scripts/package-windows.ps1` | Đóng gói JAR/JavaFX DesktopLauncher không còn tồn tại |
| `scripts/start-client.mjs`, `scripts/start-client.test.mjs` | Launcher JavaFX không còn được npm sử dụng và test chỉ kiểm tra launcher đó |
| `docker-compose.yml` | Trùng hoàn toàn với `compose.yaml` theo SHA-256; chỉ giữ một cấu hình Compose |

Không đổi tên migration SQL, package Java hoặc các route Next.js; đây là các tên có ý nghĩa runtime. Đặc tả API được giữ và chuyển chỗ; benchmark cũ được ghi rõ là phép đo TCP loopback lịch sử.

## 4. File lớn: mã ứng dụng và công cụ

Bảng bao gồm mã ứng dụng khoảng 20 KiB trở lên và một số file nhỏ hơn nhưng quan trọng. Các file này có chức năng thực tế, cần giữ.

| File | KiB / dòng | Chức năng | Khi phát triển tiếp |
| --- | ---: | --- | --- |
| [FileTransferService.java](../backend/src/main/java/com/landrop/transfer/FileTransferService.java) | 76,1 / 1.799 | Engine offer/accept, gửi/nhận chunk, resume/retry, metadata file tạm, tiến trình, xác minh và cleanup | Bắt đầu tại đây khi sửa truyền TCP, pause/resume hoặc file lớn; đối chiếu tests transfer. Có thể tách nhận/gửi/resume thành thành phần riêng khi thay đổi hành vi |
| [admin/index.html](../backend/src/main/resources/admin/index.html) | 51,9 / 1.178 | HTML/CSS/JavaScript của admin do Java server phục vụ | Sửa bảng quản trị backend tại đây; độc lập với `frontend/app/admin/` |
| [FoundationRealtimeServer.java](../backend/src/main/java/com/landrop/server/realtime/FoundationRealtimeServer.java) | 41,4 / 820 | Control WebSocket của server tích hợp: hello/welcome, identity/device binding, heartbeat, chat và transfer events | Tích hợp Next.js phải dùng đúng handshake/envelope của server này |
| [AdminHttpServer.java](../backend/src/main/java/com/landrop/admin/server/AdminHttpServer.java) | 31,4 / 711 | Các route admin, kiểm tra quyền, parse request và response cho quản trị | Thêm API admin hoặc giới hạn request tại đây; nghiệp vụ ở service |
| [AdminControlCenterService.java](../backend/src/main/java/com/landrop/admin/service/AdminControlCenterService.java) | 30,1 / 595 | Nghiệp vụ quản trị user/workspace/client/session, trạng thái và thao tác vận hành | Kết nối dashboard thật hoặc thay đổi chính sách quản trị |
| [ChannelChatService.java](../backend/src/main/java/com/landrop/chat/service/ChannelChatService.java) | 30,1 / 691 | Quyền kênh, tạo/lịch sử tin, idempotency, reply/edit/delete và publish sự kiện | Hoàn thiện chat nhóm, retry/dedup và phân quyền |
| [PeerConnection.java](../backend/src/main/java/com/landrop/peer/PeerConnection.java) | 25,0 / 687 | Vòng đọc TCP, dispatch packet, HELLO, ping/pong, key exchange, send và đóng kết nối | Sửa framing, handshake hoặc mã hóa cùng `protocol/` và `security/`; không áp dụng trực tiếp cho trình duyệt |
| [FileHttpServer.java](../backend/src/main/java/com/landrop/file/server/FileHttpServer.java) | 24,6 / 498 | REST file/thư mục/transfer metadata, routing và quyền | Tích hợp file manager với dữ liệu thật; kiểm tra body limit và trạng thái transfer |
| [RealtimeServer.java](../backend/src/main/java/com/landrop/realtime/RealtimeServer.java) | 23,1 / 410 | WebSocket của đường AuthHttpServer/harness: xác thực, đăng ký kết nối, history/message và broadcast | Dùng cho CLI đa thiết bị và tests realtime; khác server Foundation bên trên |
| [mock-data.ts](../frontend/lib/mock-data.ts) | 22,5 / 876 | Fixture users, workspaces, channels, messages, files, transfers và admin | Giữ cho UI/demo hiện tại; thay từng domain bằng API thật trước khi bỏ |
| [Trang chat kênh](<../frontend/app/w/[workspaceId]/chat/[channelId]/page.tsx>) | 22,0 / 529 | Timeline chat, composer, lựa chọn file và các thao tác giao diện | Tách UI và đồng bộ history/events/attachment metadata khi tích hợp backend |
| [start-server.mjs](../scripts/start-server.mjs) | 21,2 / 669 | Đọc cấu hình, chọn cách chạy Java, kiểm tra cổng/health/admin, quản lý tiến trình và mở trang admin | Giữ làm entrypoint `npm run server*`; kiểm tra launcher tests khi sửa |
| [Trang file manager](<../frontend/app/w/[workspaceId]/files/page.tsx>) | 21,0 / 544 | Duyệt/tìm file và thư mục, thao tác UI và khởi tạo download | Kết nối API file/folder thật, phản ánh nguồn online/offline |
| [AuthHttpServer.java](../backend/src/main/java/com/landrop/auth/server/AuthHttpServer.java) | 20,0 / 451 | Đường chạy auth HTTP và realtime harness, có bootstrap repository/service riêng | Giữ cho demo/tests; xác định chính xác entrypoint khi gộp logic server trong tương lai |
| [ServerBootstrap.java](../backend/src/main/java/com/landrop/server/bootstrap/ServerBootstrap.java) | 20,0 / 510 | Khởi tạo database/auth/device/workspace/chat/file/admin, HTTP/WS và shutdown | Sửa readiness, migration-before-start hoặc rollback khi startup lỗi |

Tên giống nhau không đồng nghĩa là thừa: hai realtime server, hai giao diện admin và hai cơ chế TCP/WebRTC phục vụ các đường sử dụng khác nhau. Không xóa trước khi kiểm tra toàn bộ caller và test.

## 5. File lớn: tests, dependency và tài liệu

Các tests lớn nằm dưới `backend/src/test/java/com/landrop/`. Giữ vì kiểm tra nhiều failure case, không phải output sinh ra:

| File (tương đối từ thư mục tests) | KiB | Chức năng |
| --- | ---: | --- |
| `admin/AdminControlCenterTest.java` | 33,8 | Quản trị, lifecycle, routes và quyền; một số case cần DB |
| `file/FileManagementTest.java` | 32,4 | File/folder metadata và transfer rules; setup hiện cần DB, loại khỏi regression không DB |
| `chat/PersistentChatTest.java` | 28,9 | Chat bền vững, quyền, reply/edit/delete/idempotency; loại khỏi regression không DB |
| `file/FileManagementPostgresTest.java` | 28,1 | File metadata, folder và transfer trên PostgreSQL thật |
| `auth/Foundation04AuthenticationDeviceTest.java` | 25,0 | Identity, session, enrollment và binding thiết bị |
| `transfer/MultiPeerDownloadIntegrationTest.java` | 23,4 | Swarm download nhiều peer, chia chunk và failover |
| `chat/PersistentChatPostgresTest.java` | 22,9 | Persistence, quyền và lịch sử chat qua JDBC/PostgreSQL |
| `server/LandropServerFoundationTest.java` | 21,9 | Bootstrap, HTTP/control WS, heartbeat và transfer coordination |
| `transfer/FileTransferResumeIntegrationTest.java` | 21,9 | Tiếp tục truyền sau gián đoạn, metadata partial và integrity |
| `transfer/Phase91HardeningIntegrationTest.java` | 20,5 | Các trường hợp hardening/failover của engine transfer |
| `auth/AuthIntegrationTest.java` | 20,1 | Đăng ký/đăng nhập, refresh, logout và xác thực |

| File/thư mục | Dung lượng | Chức năng và cách giữ |
| --- | ---: | --- |
| [frontend/package-lock.json](../frontend/package-lock.json) | 54,8 KiB | Khóa dependency npm; giữ trong Git, cài bằng `npm --prefix frontend ci` |
| `.mvn/wrapper/maven-wrapper.jar` | 49,5 KiB | Bootstrap Maven Wrapper; giữ cùng wrapper scripts/properties |
| `frontend/node_modules/@next/swc-win32-x64-msvc/next-swc.win32-x64-msvc.node` | 129,57 MiB | Compiler SWC native của Next.js trên Windows; là dependency được cài, không phải file nghiệp vụ |
| `frontend/node_modules/typescript/lib/typescript.js` | 8,69 MiB | Compiler TypeScript; không chỉnh sửa thủ công |
| `frontend/node_modules/` | 282,48 MiB | Toàn bộ dependency phát triển; giữ để chạy/build ngay, không commit. Có thể bỏ toàn thư mục rồi cài lại bằng `npm ci` khi cần dung lượng |
| `.git/` | Khoảng 1,45 MiB lúc đo | Commit/object/index và lịch sử phục hồi; giữ nguyên |
| `frontend/.next/cache/` | 64,73 MiB trước dọn | Cache webpack/TypeScript; đã xóa, tự sinh lại |
| `backend/target/` | 2,82 MiB trước dọn | Class, tài nguyên copy và Surefire reports; đã xóa sau tổng hợp. Luồng khác tái tạo classes/test-classes trong khi hoàn tất báo cáo, nên output hiện tại được giữ |

Bộ báo cáo backend mới tạo cùng ngày được giữ để tra cứu: [phân tích backend](reports/LANDrop-BACKEND-REPORT-2026-10-05.md) khoảng **55,2 KiB**; [catalog từng file](reports/LANDrop-BACKEND-FILE-CATALOG-2026-10-05.md) **39,2 KiB**; [inventory CSV](reports/LANDrop-BACKEND-FILE-INVENTORY-2026-10-05.csv) **45,4 KiB**; [log test](reports/LANDrop-BACKEND-TEST-LOG-2026-10-05.txt) **214,8 KiB** và [kết quả CSV](reports/LANDrop-BACKEND-TEST-RESULTS-2026-10-05.csv) **6,8 KiB**. [PDF tương ứng](../output/pdf/LANDrop-BACKEND-REPORT-2026-10-05.pdf) khoảng **186,3 KiB**. Đây là bằng chứng của luồng báo cáo riêng, không thay cho kết quả kiểm tra tại mục 6.

## 6. Kiểm tra trong đợt chuẩn hóa

| Lệnh/kiểm tra | Kết quả |
| --- | --- |
| `npm test` | **17/17 passed**, 0 skipped |
| TypeScript `--noEmit --incremental false` | Passed |
| `npm run build` | Passed, biên dịch và sinh các route Next.js thành công |
| `mvn clean test "-Dlandrop.database.enabled=false" "-Dtest=*,!PersistentChatTest,!FileManagementTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | **BUILD SUCCESS**: 274 tests được báo cáo, 0 failures/errors, **28 skipped**; 246 tests đã chạy |
| Đường dẫn tài liệu, npm script, demo script, Docker COPY và POM | **330 liên kết Markdown nội bộ hợp lệ**; script/module và đường dẫn chạy của 6 demo hợp lệ |
| `git diff --check` | Không có lỗi whitespace |

28 skips của Maven: `AdminControlCenterTest` 4; `PersistentChatPostgresTest` 9; `FileManagementPostgresTest` 9; `RealtimePostgresTest` 3; `WorkspacePostgresTest` 3. Không chạy database thật, migration, server/demo tương tác hoặc Docker build trong đợt này. Maven dùng JDK **26.0.2.1** với compiler target/release **21**; chưa xác minh runtime JDK 21 riêng.

Node/Next.js và Surefire ban đầu bị sandbox hạn chế tạo process/file tạm; các kiểm tra cuối ở trên đã chạy lại ngoài sandbox. Output/cache có thể tái tạo đã được dọn sau kiểm tra.

## 7. Dùng những file này cho lần phát triển tới

1. **Nối frontend với API thật:** bắt đầu từ auth context, các trang chat/files/admin, và 7 hợp đồng trong `docs/api/`. Thay mock theo từng chức năng; thực hiện refresh/logout và quyền server trước khi coi UI là dữ liệu thật.
2. **Chốt transport trình duyệt:** `frontend/lib/webrtc/webrtc-transfer-service.ts` có chunk/backpressure và đường mô phỏng, nhưng chưa chứng minh transfer thật giữa hai peer. WebRTC không đọc được protocol TCP Java chỉ nhờ dùng cùng metadata. Chốt signaling, authorization, source availability và receive/persist trước khi nghiệm thu.
3. **Củng cố transfer Java:** dùng `FileTransferService`, `PeerConnection`, `MultiPeerDownloadCoordinator`, `P2pCryptoSession` và các tests transfer. Mã crypto đã tồn tại; không suy ra trạng thái bảo mật từ các tài liệu plaintext cũ. Kiểm tra policy và xác thực peer trước khi công bố mức bảo vệ.
4. **Củng cố server:** dùng `ServerBootstrap`, hai đường realtime và service file/chat để kiểm tra readiness khi DB lỗi, migration startup, contract event và nguồn trạng thái transfer.
5. **Duy trì docs:** hướng dẫn thao tác nằm trong README/guides; chi tiết contract nằm trong api; không tạo thêm bảng nghiệm thu tĩnh thay cho kết quả test có ngày/phạm vi. Khi xóa một implementation, cập nhật caller, script, Docker và tài liệu liên quan trong cùng thay đổi.

Các điểm trên là hướng tiếp tục từ checkout hiện tại, không phải xác nhận mọi lỗi trong các audit cũ vẫn còn. Dùng [TEST_PLAN](TEST_PLAN.md) để kiểm chứng trước khi thay đổi hành vi.
