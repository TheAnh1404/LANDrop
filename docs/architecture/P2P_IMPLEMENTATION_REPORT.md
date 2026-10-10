# Kết quả nâng cấp P2P LANDrop

Ngày 2026-10-10. Phạm vi: LAN, chat trực tiếp 1–1, tệp lớn và resume. Đã triển khai hạng mục phần mềm P1–P5 và cấu hình HTTPS cho P0; nghiệm thu vật lý vẫn cần bài hai máy LAN riêng.

## Năm giai đoạn đã triển khai

| Giai đoạn | Kết quả trong repository | Bằng chứng |
| --- | --- | --- |
| P1 — Kết nối | Engine chung, hai peer nền, idempotent request/cancel, renewable lease, generation cho ICE restart, lọc signal muộn và cleanup | 22 test gateway/socket; browser Next.js ↔ Java hoạt động qua deadline 120 giây |
| P2 — Chat | IndexedDB history/outbox, scope theo account/workspace/device, ACK sau transaction strict, dedup, retry/hủy, identity thiết bị bền qua đóng tab | 100 tin offline/reconnect/ACK bị mất; storage failure không delivered; test account/device isolation |
| P3 — Tệp có xác nhận | Consent từng tệp, framing UUID/offset, hash block và toàn tệp, progress ACK sau checkpoint, COMPLETE receipt, queue/cancel | Tệp rỗng/Unicode/multi-block, dữ liệu hỏng, lỗi ghi, receipt thất lạc/lỗi lưu, tải xuống khớp byte |
| P4 — Tệp lớn/resume | Worker hash riêng worker I/O, OPFS flush/checkpoint, đọc/ghi từng block, pause tại biên block, re-offer và chọn lại nguồn, tải lại tệp đã lưu sau reload | Chrome thực 128 MiB và 1 GiB, reload receiver, resume và truyền hai chiều; offset header >4 GiB được kiểm tra |
| P5 — Giao diện/vận hành | Hàng đợi, tiến trình/tốc độ/ETA, consent/pause/resume/retry, cleanup staging, desktop/mobile, HTTPS/WSS proxy và hướng dẫn recovery | Build Next/Java, bundle freshness, TLS proxy integration, live browser không lỗi JS/gateway và ảnh chụp |

Code không yêu cầu migration mới cho byte tệp/history/checkpoint P2P. Nội dung trực tiếp không đi qua PostgreSQL. Backend tiếp tục dùng database cho tài khoản, session, thiết bị, workspace và quyền.

## Kết quả tự động

Windows, JDK 21.0.12.1, Node 24.19.0, Chrome 154.0.8037.100. Dữ liệu PostgreSQL kiểm thử dùng database/schema riêng; không in credential hay dùng dữ liệu production.

| Kiểm tra | Kết quả |
| --- | --- |
| `npm --prefix frontend test` | 100 passed: 29 API, 34 realtime, 16 WebRTC/manager, 21 contract P2P |
| `npm test` với TLS fixture | 29 passed, không skip; gồm routing HTTPS/API/assets và upgrade WSS giữ Origin |
| Backend regression không DB | 379 tests: 326 passed, 53 skipped, 0 failure/error; loại PersistentChatTest/FileManagementTest theo TEST_PLAN |
| Gateway signaling riêng | 22 passed, bao gồm renew/membership revoke, restart/stale generation và idempotency |
| PostgreSQL riêng | 26 passed, 0 skip: Foundation04AuthenticationDeviceTest, Realtime01PostgresTest, ServerPersistencePostgresTest |
| TypeScript, Next production build | Passed |
| Java package JDK 21 | Passed; đóng gói assets chung |
| `npm run p2p:check`, `git diff --check` | Passed |

Số test là kết quả các suite trên; không cộng thành một con số “coverage” hay coi skip là pass.

## Browser thực

Protocol smoke dùng WebRTC host candidates, IndexedDB và OPFS thật; không giả lập DataChannel. Hai context cô lập lưu trữ để tránh kết quả được chia sẻ qua origin của cùng profile. Live smoke dùng gateway và tài khoản/membership PostgreSQL thực.

