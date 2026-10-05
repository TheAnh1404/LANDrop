# LANDrop - Danh mục toàn bộ tệp backend

Snapshot 05/10/2026. Có 258 tệp, loại trừ thư mục build `target`. Đây là danh mục và chỉ mục cấu trúc; nội dung phân tích nghiệp vụ nằm trong báo cáo chính. SHA-256 đầy đủ nằm trong CSV đi kèm.

## Cấu hình/tài nguyên: backend (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [pom.xml](../../backend/pom.xml) | 117 | XML |
| [README.md](../../backend/README.md) | 40 | MD |

## Cấu hình/tài nguyên: backend/src/main/resources/admin (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [index.html](../../backend/src/main/resources/admin/index.html) | 1178 | HTML |

## Cấu hình/tài nguyên: backend/src/main/resources/db/migration (7 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [V1__init_cloud_database_phase14.sql](../../backend/src/main/resources/db/migration/V1__init_cloud_database_phase14.sql) | 125 | SQL |
| [V2__auth_sessions_phase15.sql](../../backend/src/main/resources/db/migration/V2__auth_sessions_phase15.sql) | 24 | SQL |
| [V3__realtime_message_identity.sql](../../backend/src/main/resources/db/migration/V3__realtime_message_identity.sql) | 4 | SQL |
| [V4__persistent_chat_phase17.sql](../../backend/src/main/resources/db/migration/V4__persistent_chat_phase17.sql) | 14 | SQL |
| [V5__file_management_phase18.sql](../../backend/src/main/resources/db/migration/V5__file_management_phase18.sql) | 62 | SQL |
| [V6__server_control_center_phase03.sql](../../backend/src/main/resources/db/migration/V6__server_control_center_phase03.sql) | 12 | SQL |
| [V7__authentication_device_enrollment_phase04.sql](../../backend/src/main/resources/db/migration/V7__authentication_device_enrollment_phase04.sql) | 26 | SQL |

## Kiểm thử: com.landrop.admin (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AdminControlCenterTest.java](../../backend/src/test/java/com/landrop/admin/AdminControlCenterTest.java) | 669 | 11 @Test |

## Kiểm thử: com.landrop.auth (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AuthIntegrationTest.java](../../backend/src/test/java/com/landrop/auth/AuthIntegrationTest.java) | 494 | 11 @Test |
| [Foundation04AuthenticationDeviceTest.java](../../backend/src/test/java/com/landrop/auth/Foundation04AuthenticationDeviceTest.java) | 567 | 13 @Test |
| [Foundation04HttpContractTest.java](../../backend/src/test/java/com/landrop/auth/Foundation04HttpContractTest.java) | 311 | 4 @Test |

## Kiểm thử: com.landrop.auth.security (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [JwtServiceTest.java](../../backend/src/test/java/com/landrop/auth/security/JwtServiceTest.java) | 112 | 6 @Test |
| [PasswordHasherTest.java](../../backend/src/test/java/com/landrop/auth/security/PasswordHasherTest.java) | 69 | 6 @Test |
| [SessionVerifierTest.java](../../backend/src/test/java/com/landrop/auth/security/SessionVerifierTest.java) | 75 | 2 @Test |

## Kiểm thử: com.landrop.benchmark (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [PerformanceBaselineTest.java](../../backend/src/test/java/com/landrop/benchmark/PerformanceBaselineTest.java) | 332 | 2 @Test |

## Kiểm thử: com.landrop.chat (7 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChatCliTest.java](../../backend/src/test/java/com/landrop/chat/ChatCliTest.java) | 86 | 1 @Test |
| [ChatHistoryTest.java](../../backend/src/test/java/com/landrop/chat/ChatHistoryTest.java) | 71 | 2 @Test |
| [ChatIntegrationTest.java](../../backend/src/test/java/com/landrop/chat/ChatIntegrationTest.java) | 129 | 1 @Test |
| [ChatMessageTest.java](../../backend/src/test/java/com/landrop/chat/ChatMessageTest.java) | 61 | 4 @Test |
| [ChatServiceTest.java](../../backend/src/test/java/com/landrop/chat/ChatServiceTest.java) | 249 | 8 @Test |
| [PersistentChatPostgresTest.java](../../backend/src/test/java/com/landrop/chat/PersistentChatPostgresTest.java) | 490 | 9 @Test |
| [PersistentChatTest.java](../../backend/src/test/java/com/landrop/chat/PersistentChatTest.java) | 600 | 8 @Test |

## Kiểm thử: com.landrop.config (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AppConfigTest.java](../../backend/src/test/java/com/landrop/config/AppConfigTest.java) | 69 | 3 @Test |

## Kiểm thử: com.landrop.database (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DatabaseIntegrationTest.java](../../backend/src/test/java/com/landrop/database/DatabaseIntegrationTest.java) | 238 | 10 @Test |

