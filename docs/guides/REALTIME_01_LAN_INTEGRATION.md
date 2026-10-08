# REALTIME-01 Server / Client integration

Owners: Codex Server REALTIME-01A and existing AGY Client REALTIME-01B.
Date: 2026-10-09. Integration branch: `feat/realtime-01-integration`.

## Coordination and Git

Both agents share one working tree on this machine. Server audited the actual
Java gateways; Client audited Chat/polling. Client accepted
[REALTIME_01_CONTRACT.md](../api/REALTIME_01_CONTRACT.md) before Java wire changes.
That file is the single protocol source. All integration fixes retain the agreed
event names and canonical `payload.message`; there are no competing protocols.

Server owns Java/config/launcher/backend tests/shared docs. AGY owns frontend
RealtimeClient, session integration, Chat state and frontend tests. Server owns
Git operations in the shared working tree so concurrent agents do not switch
branches or stage each other's incomplete edits. Backend commit: `ae86b07`;
Client commit: `0aaea7d`.
Frontend and integration commits are recorded separately after their checks pass.
AGY implemented the Client changes through file edits; its headless command
permission was denied, so Codex ran the tests/build in the authorized terminal.
Final integration fixes by Codex added the missing `AuthAckPayload` type import
and stopped refresh retries when the socket leaves `AUTH_REQUIRED`.

The base `main` was fetched and matched `origin/main` before creating this
feature branch. Changes are recorded as separate Server, Client and integration
commits. Keep test credentials, `.env`, downloaded JDK, runtime logs and generated
fixtures out of Git. After passing checks, integrate by fast-forwarding `main`
and pushing both the integration branch and `main` without force. Physical-machine
results must not be inferred from automated client results.

## Run backend and Next.js on one LAN host

Use JDK 21, Maven 3.9+, Node.js 20+ and the configured PostgreSQL database. Set
`$lanHost` to the server's current LAN IPv4 address. This machine's audited
address was `192.168.22.206`; it can change with DHCP.

Backend terminal, repository root:

```powershell
$lanHost = '192.168.22.206'
$env:PORT = '3100'
$env:HTTP_PORT = '3100'
$env:WS_PORT = '3101'
$env:REALTIME_PORT = '3101'
$env:HOST = '0.0.0.0'
$env:WS_ALLOWED_ORIGINS = "http://${lanHost}:3000,http://localhost:3000,http://127.0.0.1:3000"
npm run server:backend
```

Frontend terminal, repository root:

```powershell
$lanHost = '192.168.22.206'
$env:NEXT_PUBLIC_API_URL = "http://${lanHost}:3100"
npm --prefix frontend run dev -- --hostname 0.0.0.0
```

Open `http://192.168.22.206:3000` from either machine. Backend health is
`http://192.168.22.206:3100/health`; browser WS is `ws://192.168.22.206:3101/`.
If a browser already saved a different backend, use the Connect page to set the
LAN backend URL explicitly. `localhost` on a second machine refers to that
second machine, not the LANDrop server.

Restart Next.js after changing public environment settings; production builds
embed those values. The launcher now passes `.env`/`.env.local` resolved settings
to Java and canonicalizes the advertised HTTP/WS ports. The WS Origin list must
match the frontend page origin exactly. A TLS deployment needs HTTPS + WSS and
Upgrade forwarding as described in the shared contract.

LAN TCP ports 3000/3100/3101 must be reachable. This workflow does not silently
alter Windows Firewall or bypass browser security warnings.

## Reproducible two-client acceptance on this machine

This test uses the actual frontend `RealtimeClient`, real WebSocket transport,
real REST routes, the full production `ServerBootstrap` and PostgreSQL. Only
browser globals/storage are supplied by the Node harness. It does not render
React UI and does not represent two physical machines.

Stop any backend on 3100/3101 before running. The configured database must be
explicitly authorized for testing. It is used through the Neon direct endpoint,
with a generated schema `landrop_rt01_lan_<32 hex>`; existing migrations and all
fixture accounts/workspaces/messages stay in that schema. A test-only singleton
pool injection uses `DatabaseManager.forSchema` to preserve isolation through
the unmodified bootstrap/services. Teardown drops only the validated schema and
asserts its absence. No production database configuration is rewritten.

```powershell
mvn -pl backend compile dependency:build-classpath "-Dmdep.outputFile=target/realtime01-classpath.txt"
$env:LANDROP_REALTIME_TEST_DATABASE = 'true'
$env:LANDROP_LAN_HOST = '192.168.22.206'
node scripts/realtime-lan-smoke.mjs
```

