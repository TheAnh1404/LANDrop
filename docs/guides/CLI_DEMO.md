# Demo LAN bằng backend CLI

Demo dùng engine Java TCP, JDK 21 và Maven 3.9+. Chạy từ thư mục gốc repository.

```powershell
mvn -pl backend -am package -DskipTests
```

Mở ba terminal riêng. Terminal 1:

```powershell
mvn -pl backend exec:java "-Dexec.mainClass=com.landrop.discovery.DiscoveryServer" "-Dexec.args=8888"
```

Có thể dùng `.\scripts\demo\start-discovery.bat` hoặc `bash scripts/demo/start-discovery.sh`.

Terminal 2:

```powershell
mvn -pl backend exec:java "-Dexec.mainClass=com.landrop.network.Main" "-Dexec.args=peer 5001 localhost 8888 Alice"
```

Terminal 3:

```powershell
mvn -pl backend exec:java "-Dexec.mainClass=com.landrop.network.Main" "-Dexec.args=peer 5002 localhost 8888 Bob"
```

Dùng `/help` xem lệnh CLI, `/peers` liệt kê peer và `/connect <peerId>` để kết nối. Trên hai máy, thay `localhost` bằng IP discovery và truyền advertised host của từng máy như tham số cuối: `peer ... Alice 192.168.1.20`.

Chat có tài khoản dùng [MULTI_DEVICE](MULTI_DEVICE.md). `scripts/demo/start-auth.*` chạy `AuthHttpServer`, còn `npm run server:backend` chạy `LandropServerMain`; chọn entrypoint phù hợp hợp đồng cần kiểm tra.

Discovery dùng cổng 8888, peer ví dụ dùng 5001/5002. Tests TCP nằm dưới `backend/src/test/java/com/landrop/transfer/`. WebRTC có implementation riêng và cần nghiệm thu giữa trình duyệt thật.