## Kiểm thử: com.landrop.discovery (5 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DiscoveryIntegrationTest.java](../../backend/src/test/java/com/landrop/discovery/DiscoveryIntegrationTest.java) | 156 | 2 @Test |
| [PeerInfoTest.java](../../backend/src/test/java/com/landrop/discovery/PeerInfoTest.java) | 55 | 2 @Test |
| [PeerNodeTest.java](../../backend/src/test/java/com/landrop/discovery/PeerNodeTest.java) | 54 | 1 @Test |
| [PeerRegistryTest.java](../../backend/src/test/java/com/landrop/discovery/PeerRegistryTest.java) | 166 | 8 @Test |
| [UdpDiscoveryServiceTest.java](../../backend/src/test/java/com/landrop/discovery/UdpDiscoveryServiceTest.java) | 63 | 1 @Test |

## Kiểm thử: com.landrop.file (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [FileManagementPostgresTest.java](../../backend/src/test/java/com/landrop/file/FileManagementPostgresTest.java) | 595 | 9 @Test |
| [FileManagementTest.java](../../backend/src/test/java/com/landrop/file/FileManagementTest.java) | 642 | 9 @Test |

## Kiểm thử: com.landrop.network (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [PeerNetworkTest.java](../../backend/src/test/java/com/landrop/network/PeerNetworkTest.java) | 194 | 6 @Test |

## Kiểm thử: com.landrop.peer (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [PeerConnectionEncryptionIntegrationTest.java](../../backend/src/test/java/com/landrop/peer/PeerConnectionEncryptionIntegrationTest.java) | 114 | 2 @Test |
| [PeerConnectionTest.java](../../backend/src/test/java/com/landrop/peer/PeerConnectionTest.java) | 165 | 3 @Test |

## Kiểm thử: com.landrop.protocol (5 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ClipboardMessageTest.java](../../backend/src/test/java/com/landrop/protocol/ClipboardMessageTest.java) | 34 | 2 @Test |
| [PacketDecoderTest.java](../../backend/src/test/java/com/landrop/protocol/PacketDecoderTest.java) | 193 | 10 @Test |
| [PacketEncoderTest.java](../../backend/src/test/java/com/landrop/protocol/PacketEncoderTest.java) | 67 | 3 @Test |
| [PacketTest.java](../../backend/src/test/java/com/landrop/protocol/PacketTest.java) | 111 | 8 @Test |
| [RequestIdGeneratorTest.java](../../backend/src/test/java/com/landrop/protocol/RequestIdGeneratorTest.java) | 53 | 2 @Test |

## Kiểm thử: com.landrop.realtime (4 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ConnectionRegistryTest.java](../../backend/src/test/java/com/landrop/realtime/ConnectionRegistryTest.java) | 28 | 1 @Test |
| [RealtimeIntegrationTest.java](../../backend/src/test/java/com/landrop/realtime/RealtimeIntegrationTest.java) | 125 | 6 @Test |
| [RealtimePostgresTest.java](../../backend/src/test/java/com/landrop/realtime/RealtimePostgresTest.java) | 221 | 3 @Test |
| [WsProbe.java](../../backend/src/test/java/com/landrop/realtime/WsProbe.java) | 167 | JAVA |

## Kiểm thử: com.landrop.security (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [P2pCryptoSessionTest.java](../../backend/src/test/java/com/landrop/security/P2pCryptoSessionTest.java) | 71 | 3 @Test |

## Kiểm thử: com.landrop.server (4 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [Foundation04RealtimeBindingTest.java](../../backend/src/test/java/com/landrop/server/Foundation04RealtimeBindingTest.java) | 155 | 2 @Test |
| [FoundationWsClient.java](../../backend/src/test/java/com/landrop/server/FoundationWsClient.java) | 100 | JAVA |
| [LandropServerFoundationTest.java](../../backend/src/test/java/com/landrop/server/LandropServerFoundationTest.java) | 531 | 16 @Test |
| [LanIntegrationGateTest.java](../../backend/src/test/java/com/landrop/server/LanIntegrationGateTest.java) | 469 | 7 @Test |

## Kiểm thử: com.landrop.server.bootstrap (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ProductionAdminPasswordTest.java](../../backend/src/test/java/com/landrop/server/bootstrap/ProductionAdminPasswordTest.java) | 15 | 1 @Test |

## Kiểm thử: com.landrop.server.clients (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ClientRegistryAuthenticationTest.java](../../backend/src/test/java/com/landrop/server/clients/ClientRegistryAuthenticationTest.java) | 80 | 2 @Test |

