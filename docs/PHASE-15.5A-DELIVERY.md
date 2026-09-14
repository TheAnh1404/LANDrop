# Phase 15.5A — backend delivery

Implemented authenticated channel chat with multiple users and multiple devices per user. This is backend/realtime work only; no Workspace CRUD, member management API, tasks, notifications, search, activity log, or Phase 16 features were added.

## Files created

Under backend/src/main/java/com/landrop/realtime/:

- SocketPrincipal.java
- SocketAuthenticator.java
- ConnectionRegistry.java
- RealtimeStore.java
- JdbcRealtimeStore.java
- RealtimeException.java
- RealtimeServer.java
- cli/RealtimeClient.java

Additional files:

- backend/src/main/resources/db/migration/V3__realtime_message_identity.sql
- backend/src/test/java/com/landrop/realtime/WsProbe.java
- backend/src/test/java/com/landrop/realtime/ConnectionRegistryTest.java
- backend/src/test/java/com/landrop/realtime/RealtimeIntegrationTest.java
- backend/src/test/java/com/landrop/realtime/RealtimePostgresTest.java
- start-realtime-client.sh
- start-realtime-client.bat
- docs/PHASE-15.5A-REALTIME-CONTRACT.md
- docs/PHASE-15.5A-MULTI-DEVICE.md
- docs/PHASE-15.5A-DELIVERY.md

## Files modified by this backend task

- backend/pom.xml: Java-WebSocket 1.6.0 dependency.
- backend/.../auth/config/AuthConfig.java: environment/.env bind host and listener ports.
- backend/.../auth/server/AuthHttpServer.java: compose auth and realtime listeners in the shared backend launcher; stop both on shutdown.
- backend/.../auth/service/AuthService.java: check atomic refresh-token revocation result; concurrent reuse cannot issue two successors.
- backend/.../chat/ChatMessage.java: clarify existing sender is LAN-only and not a verified product identity.
- backend/.../database/DatabaseManager.java: independent schema-scoped pool for isolated integration tests.
- backend/.../database/migration/DatabaseMigrator.java: include additive V3.
- start-auth.sh and start-auth.bat: compile before launching shared auth/realtime backend.
- .env.example: BIND_HOST and REALTIME_PORT placeholders.
- .gitignore: ignore local .landrop-session-* harness credentials.
- backend/README.md and README.md: backend status and handoff links.

The working checkout also contains parallel frontend edits and a pre-existing MultiPeerDownloadTest.java change. Those are not claimed as this backend delivery and were not reverted.

## Identity audit and architecture

Before this phase there was no WebSocket registry. peerA/peerB/peerName existed in LAN demos, transport messages, transfer fixtures and the desktop presentation, not as authenticated database User identities.

The new product chat path contains no peer-name lookup or peer-derived sender. RealtimeServer rejects senderId/userId/peerName fields supplied in commands. It uses only SocketPrincipal.userId, resolved from a verified access JWT and an existing User record. JdbcRealtimeStore persists that value into the existing messages.user_id FK; the wire response exposes it as senderId.

```text
User.id
  -> independent Phase 15 refresh sessions
  -> multiple access JWTs
  -> multiple socketId connections
  -> existing channel
  -> validate membership and content
  -> PostgreSQL transaction + canonical message
  -> commit
  -> broadcast to all eligible channel sockets (including sender devices)
```

The synchronized registry maps each user to multiple socket connections. Disconnect deletes only its socketId; parallel add/remove operations preserve siblings. UUIDs for User, JWT credential, socket, and LAN peer remain distinct. Device labels are diagnostic text, not authorization.

## Authentication and sessions

WebSocket Authorization uses Bearer access tokens in the HTTP handshake. Invalid, expired, refresh-type, unknown-user and inaccessible-channel credentials are rejected before upgrade/registry insertion. HTTP 404 on rejection is Java-WebSocket's handshake behavior, documented for clients.

Phase 15A already supports multiple refresh-token records per user. Login adds one; refresh/logout affect one presented token. Refresh now uses the database conditional update as a single-winner guard against concurrent reuse. Socket expiry uses access-token expiry; clients refresh over HTTP then reconnect and reload history.

The current JWT has no stable session ID linking access tokens to refresh rows. The verified jti is exposed as accessTokenId, never mislabeled sessionId. Logout invalidates refresh credentials but an already issued access token stays valid until expiry, including on an open socket. Immediate session-specific revocation is not claimed.

