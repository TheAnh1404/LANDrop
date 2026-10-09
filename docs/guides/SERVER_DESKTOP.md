# LANDrop Server Desktop

Chạy từ thư mục gốc bằng `npm run server` với JDK 21 và Maven 3.9+. Ứng dụng mở cửa sổ desktop và tự khởi động Java backend. Không cần chạy Next.js để sử dụng client đi kèm.

## Điều khiển máy chủ

- **Tổng quan:** trạng thái chạy, số WebSocket đang online, phiên signaling đang hoạt động, uptime, bộ nhớ Java và tình trạng dịch vụ. Nút Khởi động/Dừng/Khởi động lại luôn ở phía trên.
- **Cho người dùng kết nối:** chọn card mạng LAN rồi chia sẻ URL/QR. Mặc định URL là `http://<IP>:<HTTP_PORT>/client`. Nút **Mở client** mở giao diện người dùng; **Quản trị web** mở `/admin` và vẫn yêu cầu xác thực admin như trước.
- **Thiết bị kết nối:** tìm theo thiết bị/tài khoản/IP, chọn một hàng để xem chi tiết hoặc ngắt WebSocket. Thao tác nhắm đúng connectionId đang chọn, không nhắm một socket mới sau reconnect. Cleanup subscription và signaling dùng gateway hiện có.
- **Phiên truyền:** hiển thị tên/kích thước tệp trong metadata, hai đối tác, trạng thái signaling và thời gian hết hạn. Hủy phiên gửi `transfer.cancel` với `ADMIN_CANCELLED` cho hai phía còn quyền nhận và loại phiên khỏi danh sách hoạt động. Đây là trạng thái điều phối WebRTC; không phải tiến độ truyền bytes.
- **Nhật ký:** lọc mức, tìm nội dung, bật/tắt theo dõi trực tiếp, sao chép hoặc xóa màn hình. Bộ đệm desktop giới hạn 2.000 sự kiện. Xóa màn hình không xóa log máy chủ; không hiển thị SDP đầy đủ.
- **Cấu hình:** địa chỉ bind, HTTP/WebSocket, chế độ client, cổng Next.js và thu nhỏ xuống khay. Kiểm tra cổng hợp lệ và xung đột trước khi áp dụng.

Các cài đặt trong cửa sổ có hiệu lực cho lần chạy desktop hiện tại. Đổi bind/cổng khi server đang chạy cần bấm Khởi động lại. URL/QR vẫn dùng bind/cổng của server đang chạy cho đến khi restart thành công. Muốn giữ cấu hình qua lần mở ứng dụng sau, đặt `HOST`, `PORT`, `WS_PORT` trong cấu hình launcher.

## Client Next.js riêng

Nếu dùng frontend Next.js, chọn **Next.js client riêng** trong Cấu hình. Backend HTTP, WebSocket và Next.js phải dùng ba cổng khác nhau, ví dụ 3100/3101/3000. Desktop không tự chạy frontend; chạy `npm run dev` ở terminal khác.

URL chia sẻ có dạng `http://<IP>:3000/connect?server=<backend URL đã encode>`. Client nhận sẵn địa chỉ backend; người dùng đăng ký/đăng nhập rồi chọn workspace. Các chức năng cần persistent user/workspace yêu cầu PostgreSQL và migrations đã cấu hình.

Có thể đặt `CLIENT_EXTERNAL=true` và `CLIENT_PORT=3000` trước khi mở desktop để chọn sẵn chế độ này. Để dùng client đi kèm, giữ `CLIENT_EXTERNAL=false` (mặc định). Bind `127.0.0.1` chỉ cho phép máy hiện tại; bind `0.0.0.0` rồi chọn IP LAN để chia sẻ. Gateway tiếp tục kiểm tra Origin/session theo [REALTIME-01](../api/REALTIME_01_CONTRACT.md); với frontend riêng, cấu hình origin chính xác trong `WS_ALLOWED_ORIGINS`.

## Vòng đời và khay hệ thống

Khởi động/dừng chạy ngoài Swing event thread và được xử lý tuần tự. Khởi động lại chờ dịch vụ cũ dừng trước khi tạo backend mới; nút bị khóa trong lúc chuyển trạng thái. Khi có lỗi dừng/cleanup, giao diện giữ quyền dừng lại và không ghi đè runtime bằng một lần khởi động mới.

Khi bật thu nhỏ xuống khay, đóng cửa sổ giữ server hoạt động nếu đăng ký khay thành công. Mở lại bằng biểu tượng khay. **Thoát hoàn toàn** chờ dừng dịch vụ rồi tháo listener log, timer, khay và worker. Nếu khay không khả dụng, đóng cửa sổ dùng luồng thoát này.

## Kiểm chứng và ảnh giao diện

Tests desktop bao gồm vòng đời lỗi/retry, close trong lúc start, một vòng start/restart/stop backend thật ở loopback, URL client, kiểm tra cấu hình, chọn hàng sau sort/filter/refresh, log và đổi theme. Socket tests xác minh ngắt đúng client và hủy signaling thông báo cả hai phía; REALTIME-01 chạy để kiểm tra regression chat/auth/reconnect.

```powershell
mvn clean install "-Dlandrop.database.enabled=false"
npm test
```

Các suite PostgreSQL cần database test riêng và không được chứng nhận bởi lần chạy database-disabled. Ảnh dưới đây render trực tiếp production Swing panel với dữ liệu mẫu; chưa chứng nhận thao tác khay/cửa sổ native trên Windows:

- [Tổng quan](../../output/desktop-server/landrop-server-overview.png)
- [Thiết bị](../../output/desktop-server/landrop-server-clients.png)
- [Phiên signaling](../../output/desktop-server/landrop-server-transfers.png)
- [Nhật ký](../../output/desktop-server/landrop-server-logs.png)
- [Cấu hình](../../output/desktop-server/landrop-server-settings.png)
- [Giao diện tối](../../output/desktop-server/landrop-server-dark.png)

Tạo lại ảnh mà không chạy backend:

```powershell
mvn -pl backend test-compile exec:java "-Dexec.mainClass=com.landrop.server.desktop.ServerDashboardPreview" "-Dexec.classpathScope=test" "-Dexec.args=output/desktop-server"
```

Nguồn giao diện: `ServerDashboardPanel`; vòng đời: `DesktopServerController`; nối dữ liệu/thao tác backend: `ServerMainFrame`; cấu hình/URL: `DesktopServerSettings`; khay: `DesktopTrayManager`. Không tạo WebSocket gateway hay protocol thứ hai.

Kết quả ngày 09/10/2026, JDK 21: `mvn clean install -Dlandrop.database.enabled=false` **BUILD SUCCESS**, 354 tests, 319 đạt, 35 bỏ qua, 0 failures/errors. Riêng desktop: 20/20; signaling relay: 18/18; REALTIME-01 gateway: 13/13. `npm test`: 18/18 launcher tests đạt. Các suite bỏ qua: `AdminControlCenterTest` (4), `PersistentChatPostgresTest` (9), `FileManagementPostgresTest` (9), `RealtimePostgresTest` (3), `Realtime01PostgresTest` (7), `WorkspacePostgresTest` (3), do cần database.

Một lượt chạy toàn bộ trước đó gặp timeout ở `LargeFilePeerToPeerIntegrationTest.testPauseAndResumeWithMetaPersistence` thuộc TCP cũ. Suite đó đạt khi chạy riêng (2/2), và lượt clean install toàn bộ tiếp theo đạt như trên. Không sửa hoặc bỏ qua test TCP để đạt kết quả.