## Kiểm thử: com.landrop.transfer (21 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChunkManagerTest.java](../../backend/src/test/java/com/landrop/transfer/ChunkManagerTest.java) | 243 | 9 @Test |
| [ChunkMessagesTest.java](../../backend/src/test/java/com/landrop/transfer/ChunkMessagesTest.java) | 135 | 6 @Test |
| [ChunkSchedulerTest.java](../../backend/src/test/java/com/landrop/transfer/ChunkSchedulerTest.java) | 142 | 4 @Test |
| [FileChunkTest.java](../../backend/src/test/java/com/landrop/transfer/FileChunkTest.java) | 95 | 4 @Test |
| [FileDataChunkTest.java](../../backend/src/test/java/com/landrop/transfer/FileDataChunkTest.java) | 61 | 4 @Test |
| [FileQueryMessageTest.java](../../backend/src/test/java/com/landrop/transfer/FileQueryMessageTest.java) | 108 | 4 @Test |
| [FileTransferIntegrationTest.java](../../backend/src/test/java/com/landrop/transfer/FileTransferIntegrationTest.java) | 463 | 6 @Test |
| [FileTransferMessagesTest.java](../../backend/src/test/java/com/landrop/transfer/FileTransferMessagesTest.java) | 55 | 3 @Test |
| [FileTransferResumeIntegrationTest.java](../../backend/src/test/java/com/landrop/transfer/FileTransferResumeIntegrationTest.java) | 495 | 6 @Test |
| [FileTransferStateTest.java](../../backend/src/test/java/com/landrop/transfer/FileTransferStateTest.java) | 166 | 4 @Test |
| [FileTransferUtilsTest.java](../../backend/src/test/java/com/landrop/transfer/FileTransferUtilsTest.java) | 87 | 9 @Test |
| [FolderArchiveServiceTest.java](../../backend/src/test/java/com/landrop/transfer/FolderArchiveServiceTest.java) | 91 | 4 @Test |
| [LargeFilePeerToPeerIntegrationTest.java](../../backend/src/test/java/com/landrop/transfer/LargeFilePeerToPeerIntegrationTest.java) | 223 | 2 @Test |
| [MultiPeerDownloadIntegrationTest.java](../../backend/src/test/java/com/landrop/transfer/MultiPeerDownloadIntegrationTest.java) | 507 | 8 @Test |
| [MultiPeerDownloadTest.java](../../backend/src/test/java/com/landrop/transfer/MultiPeerDownloadTest.java) | 162 | 4 @Test |
| [PeerDownloadStateTest.java](../../backend/src/test/java/com/landrop/transfer/PeerDownloadStateTest.java) | 66 | 3 @Test |
| [Phase10SystemValidationIntegrationTest.java](../../backend/src/test/java/com/landrop/transfer/Phase10SystemValidationIntegrationTest.java) | 394 | 6 @Test |
| [Phase91HardeningIntegrationTest.java](../../backend/src/test/java/com/landrop/transfer/Phase91HardeningIntegrationTest.java) | 436 | 8 @Test |
| [ResumeMessagesTest.java](../../backend/src/test/java/com/landrop/transfer/ResumeMessagesTest.java) | 131 | 3 @Test |
| [Sha256Test.java](../../backend/src/test/java/com/landrop/transfer/Sha256Test.java) | 108 | 7 @Test |
| [SharedFileScannerTest.java](../../backend/src/test/java/com/landrop/transfer/SharedFileScannerTest.java) | 131 | 6 @Test |

## Kiểm thử: com.landrop.util (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AppLoggerTest.java](../../backend/src/test/java/com/landrop/util/AppLoggerTest.java) | 45 | 2 @Test |

## Kiểm thử: com.landrop.workspace (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [WorkspacePostgresTest.java](../../backend/src/test/java/com/landrop/workspace/WorkspacePostgresTest.java) | 118 | 3 @Test |

## Mã nguồn: com.landrop.admin.dto (7 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AdminClientDto.java](../../backend/src/main/java/com/landrop/admin/dto/AdminClientDto.java) | 54 | JAVA |
| [AdminDashboardDto.java](../../backend/src/main/java/com/landrop/admin/dto/AdminDashboardDto.java) | 27 | JAVA |
| [AdminSessionDto.java](../../backend/src/main/java/com/landrop/admin/dto/AdminSessionDto.java) | 56 | JAVA |
| [AdminUserDto.java](../../backend/src/main/java/com/landrop/admin/dto/AdminUserDto.java) | 42 | JAVA |
| [AdminUserIdentityDto.java](../../backend/src/main/java/com/landrop/admin/dto/AdminUserIdentityDto.java) | 13 | JAVA |
| [AdminWorkspaceDto.java](../../backend/src/main/java/com/landrop/admin/dto/AdminWorkspaceDto.java) | 24 | JAVA |
| [AdminWorkspaceMemberDto.java](../../backend/src/main/java/com/landrop/admin/dto/AdminWorkspaceMemberDto.java) | 20 | JAVA |

## Mã nguồn: com.landrop.admin.logging (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [OperationalLogEntry.java](../../backend/src/main/java/com/landrop/admin/logging/OperationalLogEntry.java) | 21 | JAVA |
| [OperationalLogService.java](../../backend/src/main/java/com/landrop/admin/logging/OperationalLogService.java) | 124 | JAVA |

## Mã nguồn: com.landrop.admin.security (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AdminAuthMiddleware.java](../../backend/src/main/java/com/landrop/admin/security/AdminAuthMiddleware.java) | 99 | JAVA |
| [AdminBootstrapService.java](../../backend/src/main/java/com/landrop/admin/security/AdminBootstrapService.java) | 180 | JAVA |
| [AdminPasswordResetMain.java](../../backend/src/main/java/com/landrop/admin/security/AdminPasswordResetMain.java) | 10 | JAVA |

