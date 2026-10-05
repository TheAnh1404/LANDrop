# File Management & LAN Transfer API & Realtime Contract

## 1. Overview & Architectural Principles

Phase 18A integrates File Management metadata persistence and LAN File Transfer coordination into the LANDrop ecosystem.

- **Stack**: Java 21, Maven, PostgreSQL (Neon Serverless), HikariCP, Java-WebSocket, standard Java `HttpServer` (Virtual Threads enabled).
- **Identity & Transport Boundary**:
  - **User & Workspace**: Cloud identity and authorization boundaries. Files and folders strictly belong to a `Workspace` and are created by an authenticated `User`.
  - **LAN Peer**: Purely a transport network endpoint (IP address, port, peerId, deviceId). A LAN peer is **never** a user identity.
  - **Transfer Request**: Coordinates between two authenticated users (or two devices of the same user) within a workspace, authorizing the direct LAN peer transfer.
- **Hierarchy & Cycle Detection**: Folders can be organized hierarchically under a workspace. Any attempt to move a parent folder into its own descendant is detected and rejected with `400 INVALID_REQUEST` (`Cannot move folder into its own subfolder (cycle detected)`).
- **Soft Delete**: Files and folders support non-destructive soft delete (`deleted_at`). Soft-deleted items are omitted from default listings unless `includeDeleted=true` is requested.
- **Strict Transfer State Machine**:
  `PENDING` &rarr; `ACCEPTED` &rarr; `TRANSFERRING` &rarr; `COMPLETED`. Terminal states: `REJECTED`, `FAILED`, `CANCELLED`.
- **Targeted Realtime Events**: Transfer notifications (`transfer:request`, `transfer:accepted`, `transfer:started`, `transfer:progress`, `transfer:completed`, `transfer:rejected`, `transfer:cancelled`) are dispatched directly to the active sessions of the sender and receiver devices.

---

## 2. Models & Data Transfer Objects (DTOs)

### 2.1 File DTO (`FileDto`)
```json
{
  "id": "f0000000-0000-0000-0000-000000000001",
  "workspaceId": "w0000000-0000-0000-0000-000000000001",
  "uploaderId": "u0000000-0000-0000-0000-000000000001",
  "userId": "u0000000-0000-0000-0000-000000000001",
  "folderId": "d0000000-0000-0000-0000-000000000001",
  "parentFolderId": "d0000000-0000-0000-0000-000000000001",
  "name": "project_report.pdf",
  "originalName": "project_report.pdf",
  "size": 5242880,
  "mimeType": "application/pdf",
  "hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "storageType": "LAN_ONLY",
  "createdAt": "2026-09-14T12:00:00Z",
  "updatedAt": "2026-09-14T12:00:00Z",
  "deletedAt": null
}
```

### 2.2 Folder DTO (`FolderDto`)
```json
{
  "id": "d0000000-0000-0000-0000-000000000001",
  "workspaceId": "w0000000-0000-0000-0000-000000000001",
  "name": "Design Assets",
  "parentId": null,
  "parentFolderId": null,
  "createdById": "u0000000-0000-0000-0000-000000000001",
  "createdAt": "2026-09-14T12:00:00Z",
  "updatedAt": "2026-09-14T12:00:00Z",
  "deletedAt": null
}
```

### 2.3 Device DTO (`DeviceDto`)
```json
{
  "deviceId": "dev-macbook-pro-16",
  "id": "dev-macbook-pro-16",
  "userId": "u0000000-0000-0000-0000-000000000001",
  "deviceName": "MacBook Pro",
  "peerId": "peer-mac-7749",
  "ipAddress": "192.168.1.50",
  "port": 8888,
  "lastSeenAt": "2026-09-14T12:10:00Z",
  "isOnline": true
}
```

### 2.4 Transfer DTO (`TransferDto`)
```json
{
  "id": "t0000000-0000-0000-0000-000000000001",
  "fileId": "f0000000-0000-0000-0000-000000000001",
  "fileName": "project_report.pdf",
  "fileSize": 5242880,
  "mimeType": "application/pdf",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "senderUserId": "u0000000-0000-0000-0000-000000000001",
  "receiverUserId": "u0000000-0000-0000-0000-000000000002",
  "senderDeviceId": "dev-macbook-pro-16",
  "receiverDeviceId": "dev-thinkpad-x1",
  "status": "PENDING",
  "createdAt": "2026-09-14T12:15:00Z",
  "startedAt": null,
  "completedAt": null,
  "failedAt": null,
  "errorMessage": null
}
```

---

## 3. HTTP REST API Specifications

All endpoints require Bearer JWT authentication: `Authorization: Bearer <JWT_ACCESS_TOKEN>`.

### 3.1 Workspace Folders

#### `GET /api/workspaces/:workspaceId/folders`
- **Query Parameters**:
  - `parentId` (UUID, optional): Filter by parent folder. Pass `"root"` or `"null"` for top-level folders.
  - `rootOnly` (boolean, optional, default: `false`): Return only root folders.
  - `includeDeleted` (boolean, optional, default: `false`): Include soft-deleted folders.
