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
- `AdminBootstrapService` initializes the system administrator on server startup:
  - Honors `ADMIN_USERNAME`, `ADMIN_PASSWORD`, and `ADMIN_EMAIL` environment variables if set.
  - Generates secure temporary credentials if no system admin exists.
  - Never logs plaintext credentials.

### Administrative password recovery
If the generated password is lost, reset the existing administrator explicitly. The
password is read from the environment and is never passed as a command-line argument:

```powershell
$env:LANDROP_ADMIN_RESET_PASSWORD = "new-password-8+"
./mvnw -q -pl backend exec:java "-Dexec.mainClass=com.landrop.admin.security.AdminPasswordResetMain"
```

The command updates the oldest `SYSTEM_ADMIN` account and exits. It does not print the
password. Clear the environment variable after use.

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
- `POST /api/admin/users/{id}/status`: Toggle user status (`ACTIVE` vs `DISABLED`).
  - Disabling a user terminates all their active refresh token sessions immediately.
  - Protects against self-disablement.
  - Prevents disabling the last remaining `SYSTEM_ADMIN`.
- `GET /api/admin/sessions`: List all active user sessions across devices.
- `DELETE /api/admin/sessions/{id}`: Administratively revoke a specific refresh token session.

---

## 6. Realtime Client Control

### Endpoints
- `GET /api/admin/clients`: List connected WebSocket clients from `ClientRegistry` (remote address, connection ID, device name, client instance ID, handshake time, last heartbeat).
- `POST /api/admin/clients/{id}/disconnect`: Administratively terminate client connection.
  - Sends close frame code `4001` with reason payload.
  - Unregisters client from active registry.
  - Emits audit log `ADMIN_CLIENT_DISCONNECT`.

---

## 7. Operational Audit Logging

### Architecture
- Fixed-capacity circular ring buffer (1,000 entries) managed by `OperationalLogService`.
- Structured records: `id`, `timestamp`, `level` (INFO, WARN, ERROR), `eventType`, `actor`, `target`, `details`.
- Automatic regex-based credential masking for passwords, secrets, Bearer tokens, and auth headers.
- Filterable by level, event type, and free-text search with limit and offset.

### GET `/api/admin/logs`
- Query parameters: `limit`, `offset`, `level`, `eventType`, `search`.

---

## 8. Embedded Administrative Web Console

Served directly from the HTTP server at `/admin`:
- Single-page vanilla HTML5, CSS3, and JavaScript app with zero external CDN dependencies.
- Dark theme inspired by modern enterprise cloud consoles.
- Embedded JWT storage in `sessionStorage` with interactive login dialog.
- Responsive live tabs:
  1. **Dashboard**: Metrics cards, server status, quick action links.
  2. **Users**: User list, filter, enable/disable toggle.
  3. **Clients**: Connected devices table, metadata, disconnect button.
  4. **Sessions**: Active session explorer, revoke action.
  5. **Server & Network**: Active interfaces, current vs pending configuration editor, restart warning banner.
  6. **Logs**: Live audit log viewer with search, level filtering, and pagination.

---

## Verification & Test Matrix

- `AdminControlCenterTest`: 8 unit and integration test suites covering all 03A requirements with 100% pass rate.
- Full project regression: 102 frontend and backend integration tests pass with 0 failures, 0 errors.
