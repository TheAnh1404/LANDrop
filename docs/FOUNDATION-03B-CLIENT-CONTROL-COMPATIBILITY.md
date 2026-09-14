# FOUNDATION-03B — Client ↔ Control Server Compatibility

## Scope

This phase covers the existing desktop client control connection only. It does not add a login flow, enrollment, orchestration, presence, or FOUNDATION-04 features.

## Client metadata contract

The first WebSocket message is `hello`. Client fields are sent both at the envelope top level (existing FOUNDATION compatibility) and under the client-side payload model:

```json
{
  "type": "hello",
  "protocolVersion": 1,
  "clientInstanceId": "stable-installation-uuid",
  "deviceName": "MacBook-Pro",
  "platform": "mac os x",
  "clientVersion": "0.1.0",
  "osVersion": "...",
  "architecture": "aarch64"
}
```

`clientInstanceId` is stored in Java Preferences and is independent of the signed-in user and LAN peer ID. `deviceName` uses a user/device label when available and otherwise the OS hostname; it is never hardcoded to `Client A`.

The server registry stores the required fields plus `osVersion` and `architecture`, as well as the socket-observed remote address and server-issued `connectionId`.

## Welcome, heartbeat, and diagnostics

`welcome.payload` provides `serverName`, `serverVersion`, `protocolVersion`, `connectionId`, and `heartbeatIntervalMs`. The client exposes these with its server address, connection state, client metadata, last heartbeat acknowledgement, reconnect count, and disconnect reason in the diagnostics-only Settings panel.

One scheduler owns the heartbeat. `lastHeartbeat` is updated on `heartbeat_ack`, so it represents server-confirmed health and does not produce duplicate heartbeat loops.

## Disconnect/error handling

The client safely handles malformed/unknown events, server close callbacks, and `error` envelopes. Standard terminal codes are:

`ADMIN_DISCONNECT`, `SERVER_SHUTDOWN`, `SESSION_REVOKED`, `ACCOUNT_DISABLED`, and `PROTOCOL_VERSION_UNSUPPORTED`.

For these explicit reasons the socket and timers are cleaned up, state becomes `DISCONNECTED` (or `ERROR` for an unsupported WELCOME version), the reason is retained for diagnostics, and automatic reconnect is suppressed. `SERVER_RESTARTING` and unreasoned network failures remain reconnectable with bounded backoff delays. The `ServerErrorException.code()` hook preserves future auth/device UX mapping without changing the existing authentication flow.

The server now exposes a `disconnectClient(...)` control-plane hook and sends an error envelope before closing. Graceful server stop sends `SERVER_SHUTDOWN`. If a future server only closes a socket without a reason/error contract, the client cannot distinguish that from a network failure and will reconnect; this is the documented protocol limitation.

## Configuration flow

Settings provides editable API and WebSocket server URLs with validation and “Save address & Connect”. The values are persisted in Java Preferences. CLI/system/environment configuration still takes precedence at startup, so a deployment can explicitly override a local saved address. A changed port therefore does not require a code change or stale hardcoded URL.

## Auth/device compatibility

No authentication code was refactored. The current architecture keeps the mapping available for later phases:

`clientInstanceId → device → session → user`

`clientInstanceId` remains installation identity, while the existing auth session remains the user/session authority.

## Regression checklist

- Stable client instance ID: covered by existing persistence test.
- HELLO metadata and non-placeholder device name: covered by realtime unit test.
- Server name/version/protocol/connection ID: exposed from WELCOME and diagnostics.
- Server registry remote address and heartbeat: preserved by the existing server registry.
- Admin/server disconnect cleanup and reconnect suppression: covered by realtime unit test and server control hook.
- Server restart reconnect: existing LAN integration behavior remains in place.
- Editable server address and URL validation: covered by config unit test.
- Single socket/heartbeat ownership and LAN engine: unchanged architecture; targeted compile/tests pass.
- Auth flow: unchanged.

