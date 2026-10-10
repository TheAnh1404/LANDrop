# Browser P2P chat and files

The bundled `/client` page and Next.js transfer screen use an ordered WebRTC
DataChannel for chat and file contents. Authenticate on the existing gateway and
coordinate the connection through [REALTIME-02](REALTIME_02_SIGNALING_CONTRACT.md).
Both accounts must belong to the selected workspace. The bundled client offers
registration/login and loads the account's workspaces; it does not use a placeholder
workspace or invent a signaling session ID.

## DataChannel messages

Control messages are JSON strings. File chunks are binary ArrayBuffers.

| Type | Fields | Behavior |
| --- | --- | --- |
| `CHAT_MESSAGE` | `id`, `text`, `senderName`, `timestamp`, optional `replyToId` | Receives a chat message. Duplicate incoming IDs are acknowledged without another bubble. |
| `CHAT_ACK` | `messageId`, `status: "delivered"` | Updates an existing outgoing message. Means received by the peer client, not read by a person. |
| `CHAT_TYPING` | `isTyping`, optional `senderName` | Typing indicator; cleared on a message, disconnect, or after three seconds without an update. |
| `TEXT_MESSAGE` | `id`, `text`, `senderName`, `timestamp` | Existing quick-text/clipboard message, supported by Next.js. |
| `FILE_START` | `transferId`, `fileId`, `fileName`, `fileSize`, `mimeType`, `chunkSize`, `totalChunks` | Starts a file. Browser senders use 16 KiB chunks and zero chunks for empty files. |
| `FILE_END` | `transferId`, `fileId` | Completes only the matching incoming file after exactly `fileSize` bytes arrive. |
| `FILE_CANCEL` | `transferId`, optional `reason` | Discards the incoming file and updates transfer state. |
| `PING` | `timestamp` | Requests a technical echo. |
| `PONG` | `clientTimestamp`, `timestamp` | Echoes the sender's timestamp for a round-trip measurement. |

Only one outgoing file may run on a channel at a time, since binary chunks do not
carry a file ID. Sending waits while the browser's buffered amount exceeds 64 KiB;
a stalled buffer times out after 30 seconds. Disconnects and exceptions produce a
failed/cancelled transfer, without `FILE_END` or a completed file bubble. Receivers
discard truncated or oversized content. A completed outgoing transfer means queued
onto the ordered channel; it is not a receiver acknowledgement or a checksum result.

## Client state and limitations

Switching the selected peer closes/cancels the previous connection before another
peer can receive content. Early ICE candidates are buffered until a remote SDP has
been applied, and signaling is scoped to the server-issued transfer ID.
Selecting the same peer or queuing a message while negotiation is pending reuses
that session. Accepting an incoming conversation preserves the answerer role and
does not create a competing outgoing request.

Next.js chat state lives in memory. The bundled client keeps conversation metadata
in localStorage per account; access and refresh tokens stay in memory. Blob download
URLs are session-local and are not restored as usable files after a reload. Received
files remain in browser memory and may trigger an automatic download. Browser and
device memory limit file size; no disk streaming, resume, application-level checksum
or delivery acknowledgement for files is implemented here. WebRTC encrypts the
transport; the application adds no independent encryption scheme for browser chat.

## Verification

`npm test` includes bundled-client protocol and interruption tests.
`npm --prefix frontend test` includes WebRTC chat, acknowledgements, deduplication,
typing cleanup, file completion validation and simultaneous-send/channel-loss tests.
These automated suites use simulated transports. Production authentication and
workspace authorization require a dedicated PostgreSQL integration environment.

A local Chrome smoke check also exercised two real WebRTC endpoints with a mock
HTTP/signaling backend: bidirectional chat, delivery ticks, exact received file
bytes, empty files, peer switching and desktop/mobile layouts. This does not certify
the real database or connectivity between separate LAN devices.

A subsequent local Chrome check on 2026-10-10 used the integrated Java gateway
and a disposable schema in a dedicated PostgreSQL test database. It exercised
registration, persisted device enrollment and workspace membership, both peers
reaching `P2P_CONNECTED`, direct chat, exact downloaded file bytes and subsequent
login. The Next.js production build also passed registration/login, refresh on
reload and transition from demo to a live session. No JavaScript errors or
signaling errors occurred during the active chat/file check. Logout can still
produce a signaling rejection while the departing peer closes its socket;
cross-device LAN connectivity remains unverified by this local check.
