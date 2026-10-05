# Persistent Team Chat API & Realtime Contract

## 1. Overview & Architectural Principles

Phase 17A transforms LANDrop chat from ephemeral core/test-level chat into a persistent, workspace-aware, user-authenticated team chat backend.

- **Stack**: Java 21, Maven, PostgreSQL (Neon Serverless), HikariCP, Java-WebSocket, standard Java `HttpServer` (Virtual Threads enabled).
- **Security & Non-Disclosure**: Outsiders receive `404 WORKSPACE_NOT_FOUND` / `404 CHANNEL_NOT_FOUND` rather than revealing channel/workspace metadata or membership status.
- **Privacy**: The public `sender` projection exposes exclusively `{ "id", "username", "displayName", "avatarUrl" }`. Security credentials (email, password hash, refresh tokens) are never leaked.
- **Idempotency**: Message creation accepts an optional `clientMessageId` (UUID). Re-transmissions with identical `(userId, clientMessageId)` return the existing message without duplicate row creation or duplicate broadcast.
- **Integrity**: Replying to a message (`replyToId`) validates that the parent message exists and belongs to the exact same channel. Cross-channel parent references are rejected with `400 INVALID_REQUEST`.
- **Soft Delete**: Message deletion preserves row identity and auditability, sets `deleted_at`, and rewrites content to `[This message was deleted]`. Soft-deleted messages cannot be subsequently edited.

---

## 2. Models & Data Transfer Objects

