# Phase 17A — Persistent Team Chat Delivery

## 1. Summary

Phase 17A converts LANDrop chat from core/test-level chat into a persistent, workspace-aware, user-authenticated team chat backend.

- **Stack**: Java 21, Maven, PostgreSQL (Neon Serverless), HikariCP, Java-WebSocket, standard Java `HttpServer` (Virtual Threads enabled).
- **Boundary**: Strict Phase 17A backend deliverable. No frontend changes (handled in Phase 17B), and zero Phase 18+ features (no reactions, no file attachment overhaul, no tasks, notifications, presence, search, or activity logs).

---

## 2. Implementation Summary

### 2.1 Database Migration
- Migration script `backend/src/main/resources/db/migration/V4__persistent_chat_phase17.sql`:
  - Adds `edited_at TIMESTAMPTZ` to `messages`.
  - Adds `deleted_at TIMESTAMPTZ` to `messages`.
  - Creates composite index `idx_messages_channel_chronological` on `messages(channel_id, created_at ASC, id ASC)` to optimize cursor-based history pagination.
- `DatabaseMigrator.java` automatically runs and verifies V1..V4 during startup.

### 2.2 Domain DTOs
- `SenderDto`: Public-safe sender projection containing `id`, `username`, `displayName`, and `avatarUrl`. Completely excludes passwords, emails, and tokens.
- `ChatMessageDto`: Canonical message response matching contract (`id`, `channelId`, `senderId`, `sender`, `content`, `replyToId`, `clientMessageId`, `createdAt`, `updatedAt`, `editedAt`, `deletedAt`).
- `ChannelDto`: Channel object with `description` mapping to `topic`.
- `MessagesPageDto`: Dual-formatted pagination response providing `items`/`nextCursor` alongside `data`/`page`.

### 2.3 Core Services & HTTP Server
- `ChannelChatService`: Enforces workspace membership, channel existence, cursor pagination (`created_at, id`), message creation with idempotency on `(userId, clientMessageId)`, reply validation across same channel, author-only edit, soft delete, and realtime broadcast dispatch.
- `ChatHttpServer`: Registered in `AuthHttpServer` to serve `/api/channels/*` and `/api/messages/*` with JSON validation and standardized error handling.
- `WorkspaceHttpServer`: Added routes for `GET /api/workspaces/:id/channels` and `POST /api/workspaces/:id/channels`.

### 2.4 Realtime Server & WebSocket Rooms
- `RealtimeServer` & `ConnectionRegistry`:
  - Channel room support via `channel.join` / `channel:join` and `channel.leave` / `channel:leave`.
  - Implements `ChannelBroadcaster` to fan out `message.created`, `message.updated`, and `message.deleted` to all active devices of all channel members in the room.
  - Verifies channel workspace membership prior to granting room access.
  - Multi-device fanout: A single user connected on multiple devices receives synced updates across all sessions.

---

## 3. Endpoints & Protocol

The complete contract is documented in [PHASE-17A-API-CONTRACT.md](PHASE-17A-API-CONTRACT.md).

### REST Endpoints
- `GET /api/workspaces/:workspaceId/channels` — List channels in workspace
- `POST /api/workspaces/:workspaceId/channels` — Create channel (OWNER or ADMIN only)
- `GET /api/channels/:channelId` — Channel details
- `GET /api/channels/:channelId/messages` — Cursor-paginated message history
- `POST /api/channels/:channelId/messages` — Send message (with optional `clientMessageId` and `replyToId`)
- `PATCH /api/messages/:messageId` — Edit message (author only)
- `DELETE /api/messages/:messageId` — Soft delete message (author only)

### Realtime Events
- Handshake: `/api/realtime?token=JWT` or `/api/realtime/channels/:channelId?token=JWT`
- Inbound: `channel.join`, `channel.leave`, `message.send`, `message.edit`, `message.delete`
- Outbound: `connected`, `channel.joined`, `channel.left`, `message.created`, `message.updated`, `message.deleted`, `error`

---

## 4. Verification

1. **In-Memory / Unit Integration Suite (`PersistentChatTest`)**:
   - `messageHistoryPaginationWithCursor`: Verifies 2-item pages with forward composite cursor pagination.
   - `authenticatedUserCanJoinAuthorizedChannelRoomUnauthorizedRejected`: Verifies room subscription authorization.
   - `crossChannelReplyRejected`: Verifies replies to messages in other channels return 400.
   - `senderCanEditOwnMessageOtherCannot`: Verifies 403 on third-party edit and realtime broadcast on author edit.
   - `senderCanDeleteOwnMessageOtherCannot`: Verifies 403 on third-party delete, soft-delete tombstone, and rejection of editing deleted messages.
   - Channel listing and creation permission checks (MEMBER 403, outsider 404).
   - Result: **8/8 PASSED** (0 failures, 0 errors).

2. **Neon PostgreSQL Live Test Suite (`PersistentChatPostgresTest`)**:
   - Executed against live Neon PostgreSQL instance with isolated temporary schema executing migrations V1–V4.
   - Tested persistent channel creation, RBAC checks, message persistence surviving server restart, cursor pagination across pages, multi-room broadcasting, multi-device sync, and soft delete.
   - Result: **9/9 PASSED** (0 failures, 0 errors).

3. **Backend Regression**:
   - Full suite executed without regressions on existing authentication, workspace, peer-to-peer, and realtime modules.