## Mã nguồn: com.landrop.admin.server (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AdminHttpServer.java](../../backend/src/main/java/com/landrop/admin/server/AdminHttpServer.java) | 711 | JAVA |

## Mã nguồn: com.landrop.admin.service (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AdminControlCenterService.java](../../backend/src/main/java/com/landrop/admin/service/AdminControlCenterService.java) | 595 | JAVA |

## Mã nguồn: com.landrop.auth.config (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AuthConfig.java](../../backend/src/main/java/com/landrop/auth/config/AuthConfig.java) | 146 | JAVA |

## Mã nguồn: com.landrop.auth.dto (7 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AuthResponse.java](../../backend/src/main/java/com/landrop/auth/dto/AuthResponse.java) | 16 | JAVA |
| [ErrorResponse.java](../../backend/src/main/java/com/landrop/auth/dto/ErrorResponse.java) | 27 | JAVA |
| [LoginRequest.java](../../backend/src/main/java/com/landrop/auth/dto/LoginRequest.java) | 55 | JAVA |
| [LogoutRequest.java](../../backend/src/main/java/com/landrop/auth/dto/LogoutRequest.java) | 11 | JAVA |
| [RefreshRequest.java](../../backend/src/main/java/com/landrop/auth/dto/RefreshRequest.java) | 18 | JAVA |
| [RegisterRequest.java](../../backend/src/main/java/com/landrop/auth/dto/RegisterRequest.java) | 45 | JAVA |
| [UserDto.java](../../backend/src/main/java/com/landrop/auth/dto/UserDto.java) | 38 | JAVA |

## Mã nguồn: com.landrop.auth.exception (6 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AuthException.java](../../backend/src/main/java/com/landrop/auth/exception/AuthException.java) | 38 | JAVA |
| [ConflictException.java](../../backend/src/main/java/com/landrop/auth/exception/ConflictException.java) | 17 | JAVA |
| [ForbiddenException.java](../../backend/src/main/java/com/landrop/auth/exception/ForbiddenException.java) | 17 | JAVA |
| [NotFoundException.java](../../backend/src/main/java/com/landrop/auth/exception/NotFoundException.java) | 17 | JAVA |
| [UnauthorizedException.java](../../backend/src/main/java/com/landrop/auth/exception/UnauthorizedException.java) | 17 | JAVA |
| [ValidationException.java](../../backend/src/main/java/com/landrop/auth/exception/ValidationException.java) | 17 | JAVA |

## Mã nguồn: com.landrop.auth.model (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AuthErrorCode.java](../../backend/src/main/java/com/landrop/auth/model/AuthErrorCode.java) | 27 | JAVA |
| [RefreshToken.java](../../backend/src/main/java/com/landrop/auth/model/RefreshToken.java) | 55 | JAVA |

## Mã nguồn: com.landrop.auth.repository (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [RefreshTokenRepository.java](../../backend/src/main/java/com/landrop/auth/repository/RefreshTokenRepository.java) | 313 | JAVA |

## Mã nguồn: com.landrop.auth.security (4 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [JwtClaims.java](../../backend/src/main/java/com/landrop/auth/security/JwtClaims.java) | 36 | JAVA |
| [JwtService.java](../../backend/src/main/java/com/landrop/auth/security/JwtService.java) | 217 | JAVA |
| [PasswordHasher.java](../../backend/src/main/java/com/landrop/auth/security/PasswordHasher.java) | 87 | JAVA |
| [SessionVerifier.java](../../backend/src/main/java/com/landrop/auth/security/SessionVerifier.java) | 49 | JAVA |

## Mã nguồn: com.landrop.auth.server (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AuthHttpHandler.java](../../backend/src/main/java/com/landrop/auth/server/AuthHttpHandler.java) | 253 | JAVA |
| [AuthHttpServer.java](../../backend/src/main/java/com/landrop/auth/server/AuthHttpServer.java) | 451 | JAVA |
| [AuthMiddleware.java](../../backend/src/main/java/com/landrop/auth/server/AuthMiddleware.java) | 91 | JAVA |

## Mã nguồn: com.landrop.auth.service (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AuthService.java](../../backend/src/main/java/com/landrop/auth/service/AuthService.java) | 303 | JAVA |

## Mã nguồn: com.landrop.chat (5 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChatCli.java](../../backend/src/main/java/com/landrop/chat/ChatCli.java) | 224 | JAVA |
| [ChatHistory.java](../../backend/src/main/java/com/landrop/chat/ChatHistory.java) | 50 | JAVA |
| [ChatListener.java](../../backend/src/main/java/com/landrop/chat/ChatListener.java) | 41 | JAVA |
| [ChatMessage.java](../../backend/src/main/java/com/landrop/chat/ChatMessage.java) | 61 | JAVA |
| [ChatService.java](../../backend/src/main/java/com/landrop/chat/ChatService.java) | 244 | JAVA |

