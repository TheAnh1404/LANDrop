# LANDrop

**Ứng dụng desktop chia sẻ file và nhắn tin trực tiếp giữa các máy trong mạng LAN**, được xây dựng bằng Java 21 và JavaFX.

LANDrop sử dụng Discovery Server để các máy tìm thấy nhau. Sau khi kết nối, tin nhắn và dữ liệu file được truyền trực tiếp giữa các peer qua TCP. Phần Core hoạt động trong mạng nội bộ mà không cần Internet hoặc cơ sở dữ liệu cloud.

## Trạng thái dự án

| Phạm vi | Trạng thái trong repository |
| --- | --- |
| **Core — Phase 1–13** | Đã hoàn thành: discovery, chat, truyền file, tải từ nhiều peer và giao diện JavaFX |
| **Frontend — Phase 14B** | Đã hoàn thành nền tảng domain, DTO, mapper, API client và mock data bằng Java |
| **Backend cloud — Phase 14A** | Neon/PostgreSQL, schema SQL, migration, seed, model và repository layer đã có trong `backend/` |
| **Backend — Phase 15.5A** | Authenticated WebSocket, nhiều kết nối mỗi User, chat lưu PostgreSQL; [hướng dẫn nhiều máy](docs/PHASE-15.5A-MULTI-DEVICE.md) |
| **Backend — Phase 16A** | Workspace và membership API, vai trò OWNER/ADMIN/MEMBER, kiểm tra quyền tập trung; [API contract](docs/PHASE-16A-API-CONTRACT.md) |
| **Frontend — Phase 15B** | Authentication UI, centralized session state, session restore, logout và access-token refresh/retry đã có |
| **Tích hợp API/database thực tế** | Backend Auth HTTP + WebSocket đã kiểm thử với Neon trong schema tạm; frontend tích hợp theo [contract realtime](docs/PHASE-15.5A-REALTIME-CONTRACT.md) |

Phase 14B là một lớp dữ liệu frontend độc lập, chưa được đưa vào luồng khởi động hoặc các màn hình Core. Phase 15B bổ sung auth gate trước khi vào setup/application; dữ liệu cloud vẫn chưa được đưa vào màn hình workspace.

## Chức năng hiện có

### Kết nối và nhắn tin

- Đăng ký peer với Discovery Server, duy trì heartbeat và lấy danh sách peer đang online.
- Thiết lập kết nối TCP trực tiếp giữa các peer.
- Nhắn tin hai chiều và lưu lịch sử hội thoại trong bộ nhớ của phiên chạy.
- Tách kết nối chat và truyền file bằng mục đích kết nối `CHAT` / `FILE_TRANSFER`.

### Chia sẻ file

- Gửi và nhận file với trạng thái, tiến độ và cơ chế tiếp tục truyền khi kết nối bị gián đoạn.
- Chia file thành các chunk và tải đồng thời từ nhiều peer có cùng file — **multi-peer download / swarming**.
- Bổ sung nguồn tải trong lúc truyền và phân công lại chunk khi một peer mất kết nối.
- Kiểm tra SHA-256 trước khi hoàn tất file nhận.
- Xử lý tên file đầu vào để hạn chế ghi ra ngoài thư mục nhận.

### Giao diện desktop

JavaFX cung cấp các màn hình thiết lập kết nối, dashboard, danh sách peer, chat, quản lý truyền file và cài đặt. `UIStateService` kết nối trạng thái giao diện với các dịch vụ Core và điều phối cập nhật trên JavaFX Application Thread.

## Công nghệ

| Thành phần | Công nghệ |
| --- | --- |
| Ngôn ngữ | Java, biên dịch với `--release 21` |
| Giao diện | JavaFX 21.0.2, FXML, CSS |
| Build | Maven, cấu trúc đa module |
| Mạng LAN | Java TCP sockets, giao thức nhị phân với header 14 byte |
| Xử lý đồng thời | Virtual threads, `CompletableFuture` |
| Đọc/ghi file | Java NIO, `FileChannel` |
| Kiểm tra toàn vẹn | SHA-256 |
| JSON | Jackson Databind 2.17.0 |
| HTTP client Phase 14B | `java.net.http.HttpClient` |
| Kiểm thử | JUnit Jupiter 5.10.2, Maven Surefire |

