# Chạy P2P chat và tệp lớn trong LAN

Next.js và `/client` dùng [giao thức v2](../api/P2P_DATA_CHANNEL_V2.md). Cả hai tài khoản phải là thành viên workspace. HTTP qua IP LAN chỉ phù hợp chế độ tệp nhỏ; sử dụng HTTPS được trình duyệt tin cậy để ghi OPFS và resume qua reload.

## Cấu hình máy server

JDK 21, Maven 3.9+, Node 20+. Cài frontend và tạo engine trước khi đóng gói Java:

```powershell
npm --prefix frontend ci
npm run p2p:build
mvn -pl backend package
```

Đặt vào `.env.local` cùng cấu hình database/auth hiện có:

```dotenv
HTTP_PORT=3100
WS_PORT=3101
LANDROP_PROXY_BACKEND=http://127.0.0.1:3100
LANDROP_PROXY_WS=http://127.0.0.1:3101
LANDROP_TLS_PORT=3443
# Bật dòng này nếu dùng Next.js (mặc định proxy phục vụ client Java).
LANDROP_PROXY_FRONTEND=http://127.0.0.1:3000
# Thay bằng origin thực tế trên LAN.
WS_ALLOWED_ORIGINS=https://landrop.local:3443,https://192.168.1.10:3443
```

Hostname phải phân giải tới máy server qua DNS/router/hosts; có thể dùng IP LAN trong chứng chỉ. Các địa chỉ ở trên chỉ là ví dụ.