## Mã nguồn: com.landrop.chat.dto (4 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChannelDto.java](../../backend/src/main/java/com/landrop/chat/dto/ChannelDto.java) | 21 | JAVA |
| [ChatMessageDto.java](../../backend/src/main/java/com/landrop/chat/dto/ChatMessageDto.java) | 21 | JAVA |
| [MessagesPageDto.java](../../backend/src/main/java/com/landrop/chat/dto/MessagesPageDto.java) | 21 | JAVA |
| [SenderDto.java](../../backend/src/main/java/com/landrop/chat/dto/SenderDto.java) | 14 | JAVA |

## Mã nguồn: com.landrop.chat.server (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChatHttpServer.java](../../backend/src/main/java/com/landrop/chat/server/ChatHttpServer.java) | 327 | JAVA |

## Mã nguồn: com.landrop.chat.service (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChannelBroadcaster.java](../../backend/src/main/java/com/landrop/chat/service/ChannelBroadcaster.java) | 11 | JAVA |
| [ChannelChatService.java](../../backend/src/main/java/com/landrop/chat/service/ChannelChatService.java) | 691 | JAVA |
| [ChatException.java](../../backend/src/main/java/com/landrop/chat/service/ChatException.java) | 23 | JAVA |

## Mã nguồn: com.landrop.config (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AppConfig.java](../../backend/src/main/java/com/landrop/config/AppConfig.java) | 182 | JAVA |

## Mã nguồn: com.landrop.database (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DatabaseCli.java](../../backend/src/main/java/com/landrop/database/DatabaseCli.java) | 69 | JAVA |
| [DatabaseConfig.java](../../backend/src/main/java/com/landrop/database/DatabaseConfig.java) | 97 | JAVA |
| [DatabaseManager.java](../../backend/src/main/java/com/landrop/database/DatabaseManager.java) | 181 | JAVA |

## Mã nguồn: com.landrop.database.migration (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DatabaseMigrator.java](../../backend/src/main/java/com/landrop/database/migration/DatabaseMigrator.java) | 222 | JAVA |

## Mã nguồn: com.landrop.database.model (10 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [Channel.java](../../backend/src/main/java/com/landrop/database/model/Channel.java) | 21 | JAVA |
| [Device.java](../../backend/src/main/java/com/landrop/database/model/Device.java) | 69 | JAVA |
| [FileRecord.java](../../backend/src/main/java/com/landrop/database/model/FileRecord.java) | 52 | JAVA |
| [Folder.java](../../backend/src/main/java/com/landrop/database/model/Folder.java) | 38 | JAVA |
| [Message.java](../../backend/src/main/java/com/landrop/database/model/Message.java) | 48 | JAVA |
| [TransferRecord.java](../../backend/src/main/java/com/landrop/database/model/TransferRecord.java) | 27 | JAVA |
| [User.java](../../backend/src/main/java/com/landrop/database/model/User.java) | 71 | JAVA |
| [Workspace.java](../../backend/src/main/java/com/landrop/database/model/Workspace.java) | 21 | JAVA |
| [WorkspaceMember.java](../../backend/src/main/java/com/landrop/database/model/WorkspaceMember.java) | 20 | JAVA |
| [WorkspaceRole.java](../../backend/src/main/java/com/landrop/database/model/WorkspaceRole.java) | 22 | JAVA |

## Mã nguồn: com.landrop.database.repository (8 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChannelRepository.java](../../backend/src/main/java/com/landrop/database/repository/ChannelRepository.java) | 129 | JAVA |
| [DeviceRepository.java](../../backend/src/main/java/com/landrop/database/repository/DeviceRepository.java) | 271 | JAVA |
| [FileRepository.java](../../backend/src/main/java/com/landrop/database/repository/FileRepository.java) | 246 | JAVA |
| [FolderRepository.java](../../backend/src/main/java/com/landrop/database/repository/FolderRepository.java) | 229 | JAVA |
| [MessageRepository.java](../../backend/src/main/java/com/landrop/database/repository/MessageRepository.java) | 233 | JAVA |
| [TransferRepository.java](../../backend/src/main/java/com/landrop/database/repository/TransferRepository.java) | 151 | JAVA |
| [UserRepository.java](../../backend/src/main/java/com/landrop/database/repository/UserRepository.java) | 292 | JAVA |
| [WorkspaceRepository.java](../../backend/src/main/java/com/landrop/database/repository/WorkspaceRepository.java) | 149 | JAVA |

## Mã nguồn: com.landrop.database.seed (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DatabaseSeeder.java](../../backend/src/main/java/com/landrop/database/seed/DatabaseSeeder.java) | 379 | JAVA |

## Mã nguồn: com.landrop.database.service (6 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChannelService.java](../../backend/src/main/java/com/landrop/database/service/ChannelService.java) | 49 | JAVA |
| [FileService.java](../../backend/src/main/java/com/landrop/database/service/FileService.java) | 74 | JAVA |
| [FolderService.java](../../backend/src/main/java/com/landrop/database/service/FolderService.java) | 45 | JAVA |
| [MessageService.java](../../backend/src/main/java/com/landrop/database/service/MessageService.java) | 56 | JAVA |
| [UserService.java](../../backend/src/main/java/com/landrop/database/service/UserService.java) | 53 | JAVA |
| [WorkspaceService.java](../../backend/src/main/java/com/landrop/database/service/WorkspaceService.java) | 84 | JAVA |

