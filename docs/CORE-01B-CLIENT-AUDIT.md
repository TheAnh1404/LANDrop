# CORE-01B client audit

## Identity boundary

`AuthStateService.user()` is the sole product-user identity. `Domain.Device` is a registered device attached to a user. `DiscoveredDevice` is an unauthenticated LAN endpoint; its `peerId` is diagnostic transport data and must never drive message ownership or authorization.

Message ownership remains `message.senderId == auth.user.id`. Chat state has no LAN-peer dependency.

## Technical debt discovered

| Area | Previous issue | CORE-01B action |
| --- | --- | --- |
| LAN UI | Controllers consumed `PeerInfo` and displayed raw peer IDs | `LanDiscoveryService` now maps engine peers to endpoint-only `DiscoveredDevice`; product UI does not render peer IDs. |
| Transfer UI | File manager called the mixed `UIStateService` directly | `LanTransferService` is now its transfer boundary. |
| Realtime | Workspace chat allocated/managed a transport itself | `RealtimeGateway` is the sole channel-socket owner and owns reconnect/subscription cleanup. |
| Config | WebSocket dev default pointed at the HTTP port | Default WS endpoint is now port 3001; values remain overridable. |
| Legacy state | `UIStateService` still contains discovery, direct-P2P legacy chat, and transfer engine state | Kept behind LAN adapters for backward-compatible Core regression. Future Core extraction can replace only `UIStateLanServices`. |
| HTTP clients | Auth/workspace legacy adapters pre-date a single mutable request abstraction | Existing feature clients remain service-layer-only, but an API foundation consolidation is a follow-up contract migration with CORE-01A; no UI makes raw HTTP calls. |

## Required operational separation

* API/auth/realtime failures are server connectivity failures.
* Discovery/device reachability and binary transfer failures are LAN failures.
* File Manager initiates server-coordinated transfer requests; it does not open sockets or identify a user from a peer.

## Non-goals

No new product feature, task, notification, search, cloud drive, or file-preview functionality is introduced.