Frontend hiện tại là ứng dụng JavaFX; repository không có ứng dụng TypeScript hoặc yêu cầu cài Node.js.

## Kiến trúc

```text
                    Discovery Server
                       TCP :8888
                  đăng ký / tìm peer
                    /           \
                   v             v
             Peer A <----------> Peer B
                   chat / file TCP
                 \               /
                  \             /
                   v           v
                       Peer C
                tải chunk từ nhiều nguồn
```

Discovery Server giữ thông tin hiện diện và địa chỉ kết nối. Nội dung chat và file đi qua kết nối trực tiếp giữa các peer. Với swarming, peer nhận tải các chunk từ nhiều nguồn, ghép file và xác minh SHA-256.

Luồng dữ liệu mới của Phase 14B:

```text
Mock JSON hoặc HTTP API khi backend sẵn sàng
                      |
                     DTO
                      |
              Kiểm tra và mapping
                      |
             Domain records bất biến
                      |
             Thành phần trình bày
```

Hai luồng có định danh riêng: ID của user/message trong database không được suy ra từ tên peer hoặc request ID của gói tin LAN. Metadata `File` cũng tách biệt với phiên truyền `FileTransfer`.

Xem [kiến trúc chi tiết](docs/ARCHITECTURE.md) và [giao thức Core](docs/PROTOCOL.md).

## Cấu trúc thư mục

```text
LANDrop/
├── pom.xml                          # Maven aggregator: backend + frontend
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/landrop/
│       │   ├── config/              # Cấu hình Core
│       │   ├── discovery/           # Discovery Server và client
│       │   ├── network/             # TCP server/client và điểm chạy CLI
│       │   ├── peer/                # Kết nối, định danh và vòng đời peer
│       │   ├── protocol/            # Packet, encoder, decoder
│       │   ├── chat/                # Dịch vụ chat và lịch sử trong bộ nhớ
│       │   ├── transfer/            # Truyền file, chunk, resume, swarming
│       │   └── util/                # Tiện ích và logging
│       └── test/java/com/landrop/   # Kiểm thử Core
├── frontend/
│   ├── pom.xml
│   ├── README.md
│   └── src/
│       ├── main/java/com/landrop/ui/
│       │   ├── MainApp.java         # Điểm khởi chạy JavaFX
│       │   ├── controllers/        # Điều khiển màn hình
│       │   ├── services/           # Trạng thái và kết nối giao diện với Core
│       │   ├── models/             # Model phục vụ giao diện Core
│       │   ├── utils/              # Tiện ích giao diện
│       │   ├── cloud/              # Nền tảng dữ liệu frontend Phase 14B
│       │   └── auth/               # Authentication và session Phase 15B
│       │       ├── model/          # Domain records
│       │       ├── dto/            # Hình dạng JSON dự kiến từ API
│       │       ├── mapping/        # Kiểm tra JSON và chuyển đổi DTO
│       │       └── api/            # HTTP client và mock transport
│       ├── main/resources/com/landrop/ui/
│       │                           # FXML, CSS và cloud/phase14-mock.json
│       └── test/java/com/landrop/ui/
│                                   # Kiểm thử JavaFX và nền tảng dữ liệu
├── docs/                           # Kiến trúc, demo, contract và bàn giao
├── start-discovery.sh / .bat
└── start-peerA…D.sh / .bat          # Script mở các peer demo
```

`backend/` chứa logic Core, xử lý mạng và database foundation Phase 14A. `frontend/` chứa giao diện cùng các kiểu dữ liệu, mapper và client mà giao diện sử dụng. Package `frontend/.../cloud/api` là **client gọi API**, không phải nơi triển khai backend endpoint. HTTP API và việc nối repository vào endpoint chưa thuộc phần frontend này.

## Yêu cầu môi trường