### 2.1 Sender DTO (`SenderDto`)
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "username": "alex",
  "displayName": "Alex Smith",
  "avatarUrl": null
}
```

### 2.2 Channel DTO (`ChannelDto`)
```json
{
  "id": "a0000000-0000-0000-0000-000000000001",
  "workspaceId": "b0000000-0000-0000-0000-000000000001",
  "name": "general",
  "topic": "General team discussion",
  "description": "General team discussion",
  "isPrivate": false,
  "createdAt": "2026-09-14T12:00:00Z",
  "updatedAt": "2026-09-14T12:00:00Z"
}
```

### 2.3 Message DTO (`ChatMessageDto`)
```json
{
  "id": "c0000000-0000-0000-0000-000000000001",
  "channelId": "a0000000-0000-0000-0000-000000000001",
  "senderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "sender": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "username": "alex",
    "displayName": "Alex Smith",
    "avatarUrl": null
  },
  "content": "Hello team!",
  "replyToId": null,
  "clientMessageId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "createdAt": "2026-09-14T12:05:00Z",
  "updatedAt": "2026-09-14T12:05:00Z",
  "editedAt": null,
  "deletedAt": null
}
```

---

## 3. HTTP REST API Specifications

Base path: `/api`. All endpoints require `Authorization: Bearer <JWT_ACCESS_TOKEN>`.

### 3.1 Channels in Workspace

#### `GET /api/workspaces/:workspaceId/channels`
- **Permission**: Workspace members (`OWNER`, `ADMIN`, `MEMBER`).
- **Response**: `200 OK`
```json
{
  "data": [
    {
      "id": "a0000000-0000-0000-0000-000000000001",
      "workspaceId": "b0000000-0000-0000-0000-000000000001",
      "name": "general",
      "topic": "General team discussion",
      "description": "General team discussion",
      "isPrivate": false,
      "createdAt": "2026-09-14T12:00:00Z",
      "updatedAt": "2026-09-14T12:00:00Z"
    }
  ]
}
```

#### `POST /api/workspaces/:workspaceId/channels`
- **Permission**: Workspace `OWNER` or `ADMIN`. `MEMBER` receives `403 FORBIDDEN`. Outsider receives `404 WORKSPACE_NOT_FOUND`.
- **Request Body**:
```json
{
  "name": "development",
  "description": "Tech discussions"
}
```
- **Response**: `201 Created`
```json
{
  "data": {
    "id": "a0000000-0000-0000-0000-000000000002",
    "workspaceId": "b0000000-0000-0000-0000-000000000001",
    "name": "development",
    "topic": "Tech discussions",
    "description": "Tech discussions",
    "isPrivate": false,
    "createdAt": "2026-09-14T12:06:00Z",
    "updatedAt": "2026-09-14T12:06:00Z"
  }
}
```

---

### 3.2 Channel Details

#### `GET /api/channels/:channelId`
- **Permission**: Channel workspace members.
- **Response**: `200 OK`
```json
{
  "data": {
    "id": "a0000000-0000-0000-0000-000000000001",
    "workspaceId": "b0000000-0000-0000-0000-000000000001",
    "name": "general",
    "topic": "General team discussion",
    "description": "General team discussion",
    "isPrivate": false,
    "createdAt": "2026-09-14T12:00:00Z",
    "updatedAt": "2026-09-14T12:00:00Z"
  }
}
```

---

### 3.3 Message History & Cursor Pagination

#### `GET /api/channels/:channelId/messages`
- **Query Parameters**:
  - `limit`: Integer `1..100` (default: `50`).
  - `cursor`: Opaque composite cursor `<created_at>|<id>` for forward pagination.
  - `before`: Opaque composite cursor `<created_at>|<id>` for reverse/historical scrolling.
  - `order`: `asc` (default forward chronological) or `desc`.
- **Permission**: Channel workspace members.
- **Response**: `200 OK`
```json
{
  "items": [
    {
      "id": "c0000000-0000-0000-0000-000000000001",
      "channelId": "a0000000-0000-0000-0000-000000000001",
      "senderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "sender": {
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "username": "alex",
        "displayName": "Alex Smith",
        "avatarUrl": null
      },
      "content": "Hello team!",
      "replyToId": null,
      "clientMessageId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
      "createdAt": "2026-09-14T12:05:00Z",
      "updatedAt": "2026-09-14T12:05:00Z",
      "editedAt": null,
      "deletedAt": null
    }
  ],
  "nextCursor": "2026-09-14T12:05:00Z|c0000000-0000-0000-0000-000000000001",
  "data": [ /* identical array to items */ ],
  "page": {
    "nextCursor": "2026-09-14T12:05:00Z|c0000000-0000-0000-0000-000000000001"
  }
}
```

---

### 3.4 Send Message

#### `POST /api/channels/:channelId/messages`
- **Permission**: Channel workspace members.
- **Request Body**:
```json
{
  "content": "Deploying build #42 now.",
  "clientMessageId": "8f88ceb0-13f5-46b7-a36c-94cf17fbbca9",
  "replyToId": "c0000000-0000-0000-0000-000000000001"
}
```
- **Response**: `201 Created`
```json
{
  "data": {
    "id": "d0000000-0000-0000-0000-000000000002",
    "channelId": "a0000000-0000-0000-0000-000000000001",
    "senderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "sender": {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "username": "alex",
      "displayName": "Alex Smith",
      "avatarUrl": null
    },
    "content": "Deploying build #42 now.",
    "replyToId": "c0000000-0000-0000-0000-000000000001",
    "clientMessageId": "8f88ceb0-13f5-46b7-a36c-94cf17fbbca9",
    "createdAt": "2026-09-14T12:07:00Z",
    "updatedAt": "2026-09-14T12:07:00Z",
    "editedAt": null,
    "deletedAt": null
  }
}
```

---

### 3.5 Edit Message

#### `PATCH /api/messages/:messageId`
- **Permission**: Message author only. Other workspace members receive `403 FORBIDDEN`. Outsiders receive `404 NOT_FOUND`.
- **Validation**: Cannot edit soft-deleted message (`400 INVALID_REQUEST`).
- **Request Body**:
```json
{
  "content": "Deploying build #42 to production now."
}
```
- **Response**: `200 OK`
```json
{
  "data": {
    "id": "d0000000-0000-0000-0000-000000000002",
    "channelId": "a0000000-0000-0000-0000-000000000001",
    "senderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "sender": {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "username": "alex",
      "displayName": "Alex Smith",
      "avatarUrl": null
    },
    "content": "Deploying build #42 to production now.",
    "replyToId": "c0000000-0000-0000-0000-000000000001",
    "clientMessageId": "8f88ceb0-13f5-46b7-a36c-94cf17fbbca9",
    "createdAt": "2026-09-14T12:07:00Z",
    "updatedAt": "2026-09-14T12:08:30Z",
    "editedAt": "2026-09-14T12:08:30Z",
    "deletedAt": null
  }
}
```

---

### 3.6 Delete Message (Soft Delete)

#### `DELETE /api/messages/:messageId`
- **Permission**: Message author only. Other workspace members receive `403 FORBIDDEN`. Outsiders receive `404 NOT_FOUND`.
- **Response**: `200 OK` (returns tombstone message object)
```json
{
  "data": {
    "id": "d0000000-0000-0000-0000-000000000002",
    "channelId": "a0000000-0000-0000-0000-000000000001",
    "senderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "sender": {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "username": "alex",
      "displayName": "Alex Smith",
      "avatarUrl": null
    },
    "content": "[This message was deleted]",
    "replyToId": "c0000000-0000-0000-0000-000000000001",
    "clientMessageId": "8f88ceb0-13f5-46b7-a36c-94cf17fbbca9",
    "createdAt": "2026-09-14T12:07:00Z",
    "updatedAt": "2026-09-14T12:09:00Z",
    "editedAt": "2026-09-14T12:08:30Z",
    "deletedAt": "2026-09-14T12:09:00Z"
  }
}
```

---

## 4. Realtime WebSocket Protocol

WebSocket Endpoint: `ws://SERVER:PORT/api/realtime?token=<JWT>` or `ws://SERVER:PORT/api/realtime/channels/:channelId?token=<JWT>`.

