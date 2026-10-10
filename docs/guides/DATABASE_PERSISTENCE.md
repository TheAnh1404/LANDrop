# Database persistence and recovery

The integrated Java server applies SQL migrations V1–V7 and verifies all columns used by authenticated APIs before opening HTTP or WebSocket listeners. A configured database that cannot connect or migrate aborts startup and releases partially started resources. Restarting the same server rebuilds repositories against the new connection pool.

Configuration resolves in this order: Java system properties, process environment, `.env.local`, then `.env`. Direct Java and desktop launches search the working directory and two parent directories. PostgreSQL URLs support `postgresql://`, `postgres://` and `jdbc:postgresql://`; percent-encode special characters in credentials. Connection logs omit credentials and URLs.

When no database is configured, standalone LAN operation remains available. `/health` and `/status` expose `persistenceAvailable: false`; persistent API requests return HTTP 503 with `PERSISTENCE_UNAVAILABLE`. Unknown API routes return 404. An absent registration endpoint cannot return a successful API catalog response.

## Persistent behavior

- Registration writes the user and hashed refresh session in one transaction. Failed session creation rolls back the user so registration can be retried. Concurrent duplicate registration returns 409.
- Login returns success after its session is saved. Refresh revokes the old session and saves the replacement atomically; failed replacement leaves the original usable, and concurrent refresh consumes it once.
- Browser clients enroll a device after authentication and restored sessions. Enrollment and session binding share one transaction; installation ownership checks are serialized. Each account uses its own stored browser installation identifier.
- Workspace membership, channel messages, file/folder metadata and transfer lifecycle records live in PostgreSQL. Soft deletions and session revocations survive server restart. P2P file bytes and direct peer conversations keep their existing browser/P2P storage behavior.
- Starting the integrated server preserves an existing administrator password. `ADMIN_PASSWORD` supplies the initial credential; it does not overwrite later password changes on every restart.
- A successful live login clears the Next.js demo marker. Reload restores its real session rather than displaying sample data.

`landrop_schema_history` records migration versions and SHA-256 checksums. A PostgreSQL transaction advisory lock serializes migration runs; pending migrations and their history are committed together. Existing schemas without this history first run the idempotent scripts. Do not edit an applied migration; add a new `V<number>__description.sql` instead.

`DatabaseCli verify` is read-only. Only the explicit `seed` command inserts demo fixtures. Never run seed or database integration tests against application data.

## Verify on a dedicated test database

Use a dedicated PostgreSQL database and its direct endpoint when schema isolation requires a session-specific `search_path`. Set both `DATABASE_URL` and `TEST_DATABASE_URL` to that test database in the test process; never copy a real application URL into test commands or reports.

```powershell
mvn -pl backend test "-Dlandrop.database.enabled=false"
# After configuring DATABASE_URL and TEST_DATABASE_URL for a dedicated test database:
mvn -pl backend test "-Dlandrop.test.postgres=true" "-Dtest=DatabaseIntegrationTest,AuthIntegrationTest,Foundation04AuthenticationDeviceTest,*PostgresTest"
npm test
npm --prefix frontend test
npm --prefix frontend run build
```

`ServerPersistencePostgresTest` creates disposable schemas, starts the integrated server on ephemeral ports, exercises client APIs, checks actual SQL records, restarts the same server, and verifies credentials, metadata, edits/deletions and logout. It also injects persistence failures to verify registration, refresh and device enrollment rollback. `ServerBootstrapPersistenceTest` verifies startup failure and accurate HTTP error responses without a live database.

After installing the changes, restart the running Java server and reload the client. A Next.js production server also needs the rebuilt frontend. Confirm `/health` reports `persistenceAvailable: true` before registering an account.

The local Chrome check on 2026-10-10 used a disposable schema in the dedicated test database and verified real registration/login, stored device enrollment, workspace membership, direct chat/file bytes and Next.js session restoration after reload. The SQL check found the users, sessions, devices and memberships created by those browser actions. See [P2P_DATA_CHANNEL.md](../api/P2P_DATA_CHANNEL.md) for transport scope and teardown limitations. The available Java runtime for this check was JDK 26; it does not establish JDK 21 runtime compatibility.