## Mã nguồn: com.landrop.device.dto (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DeviceDto.java](../../backend/src/main/java/com/landrop/device/dto/DeviceDto.java) | 47 | JAVA |
| [DeviceEnrollRequest.java](../../backend/src/main/java/com/landrop/device/dto/DeviceEnrollRequest.java) | 49 | JAVA |
| [DeviceRenameRequest.java](../../backend/src/main/java/com/landrop/device/dto/DeviceRenameRequest.java) | 26 | JAVA |

## Mã nguồn: com.landrop.device.server (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DeviceHttpHandler.java](../../backend/src/main/java/com/landrop/device/server/DeviceHttpHandler.java) | 216 | JAVA |

## Mã nguồn: com.landrop.device.service (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DeviceService.java](../../backend/src/main/java/com/landrop/device/service/DeviceService.java) | 191 | JAVA |

## Mã nguồn: com.landrop.discovery (8 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DiscoveryClient.java](../../backend/src/main/java/com/landrop/discovery/DiscoveryClient.java) | 284 | JAVA |
| [DiscoveryClientHandler.java](../../backend/src/main/java/com/landrop/discovery/DiscoveryClientHandler.java) | 223 | JAVA |
| [DiscoveryServer.java](../../backend/src/main/java/com/landrop/discovery/DiscoveryServer.java) | 196 | JAVA |
| [PeerInfo.java](../../backend/src/main/java/com/landrop/discovery/PeerInfo.java) | 152 | JAVA |
| [PeerNode.java](../../backend/src/main/java/com/landrop/discovery/PeerNode.java) | 499 | JAVA |
| [PeerRegistry.java](../../backend/src/main/java/com/landrop/discovery/PeerRegistry.java) | 189 | JAVA |
| [PeerStatus.java](../../backend/src/main/java/com/landrop/discovery/PeerStatus.java) | 9 | JAVA |
| [UdpDiscoveryService.java](../../backend/src/main/java/com/landrop/discovery/UdpDiscoveryService.java) | 302 | JAVA |

## Mã nguồn: com.landrop.file.dto (4 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [DeviceDto.java](../../backend/src/main/java/com/landrop/file/dto/DeviceDto.java) | 21 | JAVA |
| [FileDto.java](../../backend/src/main/java/com/landrop/file/dto/FileDto.java) | 31 | JAVA |
| [FolderDto.java](../../backend/src/main/java/com/landrop/file/dto/FolderDto.java) | 21 | JAVA |
| [TransferDto.java](../../backend/src/main/java/com/landrop/file/dto/TransferDto.java) | 22 | JAVA |

## Mã nguồn: com.landrop.file.exception (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [FileException.java](../../backend/src/main/java/com/landrop/file/exception/FileException.java) | 28 | JAVA |

## Mã nguồn: com.landrop.file.model (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [StorageType.java](../../backend/src/main/java/com/landrop/file/model/StorageType.java) | 31 | JAVA |
| [TransferStatus.java](../../backend/src/main/java/com/landrop/file/model/TransferStatus.java) | 28 | JAVA |

## Mã nguồn: com.landrop.file.server (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [FileHttpServer.java](../../backend/src/main/java/com/landrop/file/server/FileHttpServer.java) | 498 | JAVA |

## Mã nguồn: com.landrop.file.service (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [FileManagementService.java](../../backend/src/main/java/com/landrop/file/service/FileManagementService.java) | 337 | JAVA |
| [TransferBroadcaster.java](../../backend/src/main/java/com/landrop/file/service/TransferBroadcaster.java) | 11 | JAVA |
| [TransferCoordinationService.java](../../backend/src/main/java/com/landrop/file/service/TransferCoordinationService.java) | 378 | JAVA |

## Mã nguồn: com.landrop.network (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [Main.java](../../backend/src/main/java/com/landrop/network/Main.java) | 251 | JAVA |
| [PeerClient.java](../../backend/src/main/java/com/landrop/network/PeerClient.java) | 201 | JAVA |
| [PeerServer.java](../../backend/src/main/java/com/landrop/network/PeerServer.java) | 241 | JAVA |

## Mã nguồn: com.landrop.peer (6 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ConnectionListener.java](../../backend/src/main/java/com/landrop/peer/ConnectionListener.java) | 50 | JAVA |
| [ConnectionPurpose.java](../../backend/src/main/java/com/landrop/peer/ConnectionPurpose.java) | 18 | JAVA |
| [ConnectionState.java](../../backend/src/main/java/com/landrop/peer/ConnectionState.java) | 31 | JAVA |
| [PeerConnection.java](../../backend/src/main/java/com/landrop/peer/PeerConnection.java) | 687 | JAVA |
| [PeerIdentity.java](../../backend/src/main/java/com/landrop/peer/PeerIdentity.java) | 68 | JAVA |
| [PeerListener.java](../../backend/src/main/java/com/landrop/peer/PeerListener.java) | 60 | JAVA |

