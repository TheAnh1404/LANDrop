# Kế hoạch nâng cấp P2P chat và chuyển tệp trong LAN

Ngày: 2026-10-10. Trạng thái: đã triển khai các hạng mục P1–P5; xem [báo cáo triển khai và kiểm chứng](P2P_IMPLEMENTATION_REPORT.md). Nội dung đề xuất ban đầu bên dưới được giữ để đối chiếu; tên message/giới hạn thực tế tại [contract v2](../api/P2P_DATA_CHANNEL_V2.md). Nghiệm thu hai máy LAN, 30 phút và 10 GiB còn chờ đo thực tế.
Phạm vi người dùng chọn: **LAN trước — ổn định, tệp lớn, resume**.

## Kết quả cần đạt

- Chat trực tiếp 1–1 ổn định khi reconnect, không trùng tin, lịch sử và hàng đợi tồn tại sau tải lại.
- Gửi nhiều tệp theo hàng đợi; nhận tệp có lựa chọn nơi lưu, tiến trình thực, pause/resume và hủy từng tệp.
- Bên gửi chỉ hiển thị hoàn tất sau khi bên nhận xác minh nội dung và lưu thành công.
- Truyền tệp lớn với bộ đệm ứng dụng có giới hạn; không giữ toàn bộ tệp trong RAM.
- Next.js và client `/client` dùng cùng engine/giao thức, có kiểm tra tương thích phiên bản.
- Đổi cuộc trò chuyện không hủy công việc đang chạy với peer khác. Peer offline có thể nhận tin đang chờ khi cả hai online lại; chưa có giao nhận qua server lúc bên gửi offline.

Ưu tiên Chrome/Edge desktop cho nghiệm thu tệp lớn và resume. Trình duyệt khác dùng khả năng được phát hiện thực tế và chế độ giới hạn rõ ràng. Chat nhóm, đồng bộ lịch sử qua server, TURN/Internet, swarm và ZIP thư mục để sau milestone LAN.

## Cơ sở hiện tại

| Hiện trạng trong repo | Hệ quả cần xử lý |
| --- | --- |
| `TransferCoordinator` hết hạn signaling cố định sau 120 giây, kể cả `P2P_CONNECTED` | Client có thể phải đóng một phiên chat/truyền tệp còn hoạt động |
| Một `RTCDataChannel` ordered cho chat và binary; binary chưa mang ID/offset | Chỉ một lượt gửi tệp, khó phân luồng và resume |
| `sendFile` đánh dấu completed sau khi gửi `FILE_END` | Chưa chứng minh peer nhận, xác minh và lưu tệp |
| Bên nhận gom các chunk thành Blob trong RAM | Tệp lớn tăng bộ nhớ theo kích thước tệp |
| Next.js giữ chat trong bộ nhớ; `/client` lưu theo connectionId | Mất hoặc phân mảnh lịch sử khi reload/reconnect |
| UI Next.js có một pending message/file và đóng kết nối khi đổi peer | Có thể ghi đè công việc chờ và ngắt tác vụ đang chạy |
| Signaling gắn với connection/session hiện tại, SDP offer/answer chỉ một lần | ICE restart, refresh và reconnect cần nâng contract trước khi triển khai |
| Client HTML và TypeScript có hai bản engine | Dễ lệch hành vi; cần cùng bộ kiểm thử tương thích |

Tham chiếu hiện tại: [P2P_DATA_CHANNEL](../api/P2P_DATA_CHANNEL.md), [REALTIME-02](../api/REALTIME_02_SIGNALING_CONTRACT.md).

## Kiến trúc đề xuất

Giữ gateway Java cho identity, presence, kiểm tra workspace và signaling. Chat trực tiếp và byte tệp đi qua WebRTC. PostgreSQL chỉ lưu metadata/lifecycle khi có yêu cầu tích hợp; local chat và checkpoint ở thiết bị. Tách rõ chat kênh hiện có trên server với chat trực tiếp.

Engine TypeScript dùng chung gồm `PeerSessionManager`, `ChatService`, `TransferService`, `LocalStore`, `FileSource` và `FileSink`; tên module là đề xuất. Next.js dùng adapter React, `/client` dùng adapter DOM và bundle được tạo từ cùng source trong quy trình build. Triển khai dần qua facade, không thay hai UI cùng lúc. Build/test phải phát hiện bundle cũ.

Conversation key dùng server/workspace/local user/remote user; mục tiêu gửi cụ thể gắn với deviceId ổn định. Không dùng connectionId làm khóa lịch sử hoặc checkpoint. ConnectionId chỉ là địa chỉ định tuyến phiên hiện tại. Mỗi cuộc trò chuyện hiển thị rõ thiết bị đích; chưa tự nhân bản tin tới mọi thiết bị của tài khoản.