- JDK 21 trở lên và Maven 3.9 trở lên có trong `PATH`.
- Môi trường desktop hỗ trợ JavaFX để chạy giao diện và các kiểm thử FXML.
- Các peer truy cập được Discovery Server và cổng TCP của nhau qua localhost hoặc LAN.
- Lần build đầu cần tải các dependency Maven. Khi dependency đã được lưu trong máy, có thể thêm `-o` để chạy Maven offline.

Kiểm tra công cụ:

```sh
java -version
mvn -version
```

## Bắt đầu nhanh

Chạy các lệnh sau từ thư mục gốc của project.

### 1. Build và cài các module vào Maven local repository

```sh
mvn clean install -DskipTests
```

Bước `install` giúp các lệnh chạy riêng module frontend tìm được dependency backend và parent POM. `-DskipTests` chỉ bỏ qua việc chạy test; xem phần kiểm thử bên dưới để xác minh toàn bộ project.

Build tạo các artifact:

- `backend/target/landrop-0.1.0-SNAPSHOT.jar`
- `frontend/target/landrop-frontend-0.1.0-SNAPSHOT.jar`

Các JAR này không đóng gói toàn bộ dependency; sử dụng các lệnh Maven hoặc script bên dưới để chạy ứng dụng.

### 2. Khởi động Discovery Server

Mở terminal thứ nhất:

```sh
mvn -pl backend exec:java -Dexec.mainClass=com.landrop.discovery.DiscoveryServer -Dexec.args="8888"
```

### 3. Khởi động hai peer

Mở terminal thứ hai:

```sh
mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5001 --display-name=PeerA --auto-connect=true"
```

Mở terminal thứ ba:

```sh
mvn -pl frontend javafx:run -Djavafx.args="--peer-port=5002 --display-name=PeerB --auto-connect=true"
```

Trong cửa sổ Peer A, mở **Peers**, kết nối đến Peer B rồi thử gửi tin nhắn hoặc file. File nhận mặc định được lưu trong thư mục `Downloads/LANDrop` của người dùng.

### Chạy bằng script

Sau bước build/install, có thể dùng các script tương ứng. Mỗi tiến trình cần một terminal riêng.

| Tiến trình | Cổng mặc định | macOS / Linux | Windows Command Prompt |
| --- | --- | --- | --- |
| Discovery | 8888 | `./start-discovery.sh` | `start-discovery.bat` |
| Peer A | 5001 | `./start-peerA.sh` | `start-peerA.bat` |
| Peer B | 5002 | `./start-peerB.sh` | `start-peerB.bat` |
| Peer C | 5003 | `./start-peerC.sh` | `start-peerC.bat` |
| Peer D | 5004 | `./start-peerD.sh` | `start-peerD.bat` |

Trong PowerShell, thêm `./` trước tên script `.bat`. Các script peer dùng địa chỉ localhost, phù hợp để demo nhiều cửa sổ trên cùng một máy. Vai trò nguồn tải hoặc máy nhận do thao tác trong ứng dụng quyết định.

## Chạy trên Windows

### 1. Chuẩn bị trên từng máy

1. Cài **JDK 21 trở lên** và **Apache Maven 3.9 trở lên**.
2. Trong **Environment Variables** của Windows, đặt `JAVA_HOME` trỏ tới thư mục JDK, ví dụ `C:\Tools\jdk-21`, không trỏ tới thư mục `bin`.
3. Thêm `%JAVA_HOME%\bin` và thư mục `bin` của Maven vào biến `Path`. Ví dụ Maven được giải nén ở `C:\Tools\apache-maven-3.9.9` thì thêm `C:\Tools\apache-maven-3.9.9\bin`. Thay các đường dẫn ví dụ bằng vị trí cài thực tế.
4. Mở lại PowerShell hoặc Command Prompt sau khi đổi biến môi trường.
5. Sao chép hoặc clone **toàn bộ project**, gồm `pom.xml`, `backend/`, `frontend/` và các script `.bat`, vào máy Windows. Các máy nên sử dụng cùng một phiên bản mã nguồn.

Kiểm tra trong PowerShell hoặc CMD:

```powershell
java -version
javac -version
mvn -version
where.exe java
where.exe mvn
```