## Mã nguồn: com.landrop.protocol (8 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ClipboardMessage.java](../../backend/src/main/java/com/landrop/protocol/ClipboardMessage.java) | 76 | JAVA |
| [MessageType.java](../../backend/src/main/java/com/landrop/protocol/MessageType.java) | 88 | JAVA |
| [Packet.java](../../backend/src/main/java/com/landrop/protocol/Packet.java) | 158 | JAVA |
| [PacketDecoder.java](../../backend/src/main/java/com/landrop/protocol/PacketDecoder.java) | 133 | JAVA |
| [PacketEncoder.java](../../backend/src/main/java/com/landrop/protocol/PacketEncoder.java) | 62 | JAVA |
| [PacketHandler.java](../../backend/src/main/java/com/landrop/protocol/PacketHandler.java) | 16 | JAVA |
| [ProtocolException.java](../../backend/src/main/java/com/landrop/protocol/ProtocolException.java) | 18 | JAVA |
| [RequestIdGenerator.java](../../backend/src/main/java/com/landrop/protocol/RequestIdGenerator.java) | 45 | JAVA |

## Mã nguồn: com.landrop.realtime (7 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ConnectionRegistry.java](../../backend/src/main/java/com/landrop/realtime/ConnectionRegistry.java) | 71 | JAVA |
| [JdbcRealtimeStore.java](../../backend/src/main/java/com/landrop/realtime/JdbcRealtimeStore.java) | 316 | JAVA |
| [RealtimeException.java](../../backend/src/main/java/com/landrop/realtime/RealtimeException.java) | 8 | JAVA |
| [RealtimeServer.java](../../backend/src/main/java/com/landrop/realtime/RealtimeServer.java) | 410 | JAVA |
| [RealtimeStore.java](../../backend/src/main/java/com/landrop/realtime/RealtimeStore.java) | 60 | JAVA |
| [SocketAuthenticator.java](../../backend/src/main/java/com/landrop/realtime/SocketAuthenticator.java) | 20 | JAVA |
| [SocketPrincipal.java](../../backend/src/main/java/com/landrop/realtime/SocketPrincipal.java) | 9 | JAVA |

## Mã nguồn: com.landrop.realtime.cli (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [RealtimeClient.java](../../backend/src/main/java/com/landrop/realtime/cli/RealtimeClient.java) | 169 | JAVA |

## Mã nguồn: com.landrop.security (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [P2pCryptoSession.java](../../backend/src/main/java/com/landrop/security/P2pCryptoSession.java) | 147 | JAVA |

## Mã nguồn: com.landrop.server (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [LandropServerMain.java](../../backend/src/main/java/com/landrop/server/LandropServerMain.java) | 30 | JAVA |

## Mã nguồn: com.landrop.server.bootstrap (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ServerBootstrap.java](../../backend/src/main/java/com/landrop/server/bootstrap/ServerBootstrap.java) | 510 | JAVA |

## Mã nguồn: com.landrop.server.clients (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ClientConnection.java](../../backend/src/main/java/com/landrop/server/clients/ClientConnection.java) | 215 | JAVA |
| [ClientRegistry.java](../../backend/src/main/java/com/landrop/server/clients/ClientRegistry.java) | 286 | JAVA |
| [ClientStatus.java](../../backend/src/main/java/com/landrop/server/clients/ClientStatus.java) | 11 | JAVA |

## Mã nguồn: com.landrop.server.config (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ServerConfig.java](../../backend/src/main/java/com/landrop/server/config/ServerConfig.java) | 91 | JAVA |
| [ServerConfigManager.java](../../backend/src/main/java/com/landrop/server/config/ServerConfigManager.java) | 232 | JAVA |

## Mã nguồn: com.landrop.server.health (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [HealthService.java](../../backend/src/main/java/com/landrop/server/health/HealthService.java) | 69 | JAVA |

## Mã nguồn: com.landrop.server.http (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [FoundationHttpServer.java](../../backend/src/main/java/com/landrop/server/http/FoundationHttpServer.java) | 213 | JAVA |

## Mã nguồn: com.landrop.server.logging (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ServerEvent.java](../../backend/src/main/java/com/landrop/server/logging/ServerEvent.java) | 22 | JAVA |
| [ServerEventLogger.java](../../backend/src/main/java/com/landrop/server/logging/ServerEventLogger.java) | 50 | JAVA |

## Mã nguồn: com.landrop.server.network (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [NetworkInterfaceInspector.java](../../backend/src/main/java/com/landrop/server/network/NetworkInterfaceInspector.java) | 145 | JAVA |

## Mã nguồn: com.landrop.server.protocol (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [EventEnvelope.java](../../backend/src/main/java/com/landrop/server/protocol/EventEnvelope.java) | 148 | JAVA |
| [ProtocolConstants.java](../../backend/src/main/java/com/landrop/server/protocol/ProtocolConstants.java) | 85 | JAVA |

