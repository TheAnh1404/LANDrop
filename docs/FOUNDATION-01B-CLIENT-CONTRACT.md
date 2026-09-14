# FOUNDATION-01B Client Control Contract

The client uses one authenticated WebSocket control connection configured by `WS_URL` (default: `ws://host:3001/api/realtime`). Large file bytes never travel through this connection.

Every control event is JSON:

```json
{
  "type": "event.name",
  "requestId": "uuid",
  "timestamp": "ISO-8601 instant",
  "payload": {}
}
```

On socket open the client sends `hello` with `protocolVersion: 1`, `clientVersion`, `clientInstanceId`, `deviceName`, and `platform` in `payload` and mirrors those handshake fields at the top level for FOUNDATION-01A compatibility. The server replies with `welcome` (a server-push `welcome` may omit `requestId`) and with `payload.protocolVersion: 1`. It may provide `payload.heartbeatIntervalMs`; the client defaults to 30000 ms.

The client sends `heartbeat` only after `welcome`. A request response must preserve `requestId`; server errors use `type: error` and `payload.message`.

The development settings screen sends `echo` with `{ "message": "hello server" }` and expects `echo_ack` with the same `requestId`.

Transfer control event names are `transfer.request`, `transfer.accept`, `transfer.reject`, `transfer.cancel`, and `transfer.status`. The server coordinates authorization/endpoints; `LanTransferService` performs direct Client-to-Client binary streaming.
