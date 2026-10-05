# Authenticated multi-device realtime contract

Backend implementation lives in `backend/.../realtime`. No Workspace CRUD or membership management API is introduced. Existing LAN TCP chat remains a separate transport demonstration, not authenticated product chat.

## Connect

Start the shared backend using `bash start-auth.sh` or `start-auth.bat`.
Auth HTTP defaults to port 3000; WebSocket defaults to port 3001.
Both bind to `BIND_HOST=0.0.0.0` by default. Override `AUTH_PORT`, `REALTIME_PORT`, `BIND_HOST` via OS environment or the repository root `.env`.

Native Java clients connect to:

```text
ws://<backend-lan-ip>:3001/api/realtime/channels/<channel UUID>
Authorization: Bearer <accessToken>
X-Device-Label: Laptop
```

The channel must already exist and be public (`is_private=false`); the user must have an existing `workspace_members` row for that channel's workspace. This is an authorization read, not a membership feature. Private channels are denied because the current schema does not provide a private-channel ACL.

Tokens in URL query strings are rejected. Browser Origin requests are rejected in this native-client phase. Invalid/expired/refresh JWTs, unknown users, and inaccessible channels are rejected before registration or upgrade. Java-WebSocket returns HTTP 404 on rejected upgrades, so callers must treat a failed upgrade as failed authentication/access, not expect a JSON 401. Successful connections receive:

```json
{"type":"connected","socketId":"uuid","userId":"uuid","channelId":"uuid","accessTokenId":"jwt-jti","deviceLabel":"Laptop"}
```

`socketId` is server-generated per connection. `accessTokenId` is the verified access JWT jti, **not** a stable refresh-session ID. `deviceLabel` is untrusted diagnostic text (max 80 chars), never an identity or authorization input.

## Send

```json
{"type":"message.send","requestId":"uuid","clientMessageId":"uuid","content":"hello"}
```

Do not send senderId, userId, peerName, channelId, or additional fields in this command. The server derives user and channel from the authenticated socket. IDs must be canonical lowercase UUIDs. Content must contain non-whitespace text, at most 4000 Java characters and no NUL. JSON text frames/messages are limited to 64 KiB at the protocol layer and 32768 characters at command decoding. Binary frames are rejected.

After database commit the server sends **one** canonical event to every authorized socket in that channel, including all devices of the sender:

```json
{
  "type":"message.created",
  "requestId":"uuid",
  "message":{
    "id":"server/database UUID",
    "channelId":"uuid",
    "senderId":"verified User.id",
    "clientMessageId":"uuid",
    "content":"hello",
    "createdAt":"2026-09-14T00:00:00Z",
    "updatedAt":"2026-09-14T00:00:00Z"
  }
}
```

All recipients see the same persisted message. Clients reconcile optimistic rows using `clientMessageId`, then deduplicate/upsert by canonical `message.id`. There is no separate send ACK to append as another message.

Migration V3 adds nullable `messages.client_message_id` and a unique `(user_id, client_message_id)` index. Retry the **same** ID and content after a lost ACK. A duplicate request returns the original canonical message only to that requesting socket; it does not insert or fan out a second event. Reusing the ID for different content/channel produces `REQUEST_CONFLICT`.

## Persistent history

```json
{"type":"history","requestId":"uuid","limit":50,"before":null}
```

```json
{"type":"history","requestId":"uuid","page":{"messages":[],"nextCursor":null}}
```

Results are newest first, ordered by `created_at DESC, id DESC`. Limit is 1–100 (default 50). Pass the returned opaque `nextCursor` as `before` for older pages. Older database messages have `clientMessageId:null`. History is read from PostgreSQL every time. Reconnect, request latest history, and upsert by ID to recover after network loss or a process restart. Page backwards until the last locally known ID if the gap exceeds one page.

## Error and lifecycle behavior

```json
{"type":"error","requestId":"uuid-or-null","code":"INVALID_REQUEST","message":"Invalid chat request."}
```

Codes: `INVALID_REQUEST`, `FORBIDDEN`, `REQUEST_CONFLICT`, `PERSISTENCE_UNAVAILABLE`. No raw SQL, credentials, or stack traces appear in protocol errors. Failed persistence never broadcasts.

Registry: `User.id -> Map<socketId, Connection>`. Disconnect removes only that socket. User connectivity lasts until its final socket closes. Channel access is checked on connect, writes/history, and recipient fanout.

A socket cannot read/write/receive after its access expiry; an idle expiry sweep closes it within about one second using 4001. Refresh via Phase 15A HTTP, replace both tokens, reconnect with the new access token, reload history. Multiple independent login sessions coexist; refresh/logout of one refresh token does not revoke sibling devices. The existing JWT model has no stable session claim: logout revokes refresh but existing access JWTs/sockets remain valid until access expiry. Immediate session-specific socket revocation requires a linked session claim; this limitation is explicit.

## Persistence and limits

Uses existing `messages.user_id` FK as canonical senderId; LAN Peer UUIDs never enter that FK. Commit precedes broadcast. The database assigns message IDs/timestamps. A backend crash between commit and delivery is recovered via history; this is not a durable event queue.

One shared backend process hosts HTTP auth and WebSocket listeners. The connection registry and broadcast ordering are process-local; horizontally distributed fanout is outside this phase. The implementation uses [Java-WebSocket 1.6.0](https://github.com/TooTallNate/Java-WebSocket/tree/v1.6.0) for RFC6455 framing, ping/pong and handshake handling.

For LAN testing use the backend's actual IP in client URLs. Cleartext HTTP/WS is for a trusted local test network; TLS termination/production deployment is outside scope.