`java`, `javac` và Java được Maven hiển thị phải đáp ứng phiên bản 21 trở lên. Nếu Maven đang dùng JDK cũ, kiểm tra lại `JAVA_HOME` và thứ tự các mục trong `Path`. Không cần cài Node.js, PostgreSQL hoặc Neon để chạy LAN Core.

### 2. Build project trên Windows

Ví dụ project nằm tại `C:\Projects\LANDrop`. Trong **PowerShell**:

```powershell
Set-Location "C:\Projects\LANDrop"
mvn clean install -DskipTests
```

Nếu dùng **Command Prompt (CMD)**:

```bat
cd /d "C:\Projects\LANDrop"
mvn clean install -DskipTests
```

Chờ Maven báo `BUILD SUCCESS`. Thực hiện bước này trên từng máy trước khi chạy riêng backend hoặc frontend. Lần đầu cần Internet để tải dependency, bao gồm thư viện JavaFX tương ứng với Windows; không chỉ sao chép thư mục `target/` từ macOS sang Windows.

### 3. Demo hai peer trên cùng một máy Windows

Mở **ba cửa sổ terminal**, mỗi cửa sổ ở thư mục gốc project. Giữ Discovery Server chạy trong khi sử dụng các peer.

**Cửa sổ 1 — Discovery Server:**

```powershell
.\start-discovery.bat
```

**Cửa sổ 2 — Peer A, cổng 5001:**

```powershell
.\start-peerA.bat
```

**Cửa sổ 3 — Peer B, cổng 5002:**

```powershell
.\start-peerB.bat
```

Các lệnh `.\...bat` trên dùng được trong cả PowerShell và CMD. Có thể mở thêm hai terminal để chạy `.\start-peerC.bat` và `.\start-peerD.bat`, tương ứng cổng 5003 và 5004. Trong giao diện Peer A, chọn **Peers → Connect** đến Peer B, sau đó thử chat và gửi file.

### 4. Chạy giữa nhiều máy Windows trong cùng LAN

Kết nối các máy vào mạng LAN có thể truy cập lẫn nhau. Trên mỗi máy, chạy `ipconfig` và lấy **IPv4 Address** của card Wi-Fi hoặc Ethernet đang sử dụng.

Ví dụ cấu hình ba máy:

| Máy | IPv4 ví dụ | Tiến trình | Cổng TCP |
| --- | --- | --- | --- |
| Máy A | `192.168.1.10` | Discovery Server và Peer A | `8888`, `5001` |
| Máy B | `192.168.1.20` | Peer B | `5002` |
| Máy C | `192.168.1.30` | Peer C | `5003` |

Chỉ cần **một Discovery Server** dùng chung. Trên máy A, mở một terminal tại thư mục project và chạy:

```powershell
.\start-discovery.bat 8888
```

Sau đó mở terminal khác trên máy A để chạy Peer A:

```powershell
mvn -pl frontend javafx:run "-Djavafx.args=--display-name=PeerA --peer-port=5001 --discovery-host=192.168.1.10 --discovery-port=8888 --advertised-host=192.168.1.10 --auto-connect=true"
```

Trên máy B, tại thư mục project:

```powershell
mvn -pl frontend javafx:run "-Djavafx.args=--display-name=PeerB --peer-port=5002 --discovery-host=192.168.1.10 --discovery-port=8888 --advertised-host=192.168.1.20 --auto-connect=true"
```

Trên máy C, tại thư mục project:

```powershell
mvn -pl frontend javafx:run "-Djavafx.args=--display-name=PeerC --peer-port=5003 --discovery-host=192.168.1.10 --discovery-port=8888 --advertised-host=192.168.1.30 --auto-connect=true"
```

Thay IP ví dụ bằng IP thật của từng máy. Các lệnh Maven trên dùng được trong PowerShell và CMD; giữ toàn bộ đối số `-Djavafx.args=...` trong dấu nháy kép. `--discovery-host` luôn là IP máy chạy Discovery, còn `--advertised-host` là IP của chính máy peer.

Các script `start-peerA.bat` đến `start-peerD.bat` hiện cấu hình localhost. Khi chạy trên nhiều máy, dùng các lệnh có IP LAN ở trên; `127.0.0.1` chỉ trỏ về chính máy đang chạy.