### 4.1 Connection & Room Membership

- Upon successful handshake, client receives:
```json
{
  "event": "connected",
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "deviceId": "desktop-1"
}
```

- **Join Room**: Client sends `channel.join` or `channel:join`:
```json
{
  "action": "channel.join",
  "channelId": "a0000000-0000-0000-0000-000000000001",
  "workspaceId": "b0000000-0000-0000-0000-000000000001"
}
```
Server responds with:
```json
{
  "event": "channel.joined",
  "channelId": "a0000000-0000-0000-0000-000000000001"
}
```
If caller does not belong to the channel's workspace, server responds with `error` (`code: FORBIDDEN`) and drops room subscription.

- **Leave Room**: Client sends `channel.leave` or `channel:leave`:
```json
{
  "action": "channel.leave",
  "channelId": "a0000000-0000-0000-0000-000000000001"
}
```
Server responds with:
```json
{
  "event": "channel.left",
  "channelId": "a0000000-0000-0000-0000-000000000001"
}
```

---

### 4.2 Realtime Broadcast Events

When a message mutation occurs (via WebSocket or HTTP REST), all active connections across all devices belonging to authorized users subscribed to that channel room receive the event.

#### `message.created`
```json
{
  "event": "message.created",
  "message": {
    "id": "c0000000-0000-0000-0000-000000000001",
    "channelId": "a0000000-0000-0000-0000-000000000001",
    "senderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "sender": {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "username": "alex",
      "displayName": "Alex Smith",
      "avatarUrl": null
    },
    "content": "Hello team!",
    "replyToId": null,
    "clientMessageId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "createdAt": "2026-09-14T12:05:00Z",
    "updatedAt": "2026-09-14T12:05:00Z",
    "editedAt": null,
    "deletedAt": null
  }
}
```

#### `message.updated`
```json
{
  "event": "message.updated",
  "message": {
    "id": "c0000000-0000-0000-0000-000000000001",
    "channelId": "a0000000-0000-0000-0000-000000000001",
    "content": "Hello team updated!",
    "editedAt": "2026-09-14T12:08:00Z"
  }
}
```

#### `message.deleted`
```json
{
  "event": "message.deleted",
  "channelId": "a0000000-0000-0000-0000-000000000001",
  "messageId": "c0000000-0000-0000-0000-000000000001",
  "deletedAt": "2026-09-14T12:09:00Z",
  "message": {
    "id": "c0000000-0000-0000-0000-000000000001",
    "channelId": "a0000000-0000-0000-0000-000000000001",
    "content": "[This message was deleted]",
    "deletedAt": "2026-09-14T12:09:00Z"
  }
}
```

---

## 5. Error Code Matrix

| Status | Code | Meaning |
|---|---|---|
| 400 | `INVALID_REQUEST` | Validation error, invalid JSON, length limit exceeded, reply mismatch, or editing deleted message. |
| 401 | `UNAUTHENTICATED` | Missing, expired, or invalid JWT bearer token. |
| 403 | `FORBIDDEN` | Insufficient role (e.g., MEMBER creating channel) or editing/deleting someone else's message. |
| 404 | `WORKSPACE_NOT_FOUND` | Workspace does not exist or caller is not a member. |
| 404 | `CHANNEL_NOT_FOUND` | Channel does not exist or caller is not a member of its workspace. |
| 404 | `NOT_FOUND` | Message does not exist. |
| 409 | `CHANNEL_EXISTS` | Channel with this name already exists within the target workspace. |
| 503 | `DATABASE_UNAVAILABLE` | Database connectivity failure. |