## Persistence and canonical events

The database supplies message UUID and timestamps. All recipients receive the same committed message, including two devices belonging to the sender. Migration V3 adds a nullable client_message_id to existing messages and a unique index with user_id. Same-ID retries return the original row and avoid repeat broadcast. Different content/channel with that ID is rejected.

History is queried from PostgreSQL with stable created_at/id keyset pagination. Reopening clients or replacing server/store instances recovers messages. The client harness displays only canonical IDs and deduplicates history/live events. Commit-to-broadcast delivery is not a durable event queue; reconnect/history recovers a crash in that interval.

Existing channel membership is read for authorization. Private channels fail closed because no channel-specific ACL exists. There are no Workspace/member administration routes in this implementation.

## Verification results — 2026-09-14

| Check | Result |
| --- | --- |
| Final backend test command | 192 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS |
| RealtimeIntegrationTest | 6 real-WebSocket/JWT tests passed |
| ConnectionRegistryTest | 1 parallel add/remove test passed |
| RealtimePostgresTest | 3 real Neon/auth/repository/WebSocket tests passed |
| Core/chat/discovery/transfer/JWT regression | Included in final backend command |
| Script syntax | bash -n passed |
| Client launcher | No-argument usage screen opened successfully |
| Git whitespace check | git diff --check passed |
| Physical multi-machine/Windows run | Prepared, not executed on physical remote machines |
| JavaFX restart/rendering | Outside this backend test run; CLI restart/refresh/history equivalents tested |

The final command excludes legacy DatabaseIntegrationTest and AuthIntegrationTest because they migrate/seed or create data in the default database schema. The new opt-in PostgreSQL suite runs real registration/login/refresh/logout/me and chat inside its own random landrop_rt_test_* schema, including concurrent refresh and send retries. It drops only that schema after testing. Public migration/seed was not executed by this task.

Initial sandbox socket/cache failures were rerun with the required permissions. The PostgreSQL test initially refused to proceed when schema isolation resolved to public; it now uses the same Neon database's direct endpoint plus a schema-configured independent Hikari pool. Final assertions and cleanup passed.

## Exact verification commands used

From repository root:

```sh
mvn -pl backend -am compile -DskipTests
mvn -o -pl backend -am compile -DskipTests
mvn -o -pl backend -am test -Dtest=RealtimeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl backend -am test -Dtest=RealtimePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl backend -am test -Dtest=RealtimePostgresTest -Dlandrop.test.postgres=true -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl backend -am test '-Dtest=*,!DatabaseIntegrationTest,!AuthIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -Dlandrop.transfer.download.dir=/private/tmp/LANDrop-phase155-tests
mvn -o -pl backend -am test '-Dtest=*,!DatabaseIntegrationTest,!AuthIntegrationTest' -Dlandrop.test.postgres=true -Dsurefire.failIfNoSpecifiedTests=false -Dlandrop.transfer.download.dir=/private/tmp/LANDrop-phase155-tests
mvn -o -pl backend -am package -DskipTests
bash -n start-auth.sh start-realtime-client.sh
bash start-realtime-client.sh
git diff --check
```

Long test/build outputs were redirected to /private/tmp/landrop155-*.log during execution. Final test log: /private/tmp/landrop155-final-tests.log. Schema test log: /private/tmp/landrop155-postgres.log. Reports are also under backend/target/surefire-reports/.

## Running on multiple machines

Follow [multi-device instructions and matrix A–J](PHASE-15.5A-MULTI-DEVICE.md). Apply V3 to the target development database before starting the shared server. Clients connect using the backend LAN IP on auth port 3000 and WS port 3001; no hardcoded localhost is required.

## Known limits

- Single shared backend process; registry/fanout is not distributed across backend replicas.
- Existing public-channel membership required; private channels denied pending an actual ACL model.
- Access JWT validity after logout follows existing Phase 15A expiry semantics.
- JavaFX integration and real Windows/LAN runs are not certified by CLI/backend tests.
- HTTP/WS here targets trusted LAN development; no production deployment or TLS infrastructure added.
- Frontend integration must follow the exact [wire contract](PHASE-15.5A-REALTIME-CONTRACT.md), including header auth, channel URL, canonical message upsert, and refresh/reconnect.

PHASE 15.5A COMPLETE
