# Phase 18A — File Management + LAN Transfer Backend Delivery

## 1. Summary

Phase 18A implements the File Management and LAN Transfer Coordination backend for LANDrop.

- **Stack**: Java 21, Maven, PostgreSQL (Neon Serverless), HikariCP, Java-WebSocket, standard Java `HttpServer` (Virtual Threads enabled).
- **Core Architecture**:
  `Authenticated User` &rarr; `Workspace` &rarr; `Folder / File Metadata` (Neon PostgreSQL) &rarr; `Transfer Request` &rarr; `Device / LAN Peer Resolution` &rarr; `Direct LAN Transfer`.
- **Identity & Transport Separation**:
  - `User`: Cloud-authenticated identity owning workspaces, folders, and files.
  - `Device`: Registered instance belonging to an authenticated user.
  - `LAN Peer`: Purely an ephemeral transport endpoint (IP address, port, peerId) used for direct chunk transfer.
- **Strict Scope Boundaries**: Phase 18A delivers backend metadata, authorization, and transfer coordination. Phase 18B will implement frontend UI. Zero Phase 19+ features (no task management, activity logs, presence, cloud CDN/storage) were added.

---

## 2. Implementation Summary

### 2.1 Database Migration & Persistence
- **Migration Script `V5__file_management_phase18.sql`**:
  - Updates `folders` table: adds `created_by_id UUID REFERENCES users(id)` and `deleted_at TIMESTAMPTZ` with index.
  - Updates `files` table: adds `original_name VARCHAR(255)`, `storage_type VARCHAR(50) DEFAULT 'LAN_ONLY'`, `storage_path TEXT`, and `deleted_at TIMESTAMPTZ` with index.
  - Creates `devices` table: `id VARCHAR(100) PRIMARY KEY`, `user_id UUID NOT NULL REFERENCES users(id)`, `device_name`, `peer_id`, `ip_address`, `port`, `last_seen_at`, `created_at`, `updated_at`.
  - Creates `transfers` table: `id UUID PRIMARY KEY`, `file_id UUID NOT NULL REFERENCES files(id)`, `sender_user_id UUID`, `receiver_user_id UUID`, `sender_device_id`, `receiver_device_id`, `status VARCHAR(50)`, `created_at`, `started_at`, `completed_at`, `failed_at`, `error_message`.
- **`DatabaseMigrator.java`**:
  - Automatically loads and executes V1 through V5 migrations.
  - Verifies presence of all 10 tables (`users`, `workspaces`, `workspace_members`, `channels`, `messages`, `folders`, `files`, `refresh_tokens`, `devices`, `transfers`).

### 2.2 Models, Repositories, & Services
- **Data Models**:
  - `com.landrop.database.model.Device`: Model record for user device mapping.
  - `com.landrop.database.model.TransferRecord`: Model record for transfer requests and status tracking.
  - Enhanced `FileRecord` and `Folder` with soft delete timestamps and helper aliases.
- **Repositories**:
  - `DeviceRepository`: Upserts devices, queries by user ID, queries by peer ID, finds by device ID.
  - `TransferRepository`: Inserts transfer requests, executes atomic status transitions, queries transfers with filtering.
  - `FileRepository` & `FolderRepository`: Enhanced with soft-delete filtering (`deleted_at IS NULL`), folder hierarchy queries, and cycle detection helper `isDescendantOrSelf`.
- **Domain Services**:
  - `FileManagementService`:
    - Workspace folder CRUD, parent validation, cycle prevention (`400 INVALID_REQUEST`).
    - Workspace file metadata CRUD, folder containment validation, soft delete tombstoning.
    - Workspace membership authorization (`requireWorkspaceMember`).
  - `TransferCoordinationService`:
    - Device registration and peer resolution.
    - Transfer request initiation, cross-user and same-user multi-device transfer authorization.
    - Strict state machine enforcement (`PENDING` &rarr; `ACCEPTED` &rarr; `TRANSFERRING` &rarr; `COMPLETED`, terminal `REJECTED`/`CANCELLED`/`FAILED`).
    - Progress reporting and realtime dispatch integration.
  - `TransferBroadcaster`:
    - Decoupled event broadcasting interface.

### 2.3 HTTP Servers & Realtime WebSocket
- **`FileHttpServer`**:
  - Implements REST endpoints for `/api/files/*`, `/api/folders/*`, `/api/devices/*`, `/api/transfers/*`, and `/api/users/:userId/devices`.
  - Exposes sub-route handlers for workspace files and folders.
- **`WorkspaceHttpServer`**:
  - Integrated `/api/workspaces/:workspaceId/files` and `/api/workspaces/:workspaceId/folders`.
  - Properly dispatches errors using `FileException`.
- **`RealtimeServer`**:
  - Implements `TransferBroadcaster`.
  - Added inbound actions: `device.register`, `transfer.request`, `transfer.accept`, `transfer.reject`, `transfer.progress`, `transfer.complete`, `transfer.cancel`.
  - Broadcasts targeted outbound events (`transfer:*`) directly to sender and receiver user connections and specific devices.

---

## 3. Verification & Test Results

### 3.1 Unit & In-Memory Integration Suite (`FileManagementTest`)
- 9 end-to-end integration tests:
  1. `test1_createAndListFoldersWithHierarchy`: Folder creation and root filtering.
  2. `test2_preventCyclicFolderMoves`: Cycle detection preventing moving folder into own child.
  3. `test3_createAndFilterFilesByFolder`: File creation in folders and root filtering.
  4. `test4_softDeleteFilesAndFolders`: Soft delete verification and `includeDeleted` filter.
  5. `test5_deviceRegistrationAndPeerLookup`: Device registration and peer resolution.
  6. `test6_multiUserTransferRequestAcceptProgressComplete`: Full multi-user transfer lifecycle with WebSocket events.
  7. `test7_transferRejectionAndCancellation`: Rejection with reason and cancellation flows.
  8. `test8_sameUserMultiDeviceTransfer`: Same user transferring files between two own devices.
  9. `test9_workspaceTransferAuthorizationBoundaries`: Outsider rejection and workspace boundary security.
- **Result**: **9 / 9 PASSED** (0 failures, 0 errors).

### 3.2 Live Neon PostgreSQL Integration Suite (`FileManagementPostgresTest`)
- Executed against live Neon PostgreSQL database in isolated test schema:
  - Validates V1..V5 migrations and table/column schemas.
  - Tests live folder hierarchy, cycle detection, and soft delete.
  - Tests live file metadata lifecycle and deleted filtering.
  - Tests live device registration and peer lookup.
  - Tests live transfer lifecycle over HTTP + Realtime WebSocket with table assertion.
  - Tests live transfer rejection and cancellation.
  - Tests live multi-device transfer.
  - Tests live workspace authorization boundaries.
  - Tests existing persistent chat and workspace regression.
- **Result**: **9 / 9 PASSED** (0 failures, 0 errors).

### 3.3 Full Backend Regression Suite
- Ran full test suite across the entire backend codebase:
  - **Tests run**: **251**
  - **Failures**: **0**
  - **Errors**: **0**
  - **Skipped**: **24** (conditional PostgreSQL tests when `-Dlandrop.test.postgres=false`)
  - **Status**: **BUILD SUCCESS**
