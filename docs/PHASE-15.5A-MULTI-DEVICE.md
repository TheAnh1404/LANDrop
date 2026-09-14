# Phase 15.5A — multi-machine test guide

This harness tests backend identity and persisted chat independently of JavaFX. It uses actual Phase 15A login/refresh/me/logout endpoints and authenticated WebSocket connections. JDK 21+ and Maven are required on each machine. It does not require Node.js or hardcoded peerA/peerB users.

## Shared backend

Only the backend machine needs DATABASE_URL and JWT secrets in its ignored root .env. Clients need the backend URL, channel ID, and their own login.

Add these non-secret settings to the backend .env as needed:

```dotenv
BIND_HOST=0.0.0.0
AUTH_PORT=3000
REALTIME_PORT=3001
```

Keep JWT_ACCESS_SECRET and JWT_REFRESH_SECRET stable across backend restarts, and set independent random secrets instead of the Phase 15A development defaults.

Apply migration V3 (additive message idempotency column/index) using the existing migrator, then start the shared backend:

```sh
mvn -pl backend compile exec:java -Dexec.mainClass=com.landrop.database.DatabaseCli -Dexec.args=migrate
bash start-auth.sh
```

On Windows PowerShell/CMD, run the same Maven command, then:

```bat
.\start-auth.bat
```

Use one backend for all test machines. Allow inbound TCP 3000 and 3001 on its LAN firewall. Obtain its actual LAN IP (for example 192.168.1.10). Verify from another machine:

```sh
curl http://192.168.1.10:3000/api/auth/health
```

The migration changes database schema; starting the server does not automatically migrate or seed. Do not run the legacy DatabaseCli verify command merely for connectivity: it also seeds data.

## Test accounts and channel

Use three registered users and an existing public channel to which all three have existing membership. Registration alone does not grant access to arbitrary channels. An administrator can inspect eligible channels in a development database:

```sql
SELECT c.id AS channel_id, c.name, u.email
FROM channels c
JOIN workspace_members m ON m.workspace_id = c.workspace_id
JOIN users u ON u.id = m.user_id
WHERE NOT c.is_private
ORDER BY c.id, u.email;
```

If no suitable data exists, prepare accounts with the existing Register API and provision a **test fixture** with the database administrator. This phase does not add Workspace or member-management endpoints. The automated PostgreSQL test creates its own complete fixture and removes it afterwards; use that test to validate the backend without modifying public fixture data.

## Clients on separate machines

From the repository root, replace the host and CHANNEL_UUID in each command. The final argument is a different local refresh-token file for each simulated device. Do not copy it between devices or backends.

macOS/Linux:

```sh
bash start-realtime-client.sh http://192.168.1.10:3000 ws://192.168.1.10:3001 CHANNEL_UUID Laptop .landrop-session-laptop
```

Windows PowerShell/CMD:

```bat
.\start-realtime-client.bat http://192.168.1.10:3000 ws://192.168.1.10:3001 CHANNEL_UUID Desktop .landrop-session-desktop
```

On the first run enter email/username and password at the terminal prompt. The direct Java launcher hides password input when a console is available. Subsequent runs restore the refresh token, call /auth/me, reconnect, and fetch history. The harness prints verified userId, socketId and canonical senderId; it never prints tokens.

The session file contains a credential. It is ignored by git; POSIX systems create it with owner-only read/write permissions. On Windows use your private user directory and its normal user ACL. Access tokens and passwords are not persisted.

Commands inside the client:

- Any text: send a message; display only the canonical server event.
- /history: latest 50 persisted messages; /history CURSOR: older page using the printed cursor.
- /reconnect: refresh credentials, reconnect and fetch history.
- /retry: resend unacknowledged messages with their original clientMessageId.
- /quit: close this socket; keep this device's refresh session for restart.
- /logout: revoke this device's refresh session, delete its session file, close the socket.

The sample client deduplicates live/history messages by canonical id. Pending messages are kept in memory; restart the harness to verify persisted history, not offline pending-send durability.

## Matrix A–J

| Test | Procedure | Expected |
| --- | --- | --- |
| A | Machine 1 logs in as User1; Machine 2 as User2 | Distinct userId, both connected |
| B | User1 sends hello | Both receive the same canonical id; senderId=User1 |
| C | User2 replies | Both receive it; senderId=User2 |
| D | Machine 3 logs in as User3, same channel | All three receive new messages; history recovers earlier messages |
| E | Another terminal/device logs in as User1 using a separate session file | Same userId, different socketId, both sessions coexist |
| F | User1 Device1 sends | User1 Device3 sees the update as User1 |
| G | /quit on User1 Device1 | User1 Device3 continues sending/receiving |
| H | /reconnect | Persisted chat is still available without duplicate rows |
| I | /quit then rerun the client with its same session file | Refresh + /auth/me + history restore succeeds |
| J | WebSocket without Authorization, with invalid/expired/refresh token, or with no channel membership | Upgrade rejected, no registered socket |

Tests A–J are exercised programmatically on loopback. Physical Windows/LAN machines and JavaFX rendering require manual execution; backend-only checks do not certify a separate frontend implementation.

## Automated commands

```sh
mvn -o -pl backend -am test -Dtest=RealtimeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl backend -am test -Dtest=RealtimePostgresTest -Dlandrop.test.postgres=true -Dsurefire.failIfNoSpecifiedTests=false
```

The first command uses real WebSockets/JWTs with an in-memory test repository. The second uses actual auth HTTP, JWT, Neon/PostgreSQL, real repositories and WebSockets. It creates a random landrop_rt_test_* schema, applies test-scoped V1–V3, provisions three test users and channel membership, verifies data/refresh races and listener restart, then drops only that schema.

The PostgreSQL test is disabled unless explicitly opted in. For Neon it uses the direct endpoint of the configured database, because schema/search_path isolation requires session-level state; see [Neon pooling documentation](https://neon.com/docs/connect/connection-pooling). No public data is read for assertions or modified. It requires permission to create/drop its own test schema. -o requires Maven dependencies already cached; omit on first use.

See [wire contract](PHASE-15.5A-REALTIME-CONTRACT.md) for exact JSON, authentication, pagination and access-expiry behavior.
