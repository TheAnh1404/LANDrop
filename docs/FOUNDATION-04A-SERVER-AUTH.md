# FOUNDATION-04A — Server authentication and device enrollment

## Scope and identity model

FOUNDATION-04A binds an authenticated desktop runtime to four separate identities. It reuses the existing `User`, hashed refresh-token session, device, and Foundation WebSocket registry; it adds no second authentication system or workspace feature.

| Identity | Lifetime | Owner/source |
| --- | --- | --- |
| `userId` | Account | PostgreSQL `users`; derived from a verified token. |
| `sessionId` | Login/refresh session | PostgreSQL `refresh_tokens.id`; signed JWT `sid` points to this row. |
| `deviceId` | Enrolled installation | PostgreSQL `devices.id`; server assigns it. |
| `clientInstanceId` | Local installation | Client stores a stable installation identifier; server checks its ownership during enrollment and WebSocket binding. |
| `connectionId` | One live socket | Server registry; changes on reconnect. |
| Peer ID / endpoint | LAN transport | Never serves as an account, session, or persistent device identity. |

The canonical relations are `User → Sessions`, `User → Devices`, and `Session → Device` (nullable until enrollment). An authenticated runtime connection carries all four IDs plus its own `connectionId`. Online status comes from an open, authenticated registry entry; `devices.last_seen_at` is historical presence data.

## HTTP flow

1. `POST /api/auth/login` accepts `email`, `username`, or `identifier` with `password`. The existing `AuthService` verifies the PBKDF2 hash, account status, and client/admin boundary. It returns a public user DTO, short-lived access token, and refresh token. Any client-supplied `deviceId` on login is ignored.
2. A refresh session row stores only the **hash** of the refresh token, its `user_id`, nullable `device_id`, timestamps, and revocation state. Access tokens carry a signed `sid`. Runtime HTTP middleware verifies the JWT, active session row, user ownership, and current account status before allowing protected requests. A revoked session makes its still-unexpired access token unusable.
3. `GET /api/auth/me` derives the current user from that verified access token. It does not accept a client-selected user ID.
4. `POST /api/devices/enroll` requires the access token and `clientInstanceId`, `deviceName`, `platform`, `clientVersion`. A new installation gets a server device ID; the same user's installation updates metadata without changing ID. A different owner receives HTTP 409 `DEVICE_ALREADY_OWNED`. Recovery requires administrator review of the existing enrollment; the server never transfers ownership silently.
5. Enrollment upserts the device, then conditionally binds the current live session to its device ID. A session already bound to another device receives `DEVICE_CONFLICT`. `GET /api/devices` lists only the authenticated user's devices; `PATCH /api/devices/{id}` verifies ownership before renaming.
6. `POST /api/auth/logout` revokes only the current session, identified by a signed refresh credential or, when no refresh credential is supplied, the authenticated access token. It immediately closes a runtime connection bound to that session. Other sessions for the same user remain active.

Admin login remains at `POST /api/admin/auth/login`; a system admin cannot enter the ordinary client login/device flow. The admin console stores its access token in browser `sessionStorage` and calls logout before clearing its local session.

## Realtime binding and registry

The desktop client sends the access token in the WebSocket `Authorization: Bearer` upgrade header. Its `hello` includes protocol version, installation metadata, and the enrolled `deviceId`. The server validates the signed access token against the current session row, user status, device ownership, `clientInstanceId`, and the session's device binding **before** sending authenticated `welcome`. No `userId` in a client payload is accepted as authority.

An unauthenticated `hello` may appear briefly in the registry for diagnostics, with null user/session/device binding. It cannot use transfer or channel actions; a heartbeat without auth receives `AUTH_REQUIRED`. A new socket claiming an existing `clientInstanceId` cannot evict an authenticated socket until the replacement completes binding. Authenticated heartbeats recheck session/account status and update session/device `lastSeenAt` with a 15-second write throttle.

The Admin Clients view shows device, user, endpoint, connection, session, status, protocol, and last heartbeat. `GET /api/admin/users/{id}/identity` supplies a safe user detail view with devices, sessions, and current connections. The endpoint requires a live `SYSTEM_ADMIN` session and never includes password hashes or token hashes.

Admin session revoke sends `SESSION_REVOKED` to the bound socket when possible, closes it with code `4002`, and logs the action. Disabling an account revokes all its sessions, sends `ACCOUNT_DISABLED` to its sockets, closes them with code `4003`, and blocks new login. Server-side session checks also reject requests made after a socket disconnect or process restart.

## Stable errors and operational events

| Situation | Code |
| --- | --- |
| Invalid password or unknown login | `INVALID_CREDENTIALS` |
| Disabled account | `ACCOUNT_DISABLED` |
| Expired JWT or session | `SESSION_EXPIRED` |
| Revoked or missing session | `SESSION_REVOKED` |
| Missing authenticated realtime binding | `AUTH_REQUIRED` |
| Invalid authorization or identity mismatch | `UNAUTHORIZED` |
| Installation belongs to another user | `DEVICE_ALREADY_OWNED` |
| Session or installation is bound to a different device | `DEVICE_CONFLICT` |
| Device does not exist | `DEVICE_NOT_FOUND` |
| Unsupported WebSocket protocol | `PROTOCOL_VERSION_UNSUPPORTED` |

Operational logs record `USER_LOGIN`, `USER_LOGOUT`, `SESSION_CREATED`, `SESSION_REVOKED`, `DEVICE_ENROLLED`, `DEVICE_RECONNECTED`, `ACCOUNT_DISABLED`, `AUTH_BIND_SUCCESS`, and `AUTH_BIND_FAILED`. Passwords, raw tokens, their database hashes, and JWT secrets are omitted.

## Schema and migration

The existing [`V7__authentication_device_enrollment_phase04.sql`](../backend/src/main/resources/db/migration/V7__authentication_device_enrollment_phase04.sql) adds session device/last-seen/revocation columns and persistent installation metadata. [`prisma/schema.prisma`](../prisma/schema.prisma) already describes these fields and relations. This delivery changes no schema and adds no migration. Apply existing migrations to a dedicated database before enabling authenticated operation; no reset is required.

## Verification

From the repository root:

```bash
npm test
mvn test "-Dlandrop.database.enabled=false" "-Dtest=*,!PersistentChatTest,!FileManagementTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl frontend -am package -DskipTests
```

`landrop.database.enabled=false` prevents automatic loading of the local `.env` database URL for a safe standalone regression run. The two excluded legacy suites instantiate `DatabaseManager` during setup even with fake service data; they require a database and are not part of that standalone gate. Database integration and opt-in PostgreSQL suites need a dedicated test database. Live migration and multi-device acceptance must be checked separately on that database; they were not performed against the configured cloud database.

Run the application with `npm run server` and `npm run client`. The server script opens `/admin` after readiness unless browser opening is disabled. The fixed `admin` credential in the prior Control Center foundation remains a known deployment risk; do not expose this server to an untrusted network with that credential.