### 5. Firewall, thư mục nhận và kiểm tra kết nối

Nếu Windows Firewall hỏi quyền truy cập mạng cho Java, cho phép trên mạng **Private** của LAN đang dùng. Nếu cần cấu hình inbound rule thủ công, mở TCP `8888` trên máy Discovery và cổng peer tương ứng trên từng máy; không cần tắt toàn bộ firewall.

Kiểm tra từ máy B bằng **PowerShell** khi Discovery đang chạy:

```powershell
Test-NetConnection 192.168.1.10 -Port 8888
```

Kiểm tra từ máy A khi Peer B đang chạy:

```powershell
Test-NetConnection 192.168.1.20 -Port 5002
```

`TcpTestSucceeded: True` cho biết cổng TCP có thể truy cập, chưa xác nhận hoàn tất bắt tay LANDrop. Nếu kết quả là `False`, kiểm tra IP, tiến trình đang chạy, cổng, firewall và tính năng cô lập thiết bị của mạng Wi-Fi.

File nhận mặc định nằm trong thư mục `Downloads\LANDrop` bên dưới thư mục home của người dùng Java, thường là `C:\Users\<ten-nguoi-dung>\Downloads\LANDrop`. Có thể thêm `--download-dir=C:/LANDrop/PeerA` vào bên trong chuỗi `-Djavafx.args` để chọn thư mục riêng. Khi demo nhiều peer trên cùng máy, dùng các thư mục khác nhau để dễ phân biệt file của từng peer.

Đóng cửa sổ ứng dụng để dừng peer. Với Discovery Server, nhấn `Ctrl+C` tại terminal và xác nhận dừng batch job nếu CMD yêu cầu.

## Cấu hình peer và chạy trên nhiều máy LAN

Các tham số desktop được đọc trong [MainApp.java](frontend/src/main/java/com/landrop/ui/MainApp.java) và truyền qua `-Djavafx.args`:

| Tham số | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `--display-name` | `Peer A` | Tên hiển thị của peer |
| `--peer-port` | `5001` | Cổng TCP peer lắng nghe |
| `--discovery-host` | `127.0.0.1` | Địa chỉ máy chạy Discovery Server |
| `--discovery-port` | `8888` | Cổng Discovery Server |
| `--advertised-host` | `127.0.0.1` | Địa chỉ của peer mà các máy khác sẽ kết nối đến |
| `--auto-connect` | `false` | Tự đăng ký với Discovery khi mở ứng dụng |
| `--download-dir` | Thư mục `Downloads/LANDrop` của người dùng | Thư mục nhận file |

Ví dụ Discovery chạy tại `192.168.1.10`, còn máy peer có IP LAN `192.168.1.20`:

```sh
mvn -pl frontend javafx:run -Djavafx.args="--display-name=PeerA --peer-port=5001 --discovery-host=192.168.1.10 --discovery-port=8888 --advertised-host=192.168.1.20 --auto-connect=true"
```

Thay hai địa chỉ bằng IP thực tế. Các máy dùng chung địa chỉ Discovery nhưng mỗi peer quảng bá IP của chính mình. Firewall cần cho phép cổng Discovery và cổng peer đã chọn. Nếu mở nhiều peer trên cùng một máy, mỗi peer phải dùng một cổng khác nhau; có thể đặt `--download-dir` riêng cho từng instance.

Core còn có [AppConfig.java](backend/src/main/java/com/landrop/config/AppConfig.java) để đọc các cấu hình được dịch vụ sử dụng theo thứ tự system property → biến môi trường → giá trị mặc định. Với ứng dụng desktop, sử dụng các tham số trong bảng trên; không giả định mọi khóa trong `AppConfig` tự động thay đổi cấu hình JavaFX.

## Nền tảng dữ liệu frontend — Phase 14B

Phase 14B chuẩn bị frontend cho API lưu trữ dữ liệu lâu dài, gồm:

