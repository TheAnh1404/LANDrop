# REALTIME-01A backend audit

Date: 2026-10-08–09. Scope: Java gateway, auth/session, channel permissions,
REST chat persistence/broadcast, lifecycle and backend tests.

Status: **REALTIME-01A COMPLETE** — backend acceptance verified on Java 21.
The agreed shared contract is [REALTIME_01_CONTRACT](../api/REALTIME_01_CONTRACT.md).
The source findings below describe the pre-change audit. Final implementation and
test evidence follow the audit; baseline results alone are not acceptance proof.

## Gateways and reuse

There are two existing WebSocket server implementations and launcher paths:

| Gateway | Launcher | Auth/protocol | Chat writes |
| --- | --- | --- | --- |
| `server/realtime/FoundationRealtimeServer` | `server/bootstrap/ServerBootstrap`, integrated launcher | `hello`/`welcome`, optional header JWT binding, `EventEnvelope`, enrolled devices and linked refresh sessions when full auth dependencies are present | No WS message-send handler; REST `ChannelChatService` is mounted and its broadcaster is wired to this gateway |
| `realtime/RealtimeServer` | `auth/server/AuthHttpServer.main`, `start-auth.*` and native CLI demos | Native Authorization upgrade; browser Origin rejected; separate flat event shapes and `ConnectionRegistry` | WS send/edit/delete plus the existing chat service via `JdbcRealtimeStore` |

They are alternative launchers, not two listeners started by `ServerBootstrap`.
Do not start both on port 3001. Extend the integrated gateway rather than add a
third server. Reuse `EventEnvelope`, `ProtocolConstants`, `ClientRegistry`,
`JwtService`, `SessionVerifier`, persistent refresh sessions, `ChannelChatService`,
`ChannelBroadcaster`, `ChatHttpServer` and its canonical DTOs. Existing native
gateway tests do not prove integrated browser gateway behavior.

## Pre-change source findings and root causes

1. `FoundationRealtimeServer.handleAuth` rejects event auth when all four auth
   dependencies are configured, instructing clients to send the Authorization
   header during hello. Browser WebSocket cannot set that custom header. Hello
   without credentials can allocate a connection/welcome, but it remains
   unauthenticated and cannot join a chat room.
2. Native hello authentication requires an enrolled device matching installation,
   User and persistent session. Current browser login/register issue sessions
   with `deviceId=null`; frontend session code has no device enrollment step.
   Simply enabling the current event handler would still fail browser login.
3. Browser frontend currently uses REST polling. Gateway room names already are
   `channel.join`/`channel.joined` and `channel.leave`/`channel.left`, not the
   reference `channel.subscribe` names. Broadcast wraps the existing service
   event in `EventEnvelope`, so the canonical DTO is at `payload.message`.
   `docs/api/REALTIME_CHAT.md` describes the other native gateway and flat shapes.
4. REST create/edit/delete already call `ChannelChatService`, commit before
   dispatch, and invoke the broadcaster wired in `ServerBootstrap.start`.
   No second integrated WS write pipeline is needed. History/cursor endpoints
   can remain unchanged.
5. Rooms use sets of connection IDs. Join checks User workspace membership;
   each fanout checks current membership again. Delivery is channel scoped,
   not global. Disconnect removes rooms. Repeat join adds no duplicate ID.
6. Session validity currently is checked on heartbeat, but not on room join or
   broadcast. Access expiry is not retained on `ClientConnection`. An idle
   connection can continue receiving chat after token expiry/revoke until some
   other action checks its session. Idle session/account revalidation is absent.
7. `ClientRegistry.sessionToConnectionId` retains only the last socket for a
   session. `disconnectSession` closes only that socket. Multiple tabs sharing a
   refresh session need a session-to-set index and complete revocation cleanup.
8. Client-instance replacement is deferred until identity binding, which protects
   an authenticated socket from an unauthenticated hello. The registry binding
   API itself does not prove previous identity ownership; browser event auth
   must not let another User evict a connection by claiming its instance ID.
   Same-instance reconnect and independent User sessions need regression tests.
9. Only registered post-hello connections are swept for heartbeat inactivity.
   A socket that never sends hello is absent from the registry and application
   timeout sweep. There is no separate authentication completion deadline.
10. Integrated gateway currently performs no Origin or URL/path/query validation.
    Native demo gateway rejects browser Origin and query credentials. Browser
    integration needs an explicit allowed-Origin configuration; token URLs
    must not become a workaround for custom headers.
11. `ChannelChatService.requireChannelAccess` accepts OWNER/ADMIN/MEMBER and
    rejects GUEST/non-members. It reads `is_private` but has no separate private
    ACL check. Prisma/SQL have no private-channel-members relation. The native
    store has a different public-only policy. This must be explicitly documented
    and kept consistent with the agreed integration policy.