Peer manager tách việc chọn cuộc trò chuyện khỏi vòng đời transport. Khởi đầu giới hạn hai peer đang hoạt động, hàng đợi tệp tuần tự cho mỗi peer; đo tài nguyên trước khi tăng số lượng. Hủy tệp không đóng phiên chat.

Đề xuất hai kênh reliable ordered: `control/chat` và `file-data`. Hai kênh cùng chia sẻ SCTP nên vẫn cần scheduler ưu tiên control/chat và ngân sách buffer tổng. Tách kênh không tự bảo đảm độ trễ thấp. Chunk khởi đầu 16 KiB, điều chỉnh theo `maxMessageSize` đã thương lượng và số đo thực tế.

## Thứ tự triển khai và tiêu chí kết thúc

| Giai đoạn | Công việc chính | Tiêu chí nghiệm thu |
| --- | --- | --- |
| P0 — Kiểm chứng nền tảng | Baseline hai máy LAN; kiểm tra quyền ghi/lưu/checkpoint; chọn HTTPS/WSS qua một origin được tin cậy; phát hiện khả năng browser | Có cấu hình LAN tái lập được và thử nghiệm chứng minh cách lưu checkpoint thực sự tồn tại sau reload; ghi lại RAM, tốc độ, độ trễ chat |
| P1 — Phiên kết nối ổn định | Engine chung; timeout negotiation riêng; lease phiên đã kết nối có gia hạn và tái kiểm tra quyền; correlation/idempotency; generation cho SDP/ICE; reconnect; cleanup/logout | Chat liên tục ≥30 phút; cancel trùng không gây lỗi UI; không nhận signal cũ; refresh/reconnect khôi phục đúng thiết bị, không vượt quyền |
| P2 — Chat đáng tin cậy | IndexedDB cho history/outbox; ID ổn định; ACK sau lưu; retry có giới hạn; dedup qua reconnect/reload; trạng thái gửi; chuyển peer giữ tác vụ | Gửi 100 tin khi ngắt kết nối rồi nối lại: không mất tin đã được ACK, không trùng bubble; reload giữ lịch sử và outbox đúng tài khoản/peer |
| P3 — Chuyển tệp được xác nhận | Thương lượng v2; người nhận Accept/Reject; header binary; ACK tiến độ; checksum; completion handshake; queue và cancel từng tệp | Tệp thiếu byte/sai hash không completed; ACK hoàn tất thất lạc được xử lý idempotent; tệp rỗng và batch nhiều tệp đúng dữ liệu |
| P4 — Tệp lớn, pause/resume | Worker cho hash/I/O; streaming sink; checkpoint; đối soát offset/hash; resume qua kết nối mới rồi qua reload; kiểm tra quota/quyền | Truyền tệp ≥10 GiB trên Chrome/Edge với buffer ứng dụng bị chặn ở ngân sách đã đặt; resume sau ngắt mạng/reload, hash cuối khớp |
| P5 — Hoàn thiện và phát hành | UI hàng đợi/tốc độ/ETA; retry và lỗi rõ ràng; đo chat khi truyền tệp; test hai máy/browser thật; tài liệu HTTPS và recovery | Đạt ma trận E2E, có kết quả hiệu năng tái lập, giới hạn browser công khai, hai client tương thích và build sạch |

Thứ tự phụ thuộc: P0 → P1 → P2 → P3 → P4 → P5. Thực hiện từng milestone có thể nghiệm thu riêng; không gộp thành một thay đổi lớn.

### P0: quyết định lưu trữ trước khi hứa resume

URL HTTP qua IP LAN không đủ cho một số API tệp cần secure context. Cấu hình HTTPS/WSS và chứng chỉ được browser tin cậy là đầu việc triển khai. Khi kết nối frontend tới backend, tránh mixed content và giữ Origin allowlist đúng.

Thử nghiệm hai sink: File System Access cho người dùng chọn nơi lưu và OPFS cho dữ liệu tạm/checkpoint. OPFS chịu quota/eviction; cần kiểm tra dung lượng và dọn dữ liệu. File System Access thường chỉ commit thay đổi khi đóng stream; không coi `write()` đã resolve là checkpoint an toàn qua crash. Chọn cách đóng/commit theo block hoặc staging OPFS sau số đo về tốc độ, dung lượng và tính bền vững.

Nếu không có disk sink phù hợp, giữ chế độ tệp nhỏ với giới hạn cấu hình và hiển thị rằng resume qua reload chưa hỗ trợ. Không tự rơi về gom tệp lớn trong Blob.

### P1: sửa contract vòng đời trước