- **7 domain model:** `User`, `Workspace`, `WorkspaceMember`, `Channel`, `Message`, `File`, `Folder`.
- **DTO và mapper:** kiểm tra dữ liệu JSON, chuyển timestamp sang `Instant` và kích thước file từ chuỗi thập phân sang `BigInteger`.
- **API client bất đồng bộ:** hỗ trợ HTTP và mock qua cùng một giao diện, có phân trang và xử lý lỗi.
- **Mock data:** 3 users, 1 workspace, 3 members, 3 channels, 4 messages, 2 folders và 3 bản ghi metadata file.

Chọn nguồn dữ liệu tại nơi khởi tạo client:

```java
import com.landrop.ui.cloud.api.CloudApi;
import java.net.URI;

CloudApi api = CloudApi.mock();

// Khi backend triển khai API tương thích, thay dòng trên bằng:
// CloudApi api = CloudApi.http(URI.create("http://localhost:3000/api/"));
```

Địa chỉ HTTP trên chỉ là ví dụ cấu hình, không phải server có sẵn trong repository. Client không tự chuyển sang mock nếu HTTP lỗi. Khi cập nhật giao diện từ kết quả API, dùng `Platform.runLater` và tránh gọi `join()` trên JavaFX Application Thread.

Các route GET được đề xuất:

```text
/api/users/:id
/api/workspaces
/api/workspaces/:workspaceId
/api/workspaces/:workspaceId/members
/api/workspaces/:workspaceId/channels
/api/channels/:channelId/messages
/api/workspaces/:workspaceId/files
/api/workspaces/:workspaceId/folders
```

Xem [hướng dẫn frontend](frontend/README.md), [hợp đồng API](docs/PHASE-14B-API-CONTRACT.md) và [báo cáo bàn giao Phase 14B](docs/PHASE-14B-DELIVERY.md). Phase này chưa bổ sung màn hình quản lý workspace hoặc lưu lịch sử chat LAN vào database.

Authentication frontend Phase 15B được mô tả trong [tài liệu auth](docs/PHASE-15B-AUTH.md). Ứng dụng mở loading scene, khôi phục refresh token và gọi `/auth/me` trước khi hiển thị setup hoặc Core application. Nếu chưa đăng nhập, người dùng được đưa tới Login; Register, logout và retry sau access-token expiry nằm trong cùng auth state service.

## Kiểm thử

Chạy toàn bộ test:

```sh
mvn test -DargLine=--enable-native-access=ALL-UNNAMED
```

Build kèm kiểm thử và đóng gói:

```sh
mvn clean package -DargLine=--enable-native-access=ALL-UNNAMED
```

Kiểm tra ví dụ mock Phase 14B mà không khởi động giao diện hoặc mở kết nối mạng:

