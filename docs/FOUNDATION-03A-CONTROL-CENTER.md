# FOUNDATION-03A — LANDrop Server Control Center

## Overview & Objectives

FOUNDATION-03A elevates the LANDrop Server from an autonomous background network process into a fully-manageable server application equipped with an Administrative Control Center. 

Key capabilities:
1. **System Admin Authority**: System-wide administrative permissions (`SYSTEM_ADMIN`) decoupled from Workspace roles.
2. **Server Information & Network Dynamic Inspection**: Discovery and real-time reporting of real LAN network interfaces and server metadata.
3. **Server Configuration Management**: Safe configuration lifecycle with Current vs Pending diffing, strict port/heartbeat validations, protocol version pinning, and persistence.
4. **Administrative Dashboard**: Aggregated server status, uptime, connected clients, registered users, and active sessions.
5. **User & Session Administration**: Enable/disable user accounts with immediate session revocation; selective session termination.
6. **Connected Client Control**: Real-time registry listing and remote disconnection with standard protocol close codes (`4001`).
7. **Operational Audit Ring Buffer**: In-memory 1,000-entry logging buffer with filtering, search, and automatic credential sanitization.
8. **Embedded Web Console**: Single-Page HTML5/CSS/JS administrative dashboard served directly from the server at `/admin`.

---

## 1. System Admin Model & Role Architecture

System administrative privileges are decoupled from workspace memberships to ensure workspace isolation while giving server operators administrative control.

### Database Schema Changes
`User` model in `prisma/schema.prisma` and PostgreSQL migration `V6__server_control_center_phase03.sql`:
- `status`: `VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'` (`ACTIVE`, `DISABLED`)
- `systemRole`: `VARCHAR(32) NOT NULL DEFAULT 'USER'` (`USER`, `SYSTEM_ADMIN`)
- Indexed with `idx_users_status` and `idx_users_system_role`.

### JWT Claims Extension
- Standard JWT access and refresh tokens carry the `role` claim.
- Parsed into `JwtClaims.systemRole` with helper `isSystemAdmin()`.

### Middleware Security
- `AdminAuthMiddleware.requireSystemAdmin(HttpExchange)`:
  - Validates `Authorization: Bearer <token>`.
  - Verifies token signature and checks `claims.isSystemAdmin()`.
  - Confirms user exists and status is `ACTIVE` in database.
  - Returns `401 Unauthorized` for missing/invalid tokens; `403 Forbidden` for non-admin accounts.

### Bootstrapping
- `AdminBootstrapService` creates or synchronizes the fixed local account on every server start.
- Username: `admin`; password: `admin123`; email: `admin@landrop.local`.
- The account is always activated and assigned `SYSTEM_ADMIN`; environment variables do not override it.
- Password reset tooling is disabled. If its database hash is changed manually, the next server start restores `admin123`.

---

### Control Center authentication boundary

The embedded Control Center signs administrators in through `POST /api/admin/auth/login`.
The regular Client endpoint `POST /api/auth/login` rejects `SYSTEM_ADMIN` accounts with
`ADMIN_LOGIN_REQUIRED`; Client refresh, `/api/auth/me`, and authenticated realtime binding
apply the same boundary. Regular users continue to authenticate through the Client flow.

## 2. Server Information & Network Interfaces

### GET `/api/server/info`
- **Authentication**: System Admin Bearer token.
- **Response**:
```json
{
  "serverName": "LANDrop Server",
  "serverVersion": "0.1.0-SNAPSHOT",
  "protocolVersion": 1,
  "status": "RUNNING",
  "uptimeSeconds": 1845,
  "startedAt": "2026-09-15T01:30:00Z",
  "httpPort": 3000,
  "wsPort": 3001,
  "host": "0.0.0.0",
  "lanIps": ["192.168.22.206"],
  "lanHealthUrl": "http://192.168.22.206:3000/health",
  "lanAdminUrl": "http://192.168.22.206:3000/admin",
  "environment": "production"
}
```

### GET `/api/server/interfaces`
- **Authentication**: System Admin Bearer token.
- Dynamic inspection via `NetworkInterfaceInspector` reporting display name, interface name, MAC address, MTU, status, loopback/virtual flags, and IPv4 addresses (with primary LAN interface detection).

---

## 3. Server Configuration Management

### State Lifecycle: Current vs Pending
- **Current Configuration**: Active runtime settings loaded in memory.
- **Pending Configuration**: Desired settings stored in `data/server-config.json` taking effect upon server restart.
- **Requires Restart**: Automatically flagged when network ports (`httpPort`, `wsPort`) or host address are altered.

### Validation Rules
- Ports must be between 1024 and 65535, and `httpPort != wsPort`.
- Heartbeat timeout must strictly exceed heartbeat interval (`heartbeatTimeoutMs > heartbeatIntervalMs`).
- `protocolVersion` is strictly read-only (`1`). Attempts to mutate return `400 Bad Request`.