Giữ deadline ngắn cho yêu cầu/negotiation; phiên đang hoạt động dùng lease có thời hạn và gia hạn, không chỉ tăng TTL toàn hệ thống. Mỗi renew kiểm tra user/session/device/workspace; mất quyền thì các client tuân thủ dừng trong giới hạn lease. Server không thể cưỡng chế đóng DataChannel của client bị sửa đổi, nên không hứa thu hồi dữ liệu P2P đã nhận.

Bổ sung contract có phiên bản cho renew, reconnect và ICE restart. Dùng negotiation generation để cô lập SDP/ICE cũ, xử lý end-of-candidates và offer đồng thời. Backend hiện chỉ cho offer/answer một lần: chưa gọi `restartIce()` trước khi mở rộng và kiểm thử state machine.

Refresh hợp lệ cần re-auth và thiết lập/rebind phiên theo contract mới; logout/revoke thực sự phải dừng retry. Khi server restart, lập phiên signaling mới và xác minh quyền lại; không replay SDP/ICE cũ. Retry có backoff, jitter và giới hạn; gửi yêu cầu lặp cùng ID trả cùng kết quả thay vì tạo nhiều phiên.

### P2: ý nghĩa trạng thái chat

Trạng thái: `queued → sending → delivered`, kèm `failed/cancelled`. Delivered nghĩa peer đã lưu tin vào local store và ACK; không phải đã đọc. Read receipt, edit/delete và reactions triển khai sau nền tảng này.

Peer gửi lại tin chưa ACK bằng cùng messageId. Receiver commit tin và dedup key trước khi ACK. Nếu hết quota/lỗi store, không ACK thành công. Hàng đợi có giới hạn, có thao tác retry/hủy; UI không để người dùng hiểu rằng tin được server giữ hộ.

History/outbox phân vùng theo tài khoản và origin; đổi tài khoản không đọc hoặc gửi công việc của tài khoản trước. Logout chặn worker/retry và yêu cầu người dùng chọn giữ hay xóa lịch sử cục bộ. Danh tính hiển thị lấy từ phiên đã xác thực, không tin `senderName` tự khai trong packet.

### P3: contract dữ liệu v2

Giữ gateway envelope hiện tại; version/capability negotiation riêng cho DataChannel. Peer v1 chỉ dùng tính năng v1 và không được hiển thị đảm bảo checksum/resume/v2. Unsupported version dừng rõ ràng, không đoán định dạng packet.

| Thông điệp đề xuất | Vai trò |
| --- | --- |
| `P2P_HELLO` / `P2P_CAPABILITIES` | Version, giới hạn message/buffer, sink, hash/resume |
| `FILE_OFFER` / `FILE_ACCEPT` / `FILE_REJECT` | Người nhận chọn cách lưu và đồng ý trước khi nhận byte |
| Binary frame v2 | Header: version, transfer UUID, offset, payload length; kiểm tra bounds trước ghi |
| `FILE_PROGRESS_ACK` | Offset/range đã nhận; phân biệt byte đã ghi tạm và checkpoint đã commit |
| `FILE_FINISH` / `FILE_COMPLETE_ACK` | Sender kết thúc kèm digest; receiver xác minh và commit sink rồi ACK |
| `FILE_PAUSE` / `FILE_RESUME_REQUEST` / `FILE_RESUME_ACCEPT` | Dừng gửi và đối soát checkpoint sau tái xác thực |
| `FILE_CANCEL` / `FILE_ERROR` | Hủy/lỗi riêng của tệp, có reason và cleanup idempotent |

Signaling session ID, logical file transfer ID và database transfer ID là ba định danh khác nhau. Transfer ID của ứng dụng giữ nguyên khi resume; signaling ID thay đổi khi lập kết nối mới. Checkpoint ràng buộc transfer với server/workspace/user/device và file identity, không chỉ tên/kích thước.

Checksum đề xuất SHA-256 theo block và toàn tệp, tính tăng dần trong worker. Chọn thư viện hash được kiểm chứng khi triển khai; không đưa toàn bộ tệp vào một lệnh digest trong RAM. Digest xác minh tính nhất quán nội dung, không thay thế xác thực peer. Nếu state hash không phục hồi được, rehash dữ liệu đã lưu theo luồng để vẫn kiểm tra toàn tệp.

Tiến trình phía gửi phân biệt đã gửi, peer đã nhận, đang xác minh và đã lưu. Mất `FILE_COMPLETE_ACK` dẫn tới đối soát trạng thái bằng cùng transfer ID; không tự báo thành công hoặc gửi lại toàn bộ ngay lập tức.

### P4: quy tắc resume và bộ nhớ

Khởi đầu dùng prefix liên tục đã commit, thay vì selective retransmission phức tạp. Transport reliable ordered đã xử lý mất packet; ACK ứng dụng phục vụ receiver flow control, checkpoint và xác nhận lưu.