```sh
mvn -pl frontend -am test -Dtest=CloudDataExampleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Kết quả xác minh gần nhất trong phiên bàn giao Phase 14B ngày **13/09/2026**:

| Nhóm | Số test đã qua |
| --- | ---: |
| Backend/Core | 170 |
| Frontend Core: JavaFX/FXML và dịch vụ trạng thái | 12 |
| Frontend Phase 14B: mapper, mock, HTTP client và ví dụ dữ liệu | 26 |
| **Tổng** | **208** |

Lần chạy đó có **0 failures, 0 errors, 0 skipped**. Đây là kết quả test đã ghi nhận, không phải số đo phần trăm bao phủ mã nguồn. Báo cáo từng suite nằm trong `backend/target/surefire-reports/` và `frontend/target/surefire-reports/` sau khi chạy test.

Các test tích hợp cần quyền mở socket localhost; test JavaFX cần phiên đồ họa. Bộ test không yêu cầu Neon/PostgreSQL đang chạy. Chi tiết môi trường, lệnh đã dùng và giới hạn xác minh được ghi trong [báo cáo Phase 14B](docs/PHASE-14B-DELIVERY.md).

## Demo swarming và hiệu năng

Để thử tải từ nhiều nguồn, khởi động Discovery cùng các Peer A/B/C/D, chuẩn bị cùng một file ở các nguồn chia sẻ, rồi yêu cầu tải từ peer nhận. Trong quá trình tải có thể bổ sung nguồn hoặc ngắt một nguồn để quan sát phân phối chunk và failover.

[Hướng dẫn demo](docs/DEMO.md) mô tả các kịch bản discovery, chat, truyền file, swarming và resume. [Tài liệu hiệu năng](docs/PERFORMANCE.md) ghi kết quả benchmark Core trên loopback; các số liệu đó không đại diện cho tốc độ Wi-Fi, LAN thực tế hoặc Internet.

## Xử lý lỗi thường gặp

| Hiện tượng | Cách kiểm tra |
| --- | --- |
| Không tìm thấy artifact `com.landrop:landrop` khi chạy frontend | Chạy `mvn install -DskipTests` tại thư mục gốc để cài backend và parent POM vào Maven local repository |
| `Address already in use` | Chọn cổng peer khác qua `--peer-port`; nếu đổi cổng Discovery, cập nhật `--discovery-port` ở các peer |
| Không kết nối được Discovery | Kiểm tra server đã chạy, địa chỉ `--discovery-host`, cổng và firewall |
| Thấy peer nhưng không kết nối được | Kiểm tra `--advertised-host` là IP truy cập được từ máy nhận; localhost chỉ dùng khi các peer ở cùng một máy |
| Test báo `Operation not permitted` khi mở socket | Chạy test trong môi trường cho phép socket localhost; lỗi sandbox cần được phân biệt với lỗi ứng dụng |
| Test JavaFX không khởi tạo được toolkit/display | Chạy trong phiên desktop có hỗ trợ JavaFX |
| Không tải được dependency khi dùng `-o` | Bỏ `-o` để Maven tải dependency còn thiếu |
| SHA-256 không khớp | Kiểm tra các nguồn cung cấp cùng phiên bản file và file không bị thay đổi trong lúc truyền |

## Phạm vi hiện tại

Core tập trung vào giao tiếp trong mạng LAN. SHA-256 dùng để kiểm tra toàn vẹn file; Core chưa triển khai xác thực người dùng, mã hóa đường truyền hoặc NAT traversal để hoạt động qua Internet.

Repository đã có login/register frontend và session handling Phase 15B. Backend có Auth HTTP, WebSocket realtime và Phase 16A workspace/member API; các test PostgreSQL dùng schema tạm để không ảnh hưởng dữ liệu thật. Frontend chưa triển khai giao diện quản lý workspace, task, notification, search, activity log hoặc production deployment. Những nội dung trong [roadmap](docs/PRODUCTION_ROADMAP.md) là định hướng phát triển, không phải tính năng đã có.

## Tài liệu tham khảo trong project

| Tài liệu | Nội dung |
| --- | --- |
| [Frontend](frontend/README.md) | Cấu trúc JavaFX và cách sử dụng lớp dữ liệu Phase 14B |
| [Kiến trúc](docs/ARCHITECTURE.md) | Thành phần Core và luồng xử lý |
| [Giao thức](docs/PROTOCOL.md) | Packet, framing và loại thông điệp |
| [Demo](docs/DEMO.md) | Kịch bản trình diễn nhiều peer |
| [Kế hoạch kiểm thử Core](docs/TEST_PLAN.md) | Ma trận kiểm thử baseline Phase 1–13 |
| [Hiệu năng](docs/PERFORMANCE.md) | Benchmark và điều kiện đo |
| [Core baseline](docs/CORE_BASELINE.md) | Trạng thái hoàn thành và ranh giới bảo toàn Core |
| [API contract Phase 14B](docs/PHASE-14B-API-CONTRACT.md) | Domain, DTO, route, phân trang và lỗi |
| [Bàn giao Phase 14B](docs/PHASE-14B-DELIVERY.md) | File thay đổi, kết quả xác minh và lệnh đã chạy |
| [Authentication Phase 15B](docs/PHASE-15B-AUTH.md) | Auth API shape, session state, UI flow, refresh và phạm vi |
| [Workspace Phase 16A](docs/PHASE-16A-API-CONTRACT.md) | Workspace/member routes, role rules, validation và transaction behavior |
| [Roadmap](docs/PRODUCTION_ROADMAP.md) | Các hướng phát triển sau Core |
