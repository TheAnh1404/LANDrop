# REALTIME-01 shared browser chat contract

Status: **AGREED — REALTIME-01A backend acceptance verified**.
Date: 2026-10-08–09. Owners: Server REALTIME-01A and Client REALTIME-01B.

This is the single agreed integration contract for REALTIME-01. It extends the
existing `FoundationRealtimeServer` and `EventEnvelope`, not the native demo
`com.landrop.realtime.RealtimeServer` described in `REALTIME_CHAT.md`.
The decisions below were accepted by Client before backend implementation.

## Coordination record

Server has audited the current source and retains the existing names
`auth`, `auth_ack`, `channel.join`, `channel.joined`, `channel.leave`,
`channel.left`, `heartbeat`, `heartbeat_ack` and `message.*`.
Do not introduce parallel `auth.bind`/`channel.subscribe` aliases.

Client agent running in AGY on this machine: read this file, then record your
acknowledgement or specific requested changes here under **Client response**.
Please do not modify Java backend files. Server will read this record before
implementing wire behavior. Shared-contract changes must be recorded here.

### Client response

ACCEPT received from the existing AGY REALTIME-01B conversation via CLI on
2026-10-08, before Java implementation. Client confirmed the retained event
names, event auth with device-less browser sessions, `payload.message`, REST-only
mutations, per-tab instance identity, catch-up/deduplication and exact Origin list.
Client requests that localhost:3000 be allowed in development. Client will use
single-flight REST refresh after AUTH_REQUIRED/UNAUTHORIZED, reconnect and rejoin;
revoked sessions/disabled accounts must remain terminal. The first 45-second CLI
request timed out without a reply; the focused follow-up returned ACCEPT.
Client also acknowledged the expiry/close compatibility note: code 4002 means
authentication termination; preserve the preceding error. UNAUTHORIZED permits
single-flight refresh/reconnect; SESSION_EXPIRED/SESSION_REVOKED are terminal.
Do not overwrite AUTH_REQUIRED with SESSION_REVOKED solely because of 4002.
Use `/api/channels/:id/messages` (there is no `/api/v1` alias in this gateway).

## URL and configuration

Use the integrated launcher (`npm run server:backend`), not `start-auth.*`.
Connect to `ws://<server-host>:<ws-port>/` (or `wss://` behind TLS termination).
Defaults: HTTP 3000, WS 3001; local Next.js integration: HTTP 3100, WS 3101.
`landrop.server.ws.port` / `REALTIME_PORT` and the existing `WS_PORT` fallback
configure the WS port. The gateway currently accepts any resource path; root
is the canonical browser path; `/ws` also works for reverse proxies. Query
strings are rejected at upgrade, rather than carrying credentials.

Browser Origin allowlist: `WS_ALLOWED_ORIGINS` (comma-separated exact
HTTP(S) origins). Reject an Origin not in the list. Keep Origin-less native
clients compatible. Configure the actual Next.js origin (for example
`http://localhost:3000`), not the backend host or a wildcard. Proxy must preserve
Origin and WebSocket Upgrade/Connection headers and forward to the WS port;
its idle timeout must exceed the advertised heartbeat timeout. There is no
reverse-proxy configuration currently checked into this repository.
The development default is `http://localhost:3000,http://127.0.0.1:3000`.
System property override: `landrop.ws.allowed.origins`.

## Browser connection and authentication

1. Open the WebSocket without Authorization headers or query credentials.
2. Send the existing flat `hello` command:

```json
{"type":"hello","protocolVersion":1,"clientInstanceId":"random-id-per-tab","deviceName":"Browser","platform":"web"}
```

3. Receive the existing `welcome`. This allocates a connection ID; it does not
   establish a User identity. Do not join rooms yet.
4. Send the existing `auth` command with the current REST access token:

```json
{"type":"auth","requestId":"auth-1","payload":{"accessToken":"<current-access-token>"}}
```

5. Receive `auth_ack` with `payload.authenticated=true`, server-derived `userId`,
   `username`, `sessionId` and optional `deviceId`. Then join rooms.

Server verifies JWT signature/type/expiry, the linked active persistent session
and the current User (including disabled status). Login/register currently issue
sessions without a device; browser chat must support those sessions without
inventing a Device or using clientInstanceId as authority. If a session has an
enrolled device, its ownership must be verified. Native Authorization-header
hello with enrolled device remains compatible.

`userId`, `role`, `workspaceId` supplied by clients confer no permissions.
Duplicate binding must not change an authenticated connection to another
identity. Reconnect creates a new connection and repeats auth and joins. Give
each tab its own clientInstanceId to avoid the existing instance replacement
policy. Logout/revoke must close every socket bound to that session, while
independent sessions belonging to the same User remain connected.

## Envelope and room commands

Use the existing `EventEnvelope`: `type`, optional `requestId`, ISO timestamp,
`payload`. Error events retain both `error.code`/`error.message` and the existing
top-level `code`/`message`. Existing welcome compatibility fields remain.

```json
{"type":"channel.join","requestId":"join-1","payload":{"channelId":"<uuid>"}}
```

```json
{"type":"channel.joined","requestId":"join-1","timestamp":"2026-10-08T10:00:00Z","payload":{"channelId":"<uuid>"}}
```

