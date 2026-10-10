# P2P v2: chat bền vững và truyền tệp có xác minh

Ngày 2026-10-10. Engine tại `frontend/lib/p2p/`; Next.js và `/client` sử dụng cùng source. Build tạo cả hai bản bundle và worker; `npm run p2p:check` phát hiện artifact cũ.

Gateway Java xác thực tài khoản/session/device, kiểm tra quyền workspace và chuyển tiếp SDP/ICE. Byte tệp và chat trực tiếp đi qua WebRTC; không lưu trên gateway/PostgreSQL. Metadata/checkpoint và chat nằm trong trình duyệt theo server, tài khoản, workspace, người nhận và deviceId đích. ConnectionId chỉ dùng định tuyến phiên hiện tại.

## Kết nối và phiên signaling

Gateway envelope vẫn dùng protocolVersion 1. `payload.protocolVersion: 2` trong `transfer.request` bật idempotency theo connectionId + requestId: gửi lại cùng nội dung nhận cùng transferId; nội dung xung đột bị từ chối. Mỗi lệnh mới dùng requestId UUID.

| Lệnh | Payload | Hành vi |
| --- | --- | --- |
| `transfer.renew` | `transferId` | Chỉ gia hạn `P2P_CONNECTED`, kiểm tra lại cả hai session và membership; trả `transfer.renew_ack` với `expiresAt` mới |
| `webrtc.restart` | `transferId`, `generation` | Chỉ offerer, generation hiện tại + 1; reset offer/answer flags và deadline negotiation; báo answerer và trả `webrtc.restart_ack` |
| Offer/answer/candidate/connection-status | Contract hiện có + `generation` | Relay chỉ khi generation khớp; thiếu generation tương đương 0; packet cũ trả `STALE_NEGOTIATION` |
| `transfer.cancel` | `transferId`, reason tùy chọn | Trả trạng thái CANCELLED khi gọi lặp; không gửi thêm thông báo peer; vẫn kiểm tra người tham gia/session/membership |

Deadline ban đầu và lease dùng `WEBRTC_SESSION_TTL_MS`, mặc định 120 giây. Hai client gia hạn mỗi 10 giây khi server xác nhận cả hai đã kết nối. Không gia hạn negotiation chưa hoàn tất. Mất quyền dẫn đến FAILED và cleanup. Client dừng khi renew bị từ chối hoặc deadline cục bộ hết hạn. Gateway không thể cưỡng chế đóng DataChannel của một client bị sửa đổi.

Manager giữ tối đa hai peer; đổi cuộc trò chuyện giữ tác vụ ở nền. Reconnect tìm cùng user/device với connectionId mới, backoff tăng dần tới 30 giây, tối đa 5 lần; logout dừng retry/worker. ICE mất kết nối tạm thời được offerer thử restart sau 3 giây; timeout chuyển sang recovery bằng phiên mới.

## DataChannel

Hai kênh reliable ordered: `landrop-p2p` cho chat/control và `landrop-file-v2` cho block/header/binary/finish/pause/cancel. Cùng chia sẻ SCTP nên không đảm bảo độ trễ bằng việc tách kênh. Một block tệp đang chờ ACK mỗi peer, kiểm soát `bufferedAmount` ở 256 KiB.

Control JSON có `{v:2,type,...}`. `HELLO` trao đổi version, maxMessageSize, blockSize, durableChat và disk. `READY` xác nhận version 2. Client thực không nhận legacy FILE/CHAT qua đường v1; peer cũ cần nâng cấp, không tự công bố checksum/resume.

| Thông điệp | Nội dung / điều kiện |
| --- | --- |
| `CHAT` | UUID, text tối đa 16000 byte UTF-8, timestamp, replyToId tùy chọn |
| `CHAT_ACK` | UUID; chỉ sau khi receiver commit IndexedDB; trùng UUID/nội dung chỉ ACK lại |
| `OFFER` | UUID tệp, name, size, mime, SHA-256 toàn tệp; chưa gửi byte trước consent |
| `ACCEPT` | UUID, offset checkpoint; biên 1 MiB hoặc EOF |
| `REJECT` / `ERROR` | UUID, reason; kết thúc/chờ retry riêng tệp |
| `BLOCK` | UUID, offset, size tối đa 1 MiB, SHA-256 block; cùng lane ordered với binary |
| `PROGRESS` | UUID, offset đã ghi, hash block; sau flush byte và commit metadata |
| `FINISH` | UUID, size, SHA-256 toàn tệp |
| `COMPLETE` | UUID, size, hash, savedTo; sau kiểm tra toàn tệp và commit sink |
| `PAUSE` / `PAUSE_ACK` | Dừng sau block hiện tại; ACK sau khi file lane xử lý xong, tránh resume vượt checkpoint |
| `CANCEL` | UUID; hủy riêng tệp ở cả hai đầu, dọn staging phía nhận |

Binary header dài 32 byte, số nguyên network byte order:

