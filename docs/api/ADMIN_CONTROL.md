# LANDrop Server Control Center

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
8. **Desktop Administration**: Native Server Desktop tabs manage users, sessions, workspaces, database records and audit logs. The web console has been removed.

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
- Username: `admin`; email: `admin@landrop.local`.
- The account is activated and assigned `SYSTEM_ADMIN`; password resolution uses `ADMIN_PASSWORD`, then `landrop.admin.password`, then the configured random/default-password behavior. Production startup requires a non-default password of at least 12 characters.

---

### Control Center authentication boundary

Local desktop operators call `AdminControlCenterService` directly; permission comes from access to the server's OS desktop session. Desktop actions use audit actor `DESKTOP_OPERATOR` and do not store a web login token.

The retained administrative API signs administrators in through `POST /api/admin/auth/login`.
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

## 9. Native Desktop Administration

Backend `/admin` and `/admin/*` return HTTP 410 with `ADMIN_WEB_REMOVED`, including requests accepting HTML. Next.js admin routes, screens, navigation and admin login selection have been removed. Authenticated administrative API endpoints remain available with their existing RBAC checks.

`ServerDashboardPanel` opens `DesktopAdminPanel` through **Quản trị dữ liệu**. Its users, sessions, workspaces and membership forms use the same service/repositories as the retained API. Locking accounts, revoking sessions and deleting records retain service validation and operational audit logging. The final active system administrator cannot be disabled, deleted or demoted, including when the local operator has no acting-user UUID.

The read-only database explorer uses `AdminDataRepository.Dataset` projections for devices, channels, messages, folders, files and transfer history. It applies deterministic `created_at DESC, id` ordering, a maximum page size of 100, query timeouts and a read-only snapshot transaction. It excludes password/token hashes and storage paths; file rows describe metadata rather than downloadable file bytes. SQL is not supplied by the UI.

Database actions are disabled without persistent repositories. Audit/server views remain available in standalone mode. Queries and mutations run on a dedicated worker; stopped/replaced runtime callbacks are discarded. See [Server Desktop](../guides/SERVER_DESKTOP.md) for controls, configuration and screenshots.

---

## Verification & Test Matrix

- `AdminControlCenterTest` includes lifecycle and HTTP-route coverage for system-admin group CRUD and membership management.
- `DesktopAdminPanelTest` covers asynchronous queries, stale runtime results, safe error messages, standalone controls and sorted/filtered identity selection.
- `DesktopAdministrationServiceTest` covers final-active-admin protection and desktop disable/revoke/disconnect/audit behavior.
- `AdminDataRepositoryPostgresTest` is opt-in and requires `TEST_DATABASE_URL` or `landrop.test.database.url` naming a dedicated test database; it checks all migrated projections, pagination and pool reset.
- Targeted validation: `mvn -pl backend "-Dtest=AdminControlCenterTest#testWorkspaceDatabaseManagement,AdminControlCenterTest#testWorkspaceManagementHttpRoutes" test`.
- The broader legacy WebSocket test suite still needs its unauthenticated test clients migrated to the authenticated realtime contract before it can be treated as a release gate.