Leave uses `channel.leave` and replies `channel.left` with the same shape.
Joining twice is idempotent: one subscription, no duplicate fanout.
No chat is delivered before auth or before joining that specific channel.

Permission policy reuses `ChannelChatService.canAccess`: existing channel,
workspace membership with OWNER/ADMIN/MEMBER; GUEST and non-members are denied.
The current REST service does not enforce a separate private-channel ACL (the
schema has no channel-members table). This contract preserves that current REST
policy consistently; a stricter private-channel policy requires an explicit
shared decision rather than silently changing REST behavior.
Membership and session/account validity must be rechecked before delivery;
removed members lose subscriptions. Client-claimed workspace IDs are ignored.

## Writes and canonical chat events

Keep a single write pipeline: existing authenticated REST API ->
`ChannelChatService` -> DB commit -> room broadcast. No browser WS message-send
command is added. Existing native demo sending is a separate launcher, not a
second browser write route in the integrated gateway.

REST create: `POST /api/channels/:channelId/messages` with `content`, optional
`clientMessageId` (UUID, strongly recommended for retries) and `replyToId`.
Edit: `PATCH /api/messages/:messageId`. Delete: `DELETE /api/messages/:messageId`.
History/pagination remain `GET /api/channels/:channelId/messages`. The actual REST
default is ascending; use `?order=desc&limit=50` for latest history and retain
`order=desc` when passing its `cursor` for older messages (`before` also forces
descending). The native demo's different history defaults do not apply here.

Preserve the current gateway's payload shape and the existing ChatMessageDto:

```json
{
  "type":"message.created",
  "timestamp":"2026-10-08T10:00:00Z",
  "payload":{
    "type":"message.created",
    "channelId":"<uuid>",
    "message":{
      "id":"<server-uuid>",
      "channelId":"<uuid>",
      "senderId":"<authenticated-user-uuid>",
      "sender":{"id":"<user-uuid>","username":"alice","displayName":"Alice","avatarUrl":null},
      "content":"Hello LANDrop",
      "replyToId":null,
      "clientMessageId":"<retry-uuid>",
      "createdAt":"2026-10-08T10:00:00Z",
      "updatedAt":"2026-10-08T10:00:00Z",
      "editedAt":null,
      "deletedAt":null
    }
  }
}
```

Read the DTO from `payload.message`, not the envelope itself. No `workspaceId`
is added to the current DTO; Client already knows it from channel routing.
`message.updated` payload additionally has `messageId` and the canonical DTO.
`message.deleted` additionally has `messageId`, `deletedAt` and the canonical
soft-deleted DTO. Sender receives the same event if subscribed. Do not append
both the REST response and WS event: upsert by `message.id`; reconcile optimistic
rows by `clientMessageId`. Retry the same UUID and same original request; a
different channel/content/reply target using that ID must conflict. Successful
retries return the original REST DTO without another broadcast. Persistence
failure must never broadcast. Fanout failure after commit must not report the
write as rolled back or encourage a new message ID.

## Lifecycle, errors and recovery

Send `heartbeat` at the welcome interval (default 15000 ms), receive
`heartbeat_ack`. Existing inactivity timeout defaults to 45000 ms, close 4000.
Bounded pre-hello/pre-auth deadline and idle session validity sweep
prevent sockets surviving forever without completing auth or sending messages.
Authentication deadline defaults to 10000 ms from socket open; override with
`WS_AUTH_TIMEOUT_MS` / `landrop.ws.auth.timeout.ms`. Session/expiry sweep is
scheduled every 100–1000 ms; database latency can affect completion time.
Access expiry requires refresh using the existing HTTP flow, then a fresh socket
and rejoin. Session revoked/expired and account disabled are terminal auth errors;
do not reconnect forever with the same credential. Default current close codes:
1008 handshake rejection, 4002 session termination, 4003 disabled account,
4001 admin disconnect, 1001 server shutdown. `error` precedes close when possible.
Missing/invalid auth uses AUTH_REQUIRED/UNAUTHORIZED or the existing JWT error
code; room denial uses FORBIDDEN; malformed UUID uses INVALID_PAYLOAD;
unsupported event uses UNKNOWN_EVENT; server unavailability uses INTERNAL_ERROR.

After joining/rejoining, fetch REST history and merge by canonical ID while
buffering live events. Recover a larger gap using existing pagination. No durable
event queue or multi-process distributed fanout is promised. Connection/room
cleanup must cover disconnect, timeout, instance replacement, revoke and stop.
Existing transfer and signaling event names/envelopes remain compatible.

## Acceptance boundary

Client accepted this contract before implementation. Backend acceptance passed
on Java 21: 36 final gateway/registry/foundation/signaling tests and 7 real
PostgreSQL acceptance tests. See [the backend report](../reports/REALTIME_01A_BACKEND_AUDIT.md)
for commands, the broader regression result, exclusions and remaining Client UI
verification. Evidence includes browser-style auth, invalid credentials/Origin,
authorized/denied joins, two-user room delivery/isolation, REST persistence and
idempotency, update/delete events, session revoke, reconnect, heartbeat/timeout,
restart/history recovery, REST regression and signaling regression. With explicit
user authorization, PostgreSQL acceptance used the configured database through
its direct endpoint, isolated fixtures in a random schema and verified schema
deletion afterward. Native gateway tests alone do not prove persistence.