### Endpoints
- `GET /api/server/config`: Returns `{ "current": {...}, "pending": {...}, "requiresRestart": boolean }`.
- `POST /api/server/config`: Applies updates, records an audit log event (`CONFIG_CHANGED`), persists changes to `data/server-config.json`, and returns updated configuration state.

---

## 4. Administrative Dashboard & Metrics

### GET `/api/admin/dashboard`
Aggregated operational telemetry:
```json
{
  "status": "RUNNING",
  "uptimeSeconds": 3600,
  "startedAt": "2026-09-15T01:00:00Z",
  "connectedClients": 4,
  "registeredUsers": 12,
  "registeredDevices": 18,
  "workspaceCount": 4,
  "activeSessions": 6,
  "activeTransfers": 0,
  "httpPort": 3000,
  "wsPort": 3001,
  "protocolVersion": 1,
  "requiresRestart": false
}
```

---

## 5. User & Session Administration

### Endpoints
- `GET /api/admin/users`: List registered users with pagination, role, and status.
- `PATCH /api/admin/users/{id}`: Update display name, account status, or system role.
- `DELETE /api/admin/users/{id}`: Permanently delete a user after their dependent data has been transferred or deleted.
  - Never permits self-deletion or removal/demotion of the final `SYSTEM_ADMIN`.
  - Rejects deletion where foreign-key protected data remains, rather than corrupting relationships.
- `POST /api/admin/users/{id}/disable` and `POST /api/admin/users/{id}/enable`: Change account status.
  - Disabling a user terminates all their active refresh token sessions immediately.
  - Protects against self-disablement.
  - Prevents disabling the last remaining `SYSTEM_ADMIN`.
- `GET /api/admin/sessions`: List all active user sessions across devices.
- `DELETE /api/admin/sessions/{id}`: Administratively revoke a specific refresh token session.

---

## 6. Group / Workspace Data Administration

System administrators can manage collaboration groups directly from the Control Center; raw SQL execution is intentionally not exposed.

- `GET /api/admin/workspaces?search=`: List groups with owner and member count.
- `POST /api/admin/workspaces`: Create a group with `name`, optional `slug`/`description`, and an existing `ownerId`; the owner is added as `OWNER` automatically.
- `PATCH /api/admin/workspaces/{id}`: Change name or description.
- `DELETE /api/admin/workspaces/{id}`: Permanently delete the group and its cascade-owned workspace data.
- `GET /api/admin/workspaces/{id}/members`: List group membership.
- `POST /api/admin/workspaces/{id}/members`: Add a member or update their role.
- `PATCH /api/admin/workspaces/{id}/members/{userId}` and `DELETE /api/admin/workspaces/{id}/members/{userId}`: Update or remove a member.

The original owner keeps the `OWNER` role and cannot be removed through the member editor. Every mutation emits an operational audit event.

---

## 7. Realtime Client Control

### Endpoints
- `GET /api/admin/clients`: List connected WebSocket clients from `ClientRegistry` (remote address, connection ID, device name, client instance ID, handshake time, last heartbeat).
- `POST /api/admin/clients/{id}/disconnect`: Administratively terminate client connection.
  - Sends close frame code `4001` with reason payload.
  - Unregisters client from active registry.
  - Emits audit log `ADMIN_CLIENT_DISCONNECT`.

---

## 8. Operational Audit Logging

### Architecture
- Fixed-capacity circular ring buffer (1,000 entries) managed by `OperationalLogService`.
- Structured records: `id`, `timestamp`, `level` (INFO, WARN, ERROR), `eventType`, `actor`, `target`, `details`.
- Automatic regex-based credential masking for passwords, secrets, Bearer tokens, and auth headers.
- Filterable by level, event type, and free-text search with limit and offset.

### GET `/api/admin/logs`
- Query parameters: `limit`, `offset`, `level`, `eventType`, `search`.

---

## 9. Embedded Administrative Web Console

Served directly from the HTTP server at `/admin`:
- Single-page vanilla HTML5, CSS3, and JavaScript app with zero external CDN dependencies.
- Dark theme inspired by modern enterprise cloud consoles.
- Embedded JWT storage in `sessionStorage` with interactive login dialog.
- Responsive live tabs:
  1. **Dashboard**: Metrics cards, server status, quick action links.
  2. **Users**: User list, filter, enable/disable toggle.
  3. **Groups & Data**: Group CRUD and membership editor with database-backed counts.
  4. **Clients**: Connected devices table, metadata, disconnect button.
  5. **Sessions**: Active session explorer, revoke action.
  6. **Server & Network**: Active interfaces, current vs pending configuration editor, restart warning banner.
  7. **Logs**: Live audit log viewer with search, level filtering, and pagination.

---

## Verification & Test Matrix

- `AdminControlCenterTest` includes lifecycle and HTTP-route coverage for system-admin group CRUD and membership management.
- Targeted validation: `mvn -pl backend "-Dtest=AdminControlCenterTest#testWorkspaceDatabaseManagement,AdminControlCenterTest#testWorkspaceManagementHttpRoutes" test`.
- The broader legacy WebSocket test suite still needs its unauthenticated test clients migrated to the authenticated realtime contract before it can be treated as a release gate.