| Byte | Kiểu | Ý nghĩa |
| --- | --- | --- |
| 0–3 | uint8[4] | `0x4c 0x44 0x02 0x00` |
| 4–19 | uint8[16] | UUID |
| 20–27 | uint64 | Offset tuyệt đối, phải biểu diễn chính xác bằng JS Number |
| 28–31 | uint32 | Payload length |
| 32 trở đi | bytes | Tối đa 16 KiB, nhỏ hơn giới hạn message đã thương lượng trừ header |

Sai ID, offset, biên block, kích thước, hash hay framing bị từ chối trước checkpoint. Tệp rỗng vẫn đi qua consent và FINISH/COMPLETE.

## Chat: ý nghĩa ACK

Sender commit history + outbox trong cùng IndexedDB transaction trước gửi. Receiver commit trước ACK; lỗi quota/store không ACK. Sender cũng commit delivered + xóa outbox trước hiển thị delivered. Trạng thái queued → sending → delivered, hoặc failed/cancelled. Delivered không đồng nghĩa đã đọc.

Retry cùng UUID, tối đa 5 lần, backoff 1.5–15 giây; vẫn giữ outbox sau thất bại để người dùng gửi lại. Reconnect/reload flush outbox sau capability exchange. Outbox giới hạn 1000; restore hiển thị 1000 tin gần nhất, dedup dựa trên dữ liệu đã lưu. Logout giữ dữ liệu trong origin nhưng không gửi tiếp; đăng nhập tài khoản khác dùng scope khác. Xóa history đồng thời xóa outbox.

## Lưu tệp và recovery

SHA-256 dùng `@noble/hashes`, tính tăng dần trong worker, đọc từng 1 MiB. Sender đọc một lượt để lấy full hash trước OFFER; resume kiểm tra lại toàn bộ file nguồn. Không chỉ so tên/kích thước. Receiver kiểm tra hash từng block rồi hash toàn tệp trước COMPLETE. Preflight và final verification thêm I/O/CPU, được hiển thị thành trạng thái riêng.

HTTPS + OPFS: worker dùng sync access handle, ghi đúng offset và `flush()` trước khi main thread lưu checkpoint trong IndexedDB. Hai nơi lưu không có transaction chung: khi mở lại, file ngắn hơn metadata thì quay về zero; phần đuôi chưa ACK được truncate tới checkpoint. Nếu nội dung checkpoint sai, full hash cuối sẽ chặn COMPLETE; hủy và gửi lại từ đầu để khôi phục.

Receiver chọn nơi lưu bằng File System Access trong user gesture; byte vẫn staging trong OPFS. Sau verification, stream sang đích và đóng writable trước COMPLETE. Cần đủ dung lượng cho staging và bản đích. Không chọn đích thì tệp đã xác minh nằm trong OPFS, có thể tải bằng nút tải và mở lại sau reload. `savedTo` phân biệt `disk` (đích đã commit), `browser` (OPFS), `memory` (tạm trong tab).

Không có OPFS: tổng reservation tệp nhỏ tối đa 64 MiB mỗi peer; dữ liệu/Blob URL được giải phóng khi xóa task hoặc logout. Tệp trong memory không tồn tại qua reload. Resume tại chế độ này cần gửi lại từ zero. Không fallback tệp lớn sang RAM.

Hàng đợi tối đa 20 tệp chờ mỗi peer, truyền tuần tự; receiver tối đa 8 offer đang chờ, một lượt nhận đang chạy. Pause giữ checkpoint; resume dùng UUID cũ và một OFFER/consent mới. Sender sau reload phải chọn lại tệp gốc. Receiver phải giữ origin/profile/staging; bị xóa site data hoặc eviction có thể phải nhận lại từ đầu. OPFS quota là giới hạn thực tế; trần kiểm tra protocol 5 TiB không phải cam kết browser nhận được kích thước đó.

FINISH lặp tối đa 3 lần khi COMPLETE thất lạc. Receiver đã completed trả lại receipt đã lưu, không tạo thêm một file/bubble. Sender chỉ completed khi receipt đúng size/hash; chưa nhận ACK thì không công bố thành công.

## Bộ nhớ và kiểm thử

Disk path: một block nguồn 1 MiB, một block nhận 1 MiB, frame tối đa 16 KiB, sender high water 256 KiB, hàng đợi nhận giới hạn khoảng 2.125 MiB. Hash cũng đọc từng 1 MiB. Đây là bounds do ứng dụng đặt; không phải trần RSS của Chrome, SCTP, OS cache hoặc file nguồn do người dùng chọn.

Chạy `npm --prefix frontend test`, `npm run p2p:check`, test signaling Java và hai browser scripts trong [hướng dẫn LAN](../guides/P2P_LAN.md). Báo cáo chính xác bài đã chạy tại [P2P_IMPLEMENTATION_REPORT](../architecture/P2P_IMPLEMENTATION_REPORT.md).