12. Create idempotency uses the unique `(user_id, client_message_id)` index and
    checks existing channel/content; it currently does not compare reply target.
    Broadcast is suppressed for duplicate insertion. Client must upsert canonical
    IDs rather than append REST response and live event separately. Requests
    without a clientMessageId remain non-idempotent by the existing API contract.
13. Broadcast executes inside the service's transaction try/catch after commit.
    A broadcaster RuntimeException triggers rollback/propagation despite a
    committed message. Fanout errors must not misreport persistence failure.
    Commit failure itself already prevents the broadcaster call.
14. Registry cleanup covers close and heartbeat timeout; subscription cleanup
    for timeout/revocation/instance replacement mostly depends on subsequent
    onClose. Shutdown notifies active registered clients with SERVER_SHUTDOWN.
    Explicit cleanup should include pending sockets and room sets.
15. Existing diagnostics include CLIENT_CONNECTED/DISCONNECTED, handshake,
    heartbeat, AUTH_BIND_SUCCESS/FAILED. There are no dedicated room/fanout log
    categories. Do not add chat content or credentials to operational logs;
    arbitrary client close reasons should not be treated as safe diagnostics.

## Existing signaling and configuration

The integrated switch has `webrtc.signal`, `webrtc.offer`, `webrtc.answer` and
`webrtc.ice_candidate` relay handlers and transfer control events. They must
remain compatible. REALTIME-01A does not implement RTC peer connections or
file transport. The current signaling regression tests standalone foundation
mode; it is not evidence of authenticated browser signaling acceptance.

ServerConfig defaults host 0.0.0.0, HTTP 3000, WS 3001, protocol 1, heartbeat
15000 ms, inactivity timeout 45000 ms. WS configuration uses
`landrop.server.ws.port` / `REALTIME_PORT`, then the current `WS_PORT` fallback
when the resolved value equals the default. Canonical proposed browser URL is
`ws://<host>:<ws-port>/`; local frontend integration should use HTTP/WS 3100/3101.
No reverse-proxy configuration is checked into the repo. Deployment must forward
Upgrade/Connection and preserve Origin, use wss for an HTTPS frontend, and set
proxy idle timeout above the heartbeat timeout.

## Baseline test evidence

Command actually run from repository root:

```powershell
mvn -pl backend test "-Dlandrop.database.enabled=false" "-Dtest=Foundation04RealtimeBindingTest,ClientRegistryAuthenticationTest,LandropServerFoundationTest,WebRTCSignalingRelayTest,RealtimeIntegrationTest,ConnectionRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=0"
```

First sandbox run failed because Surefire could not create its temporary output
files; callback failures also caused test timeouts. Re-run outside the sandbox
succeeded: **29 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS.

| Suite | Tests | Result |
| --- | ---: | --- |
| ConnectionRegistryTest | 1 | Passed |
| RealtimeIntegrationTest | 6 | Passed (native gateway) |
| ClientRegistryAuthenticationTest | 2 | Passed |
| Foundation04RealtimeBindingTest | 2 | Passed (header auth/enrolled device) |
| LandropServerFoundationTest | 17 | Passed |
| WebRTCSignalingRelayTest | 1 | Passed (existing standalone relay) |

Runtime discovered: Maven 3.9.16, Java 26.0.2.1; Maven compiler targets release
21. JDK 21 was not present in the checked Java/Tools installation directories.
This baseline is not a Java 21 runtime verification or a fresh clean/package
acceptance build. No dedicated PostgreSQL DB was used; persistence and restart
acceptance have not been run. Docker and psql were not found on PATH.

## Coordination record

AGY CLI was discovered on the local machine. The active REALTIME-01B conversation
was identified from workspace-scoped CLI metadata and sent a read-only contract
coordination request using its explicit conversation ID. After an initial
45-second timeout, Client replied ACCEPT to the focused follow-up before Java
implementation began. Client later acknowledged Origin configuration, REST
history order and preserving the preceding auth error on close code 4002.
Client frontend changes visible in the shared working tree belong to REALTIME-01B;
Server did not edit or validate those files as part of REALTIME-01A.

## Final implementation and changed files