- **Response**: `200 OK` &rarr; `{ "data": [ FolderDto... ] }`

#### `POST /api/workspaces/:workspaceId/folders`
- **Request Body**:
```json
{
  "name": "Reports",
  "parentId": "d0000000-0000-0000-0000-000000000001" // optional (or parentFolderId)
}
```
- **Response**: `201 Created` &rarr; `{ "data": FolderDto }`

#### `GET /api/folders/:folderId`
- **Response**: `200 OK` &rarr; `{ "data": FolderDto }`

#### `PATCH /api/folders/:folderId`
- **Request Body**:
```json
{
  "name": "Updated Folder Name",
  "parentId": "d0000000-0000-0000-0000-000000000002" // optional; null moves to root
}
```
- **Cycle Detection**: Returns `400 INVALID_REQUEST` if moving a folder into itself or its descendant.
- **Response**: `200 OK` &rarr; `{ "data": FolderDto }`

#### `DELETE /api/folders/:folderId`
- **Behavior**: Soft deletes folder (`deleted_at = NOW()`).
- **Response**: `200 OK` &rarr; `{ "data": FolderDto }`

---

### 3.2 Workspace Files

#### `GET /api/workspaces/:workspaceId/files`
- **Query Parameters**:
  - `folderId` (UUID, optional): Filter by folder. Pass `"root"` or `"null"` for top-level files.
  - `rootOnly` (boolean, optional, default: `false`): Return only top-level files.
  - `includeDeleted` (boolean, optional, default: `false`): Include soft-deleted files.
- **Response**: `200 OK` &rarr; `{ "data": [ FileDto... ] }`

#### `POST /api/workspaces/:workspaceId/files`
- **Request Body**:
```json
{
  "name": "design_spec.pdf",
  "size": 10485760,
  "mimeType": "application/pdf",
  "hash": "sha256-file-hash-hex",
  "folderId": "d0000000-0000-0000-0000-000000000001", // optional (or parentFolderId)
  "storageType": "LAN_ONLY" // optional, defaults to LAN_ONLY
}
```
- **Response**: `201 Created` &rarr; `{ "data": FileDto }`

#### `GET /api/files/:fileId`
- **Response**: `200 OK` &rarr; `{ "data": FileDto }`

#### `PATCH /api/files/:fileId`
- **Request Body**:
```json
{
  "name": "new_spec.pdf",
  "folderId": "d0000000-0000-0000-0000-000000000002" // optional; null moves to root
}
```
- **Response**: `200 OK` &rarr; `{ "data": FileDto }`

#### `DELETE /api/files/:fileId`
- **Behavior**: Soft deletes file (`deleted_at = NOW()`).
- **Response**: `200 OK` &rarr; `{ "data": FileDto }`

---

### 3.3 Device Management & Peer Resolution

#### `POST /api/devices/register`
- **Request Body**:
```json
{
  "deviceId": "dev-mac-01",
  "deviceName": "Work MacBook",
  "peerId": "peer-mac-101",
  "ipAddress": "192.168.1.100",
  "port": 8888
}
```
- **Response**: `200 OK` &rarr; `{ "data": DeviceDto }`

#### `GET /api/devices`
- **Behavior**: Lists all devices registered to current authenticated user.
- **Response**: `200 OK` &rarr; `{ "data": [ DeviceDto... ] }`

#### `GET /api/devices/:deviceId`
- **Response**: `200 OK` &rarr; `{ "data": DeviceDto }`

#### `GET /api/devices/peer/:peerId`
- **Behavior**: Resolves device metadata by LAN peer identity.
- **Response**: `200 OK` &rarr; `{ "data": DeviceDto }`

#### `GET /api/users/:userId/devices`
- **Behavior**: Lists devices registered to a specific user (for transfer target selection).
- **Response**: `200 OK` &rarr; `{ "data": [ DeviceDto... ] }`

---

### 3.4 File Transfer Coordination

#### `POST /api/transfers`
- **Behavior**: Initiates an authorized transfer request for a workspace file.
- **Request Body**:
```json
{
  "fileId": "f0000000-0000-0000-0000-000000000001",
  "receiverUserId": "u0000000-0000-0000-0000-000000000002",
  "senderDeviceId": "dev-mac-01", // optional
  "receiverDeviceId": "dev-pc-02"  // optional
}
```
- **Response**: `201 Created` &rarr; `{ "data": TransferDto }`
- **Realtime Dispatch**: Sends `transfer:request` to receiver.

#### `GET /api/transfers/:transferId`
- **Response**: `200 OK` &rarr; `{ "data": TransferDto }`

#### `GET /api/transfers`
- **Query Parameters**:
  - `status`: Filter by status (`PENDING`, `ACCEPTED`, `TRANSFERRING`, `COMPLETED`, `REJECTED`, `FAILED`, `CANCELLED`).
  - `role`: `"sender"` or `"receiver"`.
- **Response**: `200 OK` &rarr; `{ "data": [ TransferDto... ] }`