Checkpoint được công bố sau khi byte đã commit và metadata được lưu. Khi recover, kiểm tra file thực tế/offset/hash; metadata và file sink không có transaction chung. Nếu không khớp, lùi về checkpoint đã xác minh và gửi lại phần đuôi. Không tin offset do peer khai khi vượt kích thước tệp.

Resume cùng tab trước; sau đó resume qua reload/browser restart. Bên gửi có thể phải chọn lại file và cấp lại quyền; xác minh file identity trước tiếp tục. Bên nhận phải còn dữ liệu tạm và quyền ghi. Không cam kết tiếp tục tự động khi browser đóng hoặc thiết bị offline.

File source đọc theo luồng; sink ghi theo luồng. Ngân sách khởi đầu cho các buffer do ứng dụng giữ là ≤32 MiB cho một lượt truyền, có receiver credit để dừng sender khi ghi đĩa chậm. Đây là mục tiêu thiết kế, không phải trần toàn bộ RAM của browser/OS. Pause/hủy phải tác động trong một cửa sổ buffer hữu hạn. Hết đĩa/quota không được ACK hoàn tất.

## Ma trận nghiệm thu bắt buộc

| Nhóm | Tình huống |
| --- | --- |
| Phiên | Hai máy thật; LAN không Internet; >120 giây; >30 phút; refresh token; revoke; server restart; hai bên cùng mở kết nối |
| Chat | ACK mất/chậm; retry nhiều lần; reload; đổi peer/tài khoản; outbox đầy; storage lỗi; đang gửi tệp vẫn chat được |
| Tệp | 0 B, nhỏ, 1 GiB, ≥10 GiB; nhiều tệp; gửi hai chiều; tên Unicode; reject/cancel; thiếu byte; sai hash |
| Recovery | Ngắt Wi-Fi tại 10%/50%/90%; reload; browser restart; file nguồn bị sửa; checkpoint bị lỗi; thiếu quyền; hết quota/đĩa |
| Tương thích | Next.js ↔ `/client`; v1 ↔ v2; Chrome ↔ Edge; browser không có disk sink |
| Quyền | Khác workspace; membership bị gỡ; session/device đổi; packet sai ID/offset; request vượt giới hạn |
| Hiệu năng | Throughput và RAM theo kích thước; p50/p95 thời gian ACK chat khi rảnh và khi truyền tệp; CPU/hash; chi phí commit checkpoint |

Mục tiêu ban đầu: trên LAN kiểm soát với RTT baseline ≤10 ms, p95 ACK chat ≤500 ms khi truyền một tệp lớn; đo lại trên Wi-Fi để điều chỉnh scheduler. Không dùng benchmark TCP cũ làm tốc độ WebRTC. Báo cáo cấu hình máy/browser, số lần chạy, buffer và skips.

Unit/contract tests cho state machine, duplicate/out-of-order/control và checkpoint; integration PostgreSQL cho permission/lease trên database riêng; browser E2E thực cho byte/hash/storage/reload. Chạy các kiểm tra phù hợp trong [TEST_PLAN](../TEST_PLAN.md) bằng JDK 21 và Node 20+; mỗi milestone cập nhật protocol, hướng dẫn recovery và ảnh UI liên quan.

## Phần code sẽ thay đổi

- Java: `server/transfers/TransferCoordinator`, `TransferRecord`, `server/realtime/TransferSignalingHandler`, gateway auth/re-auth; các test signaling/auth hiện có.
- Browser: `frontend/lib/webrtc/`, local storage/worker mới, `frontend/components/screens/transfers.tsx`, provider và UI client `/client`.
- Build: bundle engine dùng chung cho client Java, kiểm tra version/artifact không bị cũ.
- Docs: contract realtime bổ sung, DataChannel v2, hướng dẫn HTTPS LAN, storage/resume, browser compatibility và test matrix.
- Database: chỉ thêm migration theo số tiếp theo nếu cần lưu transfer metadata/capability; không tạo bảng lưu nội dung chat trực tiếp hay byte tệp trong milestone này.

## Nguồn kỹ thuật

- [W3C WebRTC](https://www.w3.org/TR/webrtc/): DataChannel, buffered amount, max message size và ICE restart.
- [Chrome File System Access](https://developer.chrome.com/docs/capabilities/web-apis/file-system-access): quyền, secure context, user gesture và commit khi đóng stream.
- [WHATWG File System Standard](https://fs.spec.whatwg.org/): origin-private storage, file handles và access handles.

Các ngưỡng, tên message v2 và phân chia module ở đây là đề xuất của dự án; cần chốt bằng spike P0 và contract tests trước khi phát hành.