| File | Change |
| --- | --- |
| `backend/.../server/realtime/FoundationRealtimeServer.java` | Browser `auth` enabled; signed session-derived optional device; exact Origin allowlist and query rejection; auth deadline; idle session/access-expiry validation; revalidation before room delivery; atomic room mutation; close/reconnect/stop cleanup; structured diagnostics |
| `backend/.../server/clients/ClientConnection.java` | Store access expiry; publish User identity after other identity fields |
| `backend/.../server/clients/ClientRegistry.java` | Session maps to a set of sockets; revoke closes every tab; synchronized index mutations; prevent another identity evicting a claimed instance |
| `backend/.../chat/service/ChannelChatService.java` | Safe dispatch after commit; fanout exception cannot turn successful write into REST failure; idempotency compares reply target too |
| `backend/.../server/logging/ServerEvent.java` | WS/auth/room/broadcast/revoke diagnostic categories |
| `backend/src/test/java/.../server/FoundationWsClient.java` | URI/header override for browser-style Origin upgrade tests |
| `backend/src/test/java/.../server/clients/ClientRegistryAuthenticationTest.java` | Multi-tab session revoke and cross-user instance ownership tests |
| `backend/src/test/java/.../server/Realtime01BrowserGatewayTest.java` | 12 integrated-gateway socket/auth/room/lifecycle tests with controlled stores |
| `backend/src/test/java/.../server/Realtime01PostgresTest.java` | 7 real PostgreSQL + existing REST/auth/admin + browser-style socket acceptance tests |
| `.env.example` | `WS_ALLOWED_ORIGINS` and `WS_AUTH_TIMEOUT_MS` reference settings |
| `docs/api/REALTIME_01_CONTRACT.md` | Single agreed browser contract, payload examples, lifecycle and Client integration |
| `docs/api/REALTIME_CHAT.md` | Mark existing native launcher document as legacy and point browser integrations to agreed contract |
| `docs/reports/REALTIME_01A_BACKEND_AUDIT.md` | Audit, changes, verification and remaining integration work |

No new gateway, DB migration, WS browser mutation command, RTC transport or
workspace feature is introduced. Existing native header/enrolled-device hello
and standalone signaling remain compatible. Actual production REST endpoints
and ChatMessageDto are retained, including existing history ordering/pagination.

## Final URL, auth, permissions and write flow

Local integration: `ws://localhost:3101/` with backend HTTP 3100, frontend 3000.
Default WS port remains 3001. `WS_ALLOWED_ORIGINS` is an exact comma-separated
list; development defaults permit localhost:3000 and 127.0.0.1:3000. Configure
actual LAN/frontend origins explicitly; HTTPS frontend requires a TLS WS proxy.

Browser: hello -> welcome -> auth with current REST accessToken -> auth_ack ->
channel.join -> channel.joined. Token URLs are rejected. JWT signature/type/expiry,
linked persistent session and current User are validated. Browser chat supports
device-less login sessions; enrolled device ownership comes from the validated
session, never client-claimed User/workspace/role. Duplicate bind cannot switch
identity. Native header hello keeps enrolled-installation checks.

Rooms reuse the existing REST service policy: OWNER/ADMIN/MEMBER in the channel's
workspace, no GUEST or outsider; current service has no separate private-channel
ACL. Membership is rechecked before delivery and a denied room is removed.
Session/account validity is checked before subscribe, runtime commands, fanout
and on a scheduled idle sweep. Repeated joins are sets, not duplicate listeners.

Writes stay REST -> authenticated User -> ChannelChatService permission check ->
PostgreSQL transaction/commit -> canonical message event -> authorized room.
Sender receives the same DTO. Same clientMessageId/channel/content/reply retry
returns the original row without another fanout; conflicting reuse returns 409.
Failed commit never dispatches; failed fanout after commit retains REST success
and is recovered via history. Edit/delete retain ownership checks and emit the
existing message.updated/message.deleted payloads. See the contract for JSON.

Connection identity is connection ID -> persistent session -> User -> optional
verified device -> authorized room set. Each tab should have a distinct instance
ID; same authenticated instance replaces its old socket. Revoke closes every
socket of that session and preserves independent sessions. Disconnect, timeout,
replacement and stop remove registration/subscriptions. Pre-hello/pre-auth
deadline is 10 s by default. Application heartbeat remains 15 s / 45 s defaults.
Shutdown stops new monitor work and waits for the monitor to terminate before
returning; the final real-DB teardown completed without the prior in-flight
heartbeat query/pool-close error.
Access expiry emits UNAUTHORIZED then closes only that access-bound connection;
refresh uses REST then reconnect/rejoin. Idle checks depend on DB availability.

Operational events include WS_CONNECTED, WS_AUTHENTICATED, WS_AUTH_FAILED,
CHANNEL_SUBSCRIBED, CHANNEL_SUBSCRIBE_DENIED, MESSAGE_BROADCAST, WS_DISCONNECTED,
SESSION_REVOKED. New logs contain connection/channel IDs and event/counts, not
chat text or token/password payloads; untrusted hello metadata and close reason
are omitted from gateway lifecycle logs.

## Final build and acceptance evidence

JDK 21 was downloaded into ignored `tmp/realtime01-jdk21`; system Java was not
changed. Verified Maven test report properties: Java 21.0.12.1, Maven 3.9.16.