#### `POST /api/transfers/:transferId/accept`
- **Permission**: Intended receiver only.
- **Request Body**: `{ "receiverDeviceId": "dev-pc-02" }` (optional)
- **Response**: `200 OK` &rarr; `{ "data": TransferDto }` (status: `ACCEPTED`)
- **Realtime Dispatch**: Sends `transfer:accepted` to sender.

#### `POST /api/transfers/:transferId/reject`
- **Permission**: Intended receiver only.
- **Request Body**: `{ "reason": "Disk space full" }` (optional)
- **Response**: `200 OK` &rarr; `{ "data": TransferDto }` (status: `REJECTED`)
- **Realtime Dispatch**: Sends `transfer:rejected` to sender.

#### `POST /api/transfers/:transferId/progress`
- **Permission**: Transfer participants (sender or receiver).
- **Request Body**:
```json
{
  "bytesTransferred": 2621440,
  "totalBytes": 5242880,
  "speed": 1048576.0 // bytes per second
}
```
- **State Transition**: If transfer is `ACCEPTED`, reporting progress auto-transitions status to `TRANSFERRING` and emits `transfer:started` before emitting `transfer:progress`.
- **Response**: `200 OK` &rarr; `{ "data": { "success": true } }`
- **Realtime Dispatch**: Emits `transfer:progress` to sender and receiver.

#### `POST /api/transfers/:transferId/complete`
- **Permission**: Transfer participants.
- **Response**: `200 OK` &rarr; `{ "data": TransferDto }` (status: `COMPLETED`)
- **Realtime Dispatch**: Emits `transfer:completed` to sender and receiver.

#### `POST /api/transfers/:transferId/cancel`
- **Permission**: Sender only.
- **Response**: `200 OK` &rarr; `{ "data": TransferDto }` (status: `CANCELLED`)
- **Realtime Dispatch**: Emits `transfer:cancelled` to receiver.

---

## 4. WebSocket Realtime Protocol

WebSocket URL: `/ws?token=<JWT_ACCESS_TOKEN>&device=<DEVICE_ID>`

### 4.1 Inbound Client Actions
Clients can trigger transfer coordination over WebSocket using standard action messages:
- `transfer.request`: `{ "type": "transfer.request", "fileId": "...", "receiverUserId": "...", "senderDeviceId": "...", "receiverDeviceId": "..." }`
- `transfer.accept`: `{ "type": "transfer.accept", "transferId": "...", "receiverDeviceId": "..." }`
- `transfer.reject`: `{ "type": "transfer.reject", "transferId": "...", "reason": "..." }`
- `transfer.progress`: `{ "type": "transfer.progress", "transferId": "...", "bytesTransferred": 1024, "totalBytes": 2048, "speed": 1024.0 }`
- `transfer.complete`: `{ "type": "transfer.complete", "transferId": "..." }`
- `transfer.cancel`: `{ "type": "transfer.cancel", "transferId": "..." }`
- `device.register`: `{ "type": "device.register", "deviceId": "...", "deviceName": "...", "peerId": "...", "ipAddress": "...", "port": 8888 }`

### 4.2 Outbound Server Events
Realtime transfer events are delivered with root-level fields and nested `data` / `transfer` mappings for client compatibility:
- `transfer:request`: Dispatched to receiver upon transfer initiation.
- `transfer:accepted`: Dispatched to sender when receiver accepts.
- `transfer:rejected`: Dispatched to sender with rejection reason.
- `transfer:started`: Dispatched to both participants when transfer begins.
- `transfer:progress`: Dispatched to both participants with current byte counts, speed, and percentage.
- `transfer:completed`: Dispatched to both participants upon successful completion.
- `transfer:cancelled`: Dispatched to receiver when sender cancels.
- `transfer:failed`: Dispatched to participants if transfer fails.

---

## 5. Security & Authorization Matrix

| Action | Owner | Admin | Member | Guest / Outsider |
|---|:---:|:---:|:---:|:---:|
| List Workspace Folders / Files | Allowed | Allowed | Allowed | `404 WORKSPACE_NOT_FOUND` |
| Create Folder / File Metadata | Allowed | Allowed | Allowed | `404 WORKSPACE_NOT_FOUND` |
| Edit Folder / File Metadata | Allowed | Allowed | Creator Only | `404 WORKSPACE_NOT_FOUND` |
| Delete Folder / File (Soft) | Allowed | Allowed | Creator Only | `404 WORKSPACE_NOT_FOUND` |
| Initiate Transfer of File | Allowed | Allowed | Allowed | `404 WORKSPACE_NOT_FOUND` |
| Target Non-Member in Transfer | `404` | `404` | `404` | `404` |
| Accept / Reject Transfer | Receiver Only | Receiver Only | Receiver Only | `403 FORBIDDEN` |
| Report Progress / Complete | Participants | Participants | Participants | `403 FORBIDDEN` |
| Cancel Transfer | Sender Only | Sender Only | Sender Only | `403 FORBIDDEN` |