| Bài | Kết quả / artifact |
| --- | --- |
| 128 MiB qua HTTPS, sender lấy File trên đĩa | Full SHA-256 khớp, consent trước byte, checkpoint qua receiver reload, resume và tải lại sau reload; [report](../../output/p2p/browser-report-128mib.json) |
| 1 GiB qua HTTPS, hai profile Chrome riêng | Checkpoint 3 MiB tồn tại qua reload, truyền tiếp đúng offset, full hash khớp và tệp OPFS đọc lại được sau reload; [report](../../output/p2p/browser-report-1024mib.json) |
| Truyền hai chiều | 128 MiB / 1 GiB một chiều, 8 MiB chiều ngược khi hash/truyền; hai chiều completed, chat vẫn ACK |
| ACK chat trong bài 128 MiB | p95 83 ms trong lượt cuối; 20 mẫu, một lần chạy loopback; không suy rộng thành SLA LAN |
| ACK chat trong bài 1 GiB | p95 139 ms; 20 mẫu. Resume + kiểm tra nguồn + truyền + verification mất 348.858 giây; không phải throughput wire thuần |
| Next.js ↔ `/client` qua TLS proxy | Chat, consent tệp 380000 byte, COMPLETE và download khớp từng byte; không lỗi JS/gateway |
| Renew qua deadline ban đầu | Giữ phiên 135 giây, gửi thêm tin thành công; [report](../../output/p2p/live-browser-report-135s.json) |
| Live smoke sau hoàn thiện UI | [report](../../output/p2p/live-browser-report.json), [desktop](../../output/p2p/next-desktop.png), [mobile](../../output/p2p/next-mobile.png), [client Java](../../output/p2p/bundled-desktop.png) |

Source hash, final hash và disk flush có chi phí thực; `resumeSeconds` gồm kiểm tra lại nguồn và xếp lịch, không phải tốc độ wire thuần. Ngưỡng buffer ứng dụng không phải số đo RSS toàn trình duyệt. TLS fixture browser bỏ qua lỗi tin cậy riêng trong context test; không thay bài chứng chỉ tin cậy trên hai máy thật.

Bài 1 GiB sử dụng profile Chrome thường riêng, được tạo/dọn trong thư mục test. Bài thử trước với context private không hoàn tất ở kích thước lớn; không coi đó là pass hay đồng nhất quota private với profile thường. Không đo được RSS chuẩn trong phiên này, nên chỉ báo bounds trong code và kết quả byte/hash.

## Giới hạn còn phải nghiệm thu

- Hai máy LAN vật lý, Chrome ↔ Edge, mạng không Internet và chat 30 phút.
- Tệp ≥10 GiB, RAM/RSS/CPU, throughput và ACK p50/p95 qua nhiều lượt, cả Ethernet và Wi-Fi.
- Ngắt Wi-Fi 10/50/90%, browser restart, session/device revoke, storage eviction, hết quota/đĩa và quyền ghi trên máy đích.
- Hai peer pool được contract-test; UI browser live trong phiên này dùng một peer. Chưa benchmark đồng thời hai kết nối dài hạn.
- Browser/OPFS quota khác theo profile và thiết bị; private browsing có thể giới hạn thấp. Không tuyên bố trần protocol 5 TiB là dung lượng browser được đảm bảo.
- Mỗi resume phải chọn lại nguồn đúng hash. Không chạy nền khi tab đóng. Tệp memory ≤64 MiB không còn sau reload.

Hướng dẫn chạy, chứng chỉ, khôi phục và test tái lập: [P2P_LAN](../guides/P2P_LAN.md). Contract thực tế: [P2P_DATA_CHANNEL_V2](../api/P2P_DATA_CHANNEL_V2.md). Không công bố đã nghiệm thu toàn bộ P0–P5 ngoài phạm vi các bằng chứng ở trên.