```powershell
$env:JAVA_HOME = 'C:\TheAnhproject\LANDrop\tmp\realtime01-jdk21\jdk-21.0.12.1+1'
mvn -pl backend clean verify "-Dlandrop.database.enabled=false" "-Dtest=*,!PersistentChatTest,!FileManagementTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DforkCount=0"
mvn -pl backend verify "-Dlandrop.database.enabled=false" "-Dtest=Realtime01BrowserGatewayTest,Foundation04RealtimeBindingTest,LandropServerFoundationTest,WebRTCSignalingRelayTest,ClientRegistryAuthenticationTest" "-DforkCount=0"
mvn -pl backend test "-Dlandrop.test.postgres=true" "-Dtest=Realtime01PostgresTest" "-DforkCount=0"
```

Clean verify actually passed: 295 tests reported, **262 passed, 33 skipped,
0 failures/errors**, packaged `backend/target/landrop-0.1.0-SNAPSHOT.jar`.
This includes 12 browser-gateway tests, 4 registry auth tests, header-auth,
auth/device/HTTP/session tests, admin foundation and existing signaling.
Two pre-existing suites are excluded by the repository no-DB regression command:
PersistentChatTest and FileManagementTest. The 33 reported skips at that build
were AdminControlCenterTest (4), PersistentChatPostgresTest (9),
FileManagementPostgresTest (9), RealtimePostgresTest (3), WorkspacePostgresTest (3)
and the then-5 PostgreSQL REALTIME-01 tests (run separately afterward, with two
additional auth/admin scenarios added). These are not blanket PostgreSQL/admin
acceptance claims; the relevant new 7-case suite exercises the actual handlers
and persistent revoke/disable service hooks in its own schema.

After the final shutdown change, focused verify passed **36 tests, 0 failures,
0 errors, 0 skipped** and rebuilt the JAR. Final PostgreSQL acceptance passed
**7 tests, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS, 2026-10-09
00:56:51 +07:00). Surefire report properties confirm Java 21.0.12.1 for all
six suites. The PostgreSQL suite verifies:

| Scenario | Evidence |
| --- | --- |
| REST register/login and browser auth without device | Real AuthHttpHandler/AuthService/session repository + Origin/no custom auth header socket |
| Invalid credentials/Origin/missing auth; signed identity; disabled/expired/revoked | Controlled real-socket gateway suite; real login negative case; persistent admin hook cases |
| Authorized/denied rooms and no wrong channel/workspace delivery | Actual ChannelChatService permission SQL and three different rooms, including outsider |
| Two-user canonical fanout and sender deduplication | Concurrent identical REST POSTs return same DTO, SQL row count 1, one event per subscriber |
| Edit/delete/reply/idempotency/history | Existing REST routes/DTOs, ownership denial, update/delete events, conflicting reply target |
| Persistence failure | Real PostgreSQL transaction with injected failing commit rolls back; SQL count 0, no event |
| Post-commit fanout failure | Injected broadcaster exception, REST 201, SQL row retained and recoverable history |
| Membership removal/logout/Admin revoke/disable | Real DB membership/session/account changes; all shared tabs close; independent session preserved where appropriate |
| Reconnect/heartbeat/auth deadline/stale replacement/shutdown | Controlled gateway tests use real sockets and close-code/registry/room assertions |
| Restart/recovery | Stop and recreate HTTP/gateway instances, reauth/rejoin, reload persisted IDs and paginate older history |
| REST/admin/signaling compatibility | Full no-DB Java 21 regression plus scoped real-DB handler/service acceptance |

User explicitly authorized the configured `.env` database. Test creates a random
`landrop_rt01_<32 hex>` schema, applies existing migrations scoped to it and drops
only that validated schema in teardown, checking it no longer exists. Initial
pooler runs exposed unstable search_path; the final harness follows existing
tests and uses the corresponding Neon direct endpoint. A read/cleanup audit of
public fixture IDs in the exact two affected run windows found 0 public test
users, and 0 remaining REALTIME-01 schemas. No public data was deleted. All future
runs of this suite use the direct endpoint for stable schema isolation.

## Remaining Client integration work and limits

No backend wire-contract decision remains pending. Client acknowledged the
4002/preceding-error rule, but its frontend implementation is outside this
backend validation. REALTIME-01B must verify real UI/browser interactions,
single-flight refresh/reconnect, event/history reconciliation and tab identity.
Configure LAN origins and proxy forwarding as described in the shared contract.
Current workspace-role access to private-flag channels is the existing REST
policy; a separate private ACL is not implemented. Fanout is process local,
non-durable; REST history is required to recover a crash after commit. Distributed
fanout and RTC/file transport are outside REALTIME-01A.

**REALTIME-01A COMPLETE.** Backend acceptance is proven by the results above;
actual UI integration remains REALTIME-01B work. Stop at REALTIME-01A; do not
begin REALTIME-02.