Cài `mkcert` theo [hướng dẫn chính thức](https://github.com/FiloSottile/mkcert#installation), rồi tạo certificate cho hostname/IP thực:

```powershell
./scripts/setup-lan-tls.ps1 -Hostnames 'landrop.local','192.168.1.10'
```

Lệnh setup chủ động chạy `mkcert -install` để thêm CA cục bộ vào trust store. Không được chạy ngầm trong build. `data/tls` được gitignore. Mỗi máy client cần tin cùng CA: chuyển **rootCA.pem**, cài vào Trusted Root Certification Authorities; không chuyển `rootCA-key.pem`. `mkcert -CAROOT` chỉ ra thư mục CA. Có thể thay bằng certificate/key do hệ thống PKI của nhóm cung cấp qua `LANDROP_TLS_CERT` / `LANDROP_TLS_KEY`.

Chạy ba cửa sổ terminal nếu dùng Next.js:

```powershell
npm run server:backend
```

```powershell
npm run dev
```

```powershell
npm run server:secure
```

Mở `https://landrop.local:3443/client` cho client Java, `https://landrop.local:3443/connect` cho Next.js. Trong Next.js, địa chỉ backend cũng là `https://landrop.local:3443`. API, worker và WSS `/ws` đi qua cùng origin; không nhập upstream HTTP vào client HTTPS.

Cho phép cổng proxy qua firewall và kết nối WebRTC giữa hai máy theo chính sách mạng. Mở thêm port không khắc phục được Wi-Fi client isolation hoặc VLAN chặn peer. Không tự cấu hình router/TURN trong bản LAN này.

## Gửi và nhận

1. Đăng nhập, vào cùng workspace, chọn đúng tài khoản **và thiết bị**.
2. Chấp nhận kết nối nếu Next.js hỏi. Đây là phiên chat; từng tệp có consent riêng.
3. Thêm nhiều tệp hoặc kéo thả; hàng đợi truyền tuần tự theo peer. Đổi cuộc trò chuyện vẫn giữ tối đa hai peer chạy nền.
4. Người nhận bấm **Nhận và lưu**. Chrome/Edge có thể mở hộp chọn nơi lưu. Hủy hộp thoại giữ offer; chưa gửi byte tệp.
5. Tiến trình dựa trên byte được receiver xác nhận đã ghi/checkpoint. Trạng thái xác minh/lưu có thể mất thời gian với tệp lớn.
6. Completed chỉ sau SHA-256 và receipt từ receiver. Nếu lưu trong trình duyệt, dùng **Tải tệp đã xác minh** để xuất ra thư mục.

Chế độ không có disk sink giới hạn tổng 64 MiB mỗi peer và mất tệp tạm khi reload. OPFS cần quota; staging và bản lưu ngoài có thể chiếm hai lần dung lượng. Không hứa tệp bất kỳ lớn bao nhiêu cũng nhận được.

## Pause, mất mạng và reload

Pause chờ receiver xử lý block hiện tại và PAUSE_ACK. Checkpoint vẫn giữ; nhấn tiếp tục và chọn tệp gốc. File nguồn khác dù cùng tên/kích thước sẽ bị từ chối vì hash không khớp.

Sau reload, đăng nhập/vào đúng server, workspace và thiết bị. Chọn lại peer; task interrupted sẽ được restore. Người gửi chọn lại file gốc; người nhận chấp nhận OFFER mới và có thể chọn lại nơi lưu. File UUID giữ nguyên, signaling session được tạo mới. Nếu staging bị eviction/xóa dữ liệu trang, nhận lại từ zero. Trình duyệt không tiếp tục khi đóng tab.

Sau hoàn tất, dữ liệu OPFS có thể tải lại qua nút tải, kể cả sau reload; hash được kiểm tra lại. **Xóa dữ liệu tạm** xóa metadata/staging, không xóa file đã lưu ở thư mục ngoài. Logout dừng worker/kết nối; history/outbox còn cục bộ và chỉ xuất hiện ở đúng tài khoản.

| Hiện tượng | Xử lý |
| --- | --- |
| Browser báo không có disk sink | Kiểm tra URL HTTPS, certificate được tin cậy, Chrome/Edge và quyền storage |
| Chờ người nhận | Chọn cửa sổ/tab receiver và chấp nhận offer |
| Hết quota/đĩa | Xóa staging đã hoàn tất, giải phóng đĩa; thử lại consent, không có COMPLETE giả |
| SHA-256 không khớp | Không sử dụng tệp; hủy task và gửi lại nguồn gốc |
| Phiên hết hạn/mất quyền | Kiểm tra gateway/session/membership; kết nối lại sau khi quyền hợp lệ |
| Peer không xuất hiện | Cùng server/workspace, session đang online; làm mới danh sách và kiểm tra Wi-Fi isolation |
| Không kết nối được dù peer online | Xem diagnostics/ICE; kiểm tra firewall, subnet và VLAN |
| Tin failed hoặc queued | Kết nối đúng peer rồi gửi lại; không được server giữ hộ |

## Kiểm thử tái lập

```powershell
npm --prefix frontend test
npm test
npm run p2p:check
npm run build
mvn -pl backend test '-Dlandrop.database.enabled=false' '-Dtest=WebRTCSignalingRelayTest'
```

Đã cài dev dependency Playwright. Browser scripts mặc định dùng Chrome đã cài; `CHROME_PATH` ghi đè đường dẫn. Protocol smoke tự tạo server loopback và hai browser context cô lập, ghi report vào `output/p2p/browser-report.json`:

```powershell
$env:LANDROP_TEST_MIB = '128'
npm run test:p2p:browser
```

Có thể đặt `LANDROP_TEST_CERT`/`LANDROP_TEST_KEY` trỏ certificate thử nghiệm để smoke qua HTTPS proxy. Script bỏ qua lỗi tin cậy **chỉ trong context kiểm thử**, không cài CA lên máy.

Live smoke cần backend đang chạy với **database test riêng** (hoặc schema test riêng do harness quản lý), frontend production đã build và port khác backend:

```powershell
$env:LANDROP_TEST_BACKEND = 'http://127.0.0.1:3100'
$env:LANDROP_TEST_FRONTEND = 'http://127.0.0.1:3000'
$env:LANDROP_TEST_CERT = 'C:/test/lan.pem'
$env:LANDROP_TEST_KEY = 'C:/test/lan-key.pem'
$env:LANDROP_TEST_LEASE_SECONDS = '135'
npm run test:p2p:live
```

Live smoke tạo hai account/workspace test, truyền Next.js ↔ `/client`, so byte tải xuống, kiểm tra phiên qua deadline ban đầu và chụp desktop/mobile. Chỉ chạy vào dữ liệu test; script không tự xóa account vì schema/harness cần quản lý cleanup. Báo cáo không chứa credential/token/private key.

Nghiệm thu phát hành còn cần hai máy vật lý Chrome ↔ Edge: LAN offline, chat 30 phút, tệp 1/10 GiB, ngắt mạng 10/50/90%, browser restart, quota/permission failure và đo RAM/RSS riêng. Benchmark loopback không thay bài này. Dùng [báo cáo triển khai](../architecture/P2P_IMPLEMENTATION_REPORT.md) để phân biệt kết quả đã đo và bài còn chờ.