`JAVA_HOME` must point to JDK 21. The opt-in above applies only to this test
process; remove it afterward. The harness creates disposable users whose display
names are Anh/Ken and uses real REST login. Passwords are generated, stored only
in ignored `tmp/realtime01-lan-fixtures.json` and removed on successful teardown.
Tokens are not logged. Evidence is saved to ignored
`tmp/realtime01-lan-smoke-result.json` and runtime logs.

Initial cross-stack run passed 12 scenarios: auth, subscription idempotency,
two-user canonical create/dedup/SQL identity, edit/delete, channel isolation,
workspace denial, automatic reconnect/rejoin, full bootstrap stop/recreate/history,
listener cleanup, heartbeat, removed membership, terminal logout. Initial
delivery measured 616 ms including the real database write; this is one measured
delivery, not a performance guarantee. Final validation is recorded below.

## Physical-machine checklist for later

The user explicitly deferred this step until two real machines are available.
Current status: **NOT RUN**.

1. Machine A opens the LAN frontend as actual User Anh; Machine B opens the same
   URL as actual User Ken. Use separate browser profiles/windows with independent
   login sessions, and live mode. Both users must be workspace members with
   OWNER/ADMIN/MEMBER access to the target channel.
2. Anh sends a unique message; Ken receives it immediately without reload.
   Ken replies and Anh receives it. Verify one rendered row on each client for
   each server message ID. Edit/delete as the sender and verify both views.
3. Put Ken in a different channel; send from Anh and verify no wrong-channel
   delivery. Attempt a channel in a workspace Ken cannot access: REST and room
   subscription must deny it. Private-flag channels currently use the existing
   workspace-role policy, not a separate private ACL.
4. Disconnect Machine B's network, send/edit/delete from Machine A, reconnect B
   and confirm auth/rejoin/history reconciliation. Keep the browser open through
   access expiry and verify refresh recovery. Logout/revoke Ken and verify no
   reconnect with a revoked credential.
5. Restart backend with the same database. Verify clients reconnect/rejoin and
   history retains the same IDs/content. Check `GET /api/channels/:id/messages`
   and the database row ID; do not infer persistence from UI alone.
6. Record date, browser versions, both machine IPs, Git commit, message IDs,
   expected/actual behavior and PASS/FAIL for each case. Do not record tokens or
   passwords. Add these results before claiming two-machine acceptance.

## Final integration evidence

The final two-client run passed **14/14 scenarios** on the LAN IP. It additionally
exercised the actual Chat reconciliation helper recovering offline edit/delete
and rejecting stale snapshots, and real REST refresh followed by fresh WS session
binding with room intent retained. Initial delivery in that run was 557 ms,
including database persistence. Fixture schema deletion was verified.

Frontend tests passed **29 API + 33 realtime tests**, no failures/skips. These
cover wire auth, credential refresh single-flight/transient retention, terminal
close classification, reconciliation, timer replacement and listener cleanup.
TypeScript checking passed. Next.js production build passed again after the final
terminal-state retry gate. Launcher tests passed **18/18**.
Tests use Node 24.19.0; Java runtime/harness uses JDK 21.0.12.1.

The frontend recovery controller now observes non-secret authVersion on REST and
WS refresh, preserves rooms on credential rotation, gates stale completions by
identity/server/version/lifecycle epoch, retries transient refresh failures with
bounded delay and cleans up on identity change/unmount. History requests happen
after channel.joined and merge canonical versions for create/update/delete.
The browser DOM and React interactions have not been driven by automation here;
the actual client class/state helper and REST/WS/DB pipeline are tested.
Initial/latest catch-up fetches request 50 messages; REST pagination/reload remains
necessary for larger gaps. Reopening actual user sessions and testing two
physical machines remain manual tasks in the checklist above.

Runtime readiness: Next.js root and `/auth/login` on `192.168.22.206:3000` and
backend `/health` on `192.168.22.206:3100` all returned HTTP 200. Chat CORS
preflight returned 204 and permitted the Authorization header. Backend listens
on HTTP3100/WS3101; production Next.js listens on3000. Both were left running
for the user. Build output contains the configured LAN API origin. Readiness
checks are in ignored `tmp/realtime01-readiness-result.json`.
Backend evidence is in [REALTIME_01A_BACKEND_AUDIT.md](../reports/REALTIME_01A_BACKEND_AUDIT.md).