## Mã nguồn: com.landrop.server.realtime (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [FoundationRealtimeServer.java](../../backend/src/main/java/com/landrop/server/realtime/FoundationRealtimeServer.java) | 820 | JAVA |

## Mã nguồn: com.landrop.server.transfers (3 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [TransferCoordinator.java](../../backend/src/main/java/com/landrop/server/transfers/TransferCoordinator.java) | 189 | JAVA |
| [TransferRecord.java](../../backend/src/main/java/com/landrop/server/transfers/TransferRecord.java) | 38 | JAVA |
| [TransferStatus.java](../../backend/src/main/java/com/landrop/server/transfers/TransferStatus.java) | 17 | JAVA |

## Mã nguồn: com.landrop.transfer (18 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChunkManager.java](../../backend/src/main/java/com/landrop/transfer/ChunkManager.java) | 376 | JAVA |
| [ChunkScheduler.java](../../backend/src/main/java/com/landrop/transfer/ChunkScheduler.java) | 160 | JAVA |
| [ChunkState.java](../../backend/src/main/java/com/landrop/transfer/ChunkState.java) | 31 | JAVA |
| [FileChunk.java](../../backend/src/main/java/com/landrop/transfer/FileChunk.java) | 129 | JAVA |
| [FileTransfer.java](../../backend/src/main/java/com/landrop/transfer/FileTransfer.java) | 412 | JAVA |
| [FileTransferDirection.java](../../backend/src/main/java/com/landrop/transfer/FileTransferDirection.java) | 19 | JAVA |
| [FileTransferListener.java](../../backend/src/main/java/com/landrop/transfer/FileTransferListener.java) | 97 | JAVA |
| [FileTransferService.java](../../backend/src/main/java/com/landrop/transfer/FileTransferService.java) | 1799 | JAVA |
| [FileTransferStatus.java](../../backend/src/main/java/com/landrop/transfer/FileTransferStatus.java) | 32 | JAVA |
| [FileTransferUtils.java](../../backend/src/main/java/com/landrop/transfer/FileTransferUtils.java) | 303 | JAVA |
| [FolderArchiveService.java](../../backend/src/main/java/com/landrop/transfer/FolderArchiveService.java) | 107 | JAVA |
| [MultiPeerDownload.java](../../backend/src/main/java/com/landrop/transfer/MultiPeerDownload.java) | 331 | JAVA |
| [MultiPeerDownloadCoordinator.java](../../backend/src/main/java/com/landrop/transfer/MultiPeerDownloadCoordinator.java) | 411 | JAVA |
| [MultiPeerDownloadListener.java](../../backend/src/main/java/com/landrop/transfer/MultiPeerDownloadListener.java) | 25 | JAVA |
| [MultiPeerDownloadStatus.java](../../backend/src/main/java/com/landrop/transfer/MultiPeerDownloadStatus.java) | 63 | JAVA |
| [PeerDownloadState.java](../../backend/src/main/java/com/landrop/transfer/PeerDownloadState.java) | 142 | JAVA |
| [PeerDownloadWorker.java](../../backend/src/main/java/com/landrop/transfer/PeerDownloadWorker.java) | 239 | JAVA |
| [SharedFileScanner.java](../../backend/src/main/java/com/landrop/transfer/SharedFileScanner.java) | 86 | JAVA |

## Mã nguồn: com.landrop.transfer.messages (10 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [ChunkRequestMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/ChunkRequestMessage.java) | 88 | JAVA |
| [ChunkResponseMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/ChunkResponseMessage.java) | 153 | JAVA |
| [FileDataChunk.java](../../backend/src/main/java/com/landrop/transfer/messages/FileDataChunk.java) | 123 | JAVA |
| [FileOfferMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/FileOfferMessage.java) | 141 | JAVA |
| [FileQueryMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/FileQueryMessage.java) | 155 | JAVA |
| [FileQueryResponseMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/FileQueryResponseMessage.java) | 262 | JAVA |
| [FileRequestMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/FileRequestMessage.java) | 57 | JAVA |
| [ResumeRequestMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/ResumeRequestMessage.java) | 179 | JAVA |
| [ResumeResponseMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/ResumeResponseMessage.java) | 84 | JAVA |
| [TransferAckMessage.java](../../backend/src/main/java/com/landrop/transfer/messages/TransferAckMessage.java) | 65 | JAVA |

## Mã nguồn: com.landrop.util (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [AppLogger.java](../../backend/src/main/java/com/landrop/util/AppLogger.java) | 150 | JAVA |

## Mã nguồn: com.landrop.workspace (2 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [WorkspaceAccess.java](../../backend/src/main/java/com/landrop/workspace/WorkspaceAccess.java) | 86 | JAVA |
| [WorkspaceException.java](../../backend/src/main/java/com/landrop/workspace/WorkspaceException.java) | 15 | JAVA |

## Mã nguồn: com.landrop.workspace.http (1 tệp)

| Tệp | Số dòng | Ghi chú cấu trúc |
| --- | --- | --- |
| [WorkspaceHttpServer.java](../../backend/src/main/java/com/landrop/workspace/http/WorkspaceHttpServer.java) | 214 | JAVA |

