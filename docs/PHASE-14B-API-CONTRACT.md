# Phase 14B frontend API contract

**Status: proposed frontend contract v1, pending reconciliation with the Phase 14 backend persistence layer and HTTP API.** The checkout contains the backend PostgreSQL foundation (schema, migration, seed, models, and repositories), but no HTTP endpoint implementation was available when this frontend contract was prepared. These definitions are the frontend integration proposal; backend agreement and live integration remain separate verification steps.

The existing frontend is Java 21/JavaFX. Domain records, DTOs, mappers, and the client follow that architecture; this phase adds no TypeScript application or runtime. The contract covers persistent users, workspaces, memberships, channels, messages, folders, and file metadata only. It defines read requests without implementing backend endpoints, database access, authentication, workspace management screens, or later-phase features.

## Wire conventions and mapping

- Base URL includes `/api/`, for example `http://localhost:3000/api/`. HTTP responses use JSON and `Content-Type: application/json`.
- IDs are opaque, nonblank strings. Clients encode them as individual path segments and do not infer UUID, sequence, peer identity, or packet identity semantics. The literal IDs `.` and `..` cannot be used as route parameters because they are URI dot segments.
- Every field listed below is required in JSON. Nullable fields must be present with `null` when absent; omission is an invalid response. Unknown fields are tolerated for additive backend changes.
- The backend should serialize `createdAt` and `updatedAt` as RFC 3339 timestamps with a UTC suffix or explicit numeric offset, for example `2026-09-13T08:00:00Z` or `2026-09-13T15:00:00+07:00`. The decoder also accepts ISO 8601 offset timestamps supported by `OffsetDateTime.parse`, including omitted seconds. Offset-free local timestamps are rejected. DTO timestamp strings map to Java `Instant` values. `updatedAt` must not precede `createdAt`.
- Enum values are case-sensitive. Unknown enum values are invalid responses.
- `sizeBytes` is a canonical decimal JSON **string**, from `"0"` through `"9223372036854775807"`, with no leading zeroes, sign, decimal point, or exponent. It maps to `BigInteger` without floating-point conversion. This preserves PostgreSQL signed 64-bit nonnegative sizes across JSON clients.
- A file record describes metadata. It contains no local filesystem path, transfer state, upload/download URL, or storage implementation.

DTOs retain the JSON representation. Mappers validate the response and produce immutable domain records before presentation code receives data. UI components need not parse JSON or convert timestamps and file sizes.

## Persistent entities

All entities include `createdAt` and `updatedAt` as described above. `string?` below means a required field whose value is a string or `null`.

| Entity | Required fields in addition to timestamps |
| --- | --- |
| `User` | `id: string`, `displayName: string`, `email: string?`, `avatarUrl: string?` |
| `Workspace` | `id: string`, `name: string`, `ownerId: string`, `description: string?` |
| `WorkspaceMember` | `id: string`, `workspaceId: string`, `userId: string`, `role: "OWNER" \| "ADMIN" \| "MEMBER"` |
| `Channel` | `id: string`, `workspaceId: string`, `name: string`, `description: string?` |
| `Message` | `id: string`, `channelId: string`, `senderId: string`, `content: string`, `type: "TEXT" \| "FILE"`, `replyToId: string?`, `fileId: string?` |
| `Folder` | `id: string`, `workspaceId: string`, `parentId: string?`, `name: string`, `createdById: string` |
| `File` | `id: string`, `workspaceId: string`, `folderId: string?`, `uploadedById: string`, `name: string`, `mimeType: string`, `sizeBytes: string`, `sha256: string?` |

Names, `displayName`, `mimeType`, IDs, and non-null `email`/`avatarUrl` values must be nonblank. Descriptions may be `null` or any string, including empty text. Nullable IDs are either `null` or nonblank strings. File names and MIME types are metadata strings; they are not treated as filesystem paths or used to execute content.

Relationship rules for backend reconciliation and fixtures:

- Every foreign key resolves to its entity. A workspace owner has an `OWNER` membership in that workspace; membership is unique per `(workspaceId, userId)`.
- Channels, members, folders, and files belong to the workspace identified in their record and collection route. Message senders, file uploaders, and folder creators belong to the corresponding workspace.
- A message reply references a message in the same channel. Reply chains are acyclic. `TEXT` messages have nonblank content and `fileId: null`. `FILE` messages reference a file in the channel's workspace and may have an empty caption in `content`.
- A folder's non-null `parentId` references a folder in the same workspace; the hierarchy is acyclic. A file's non-null `folderId` references a folder in the same workspace. `parentId: null` and `folderId: null` mean the workspace root.
- A non-null `sha256` is a 64-character hexadecimal SHA-256 digest. Null means no digest is available.

Single-response validation cannot establish foreign keys across an entire database. The backend must enforce these relationships; development fixtures must be internally consistent.

## Routes and response envelopes

All routes below use `GET`. Successful item and collection requests return HTTP `200`.

| Route | Response data |
| --- | --- |
| `/api/users/:id` | `User` |
| `/api/workspaces` | `Workspace[]` |
| `/api/workspaces/:workspaceId` | `Workspace` |
| `/api/workspaces/:workspaceId/members` | `WorkspaceMember[]` |
| `/api/workspaces/:workspaceId/channels` | `Channel[]` |
| `/api/channels/:channelId/messages` | `Message[]` |
| `/api/workspaces/:workspaceId/files` | `File[]` |
| `/api/workspaces/:workspaceId/folders` | `Folder[]` |

An item response wraps the record in `data`:

```json
{
  "data": {
    "id": "user-ada",
    "displayName": "Ada",
    "email": null,
    "avatarUrl": null,
    "createdAt": "2026-09-13T08:00:00Z",
    "updatedAt": "2026-09-13T08:00:00Z"
  }
}
```

Every list uses the same envelope, including an empty collection:

```json
{
  "data": [],
  "page": {
    "nextCursor": null
  }
}
```

For a message list, each `data` entry has this shape:

```json
{
  "id": "message-hello",
  "channelId": "channel-general",
  "senderId": "user-ada",
  "content": "Hello from the Phase 14 development fixture.",
  "type": "TEXT",
  "replyToId": null,
  "fileId": null,
  "createdAt": "2026-09-13T08:30:00Z",
  "updatedAt": "2026-09-13T08:30:00Z"
}
```

Examples illustrate the wire format; IDs are not reserved identifiers.

## Pagination

Every list accepts `limit` and `cursor` query parameters. `limit` is an integer from `1` to `100`, defaulting to `50`. Omit `cursor` for the first page. A supplied cursor must be nonblank and is opaque to clients; URL-encode it as a query value.

Lists are ordered by `createdAt ASC`, then `id ASC` to resolve ties. `page.nextCursor` is either a nonblank token to request the next page or `null` when the collection is exhausted. Cursors are bound to their collection, including its parent workspace or channel. A malformed cursor or one reused for another collection produces `400 INVALID_QUERY`.

For example:

```text
GET /api/channels/channel-general/messages?limit=50
GET /api/channels/channel-general/messages?limit=50&cursor=<encoded-nextCursor>
```

The mock's cursor encoding is an implementation detail and is not a requirement for the backend. This phase defines no filters, search, sorting controls, or snapshot-isolation guarantee across requests. A missing parent resource returns `404`; an existing parent with no children returns `200` with an empty list and `nextCursor: null`.

## Errors

Backend error envelope:

```json
{
  "error": {
    "code": "NOT_FOUND",
    "message": "Workspace was not found."
  }
}
```

| HTTP status | Backend code | Meaning |
| --- | --- | --- |
| `400` | `INVALID_QUERY` | Invalid limit, cursor, or unsupported query |
| `404` | `NOT_FOUND` | Requested item or collection parent does not exist |
| `500` | `INTERNAL_ERROR` | Backend could not complete the request |

The client also exposes `NETWORK_ERROR`, `TIMEOUT`, `INVALID_RESPONSE`, and `HTTP_ERROR`. Transport failures and invalid successful-response shapes use status `0`; HTTP failures retain their response status. `HTTP_ERROR` covers an HTTP failure without a usable API error envelope. Error messages provide presentation text, while callers can use stable codes and status for decisions. The HTTP client never substitutes fixtures after a failed request.

## Java frontend client

The public `com.landrop.ui.cloud.api.CloudApi` client returns `CompletableFuture` values containing mapped domain records from `com.landrop.ui.cloud.model.Domain`. Item requests return the entity; list requests return `Domain.Page<T>` with `items()` and `nextCursor()` accessors. Wire records live in `com.landrop.ui.cloud.dto.Dto`; `ItemResponse<T>` and `ListResponse<T>` provide the shared envelopes without duplicating an envelope for each entity.

| Client operation | Route |
| --- | --- |
| `api.users().get(id)` | User item |
| `api.workspaces().list(pageRequest)` | Workspace list |
| `api.workspaces().get(id)` | Workspace item |
| `api.workspaces().members(id, pageRequest)` | Workspace members |
| `api.channels().list(workspaceId, pageRequest)` | Workspace channels |
| `api.messages().list(channelId, pageRequest)` | Channel messages |
| `api.files().list(workspaceId, pageRequest)` | Workspace files |
| `api.folders().list(workspaceId, pageRequest)` | Workspace folders |

Use `PageRequest.first()` for the default page, or `new PageRequest(limit, cursor)` for an explicit page. Invalid caller arguments, such as a blank ID or out-of-range page limit, throw `IllegalArgumentException` locally. Transport and response failures complete the returned future exceptionally with `ApiException`, whose `code()` and `statusCode()` expose the error classification. `CloudApi.mock()` uses development fixtures without network access. `CloudApi.http(URI.create("http://localhost:3000/api/"))` uses HTTP with a default request timeout of 10 seconds. Both implementations pass DTOs through the same mapping boundary.

Completion callbacks may run outside the JavaFX application thread. Apply presentation updates with `Platform.runLater`; never block the JavaFX thread with `join()` or `get()`.

## Core compatibility and handoff

Core `ChatMessage` and `UIMessage` retain their packet request IDs, peer display names, and local timestamps. Persistent `Message` and `User` represent database records. No mapping between peer identities and database users is inferred. Persistent `File` represents metadata; Core `FileTransfer` continues to represent a running LAN transfer.

The new client is opt-in. Existing discovery, chat, transfers, state services, and application startup remain independent of the database API. No cloud failure should prevent using LAN Core. No workspace screen is introduced; the read-only development example exercises the new data path without altering existing UI flows.

Before backend integration, reconcile field names, required/null rules, enum values, signed 64-bit size serialization, foreign-key constraints, timestamp precision, routes, envelopes, and cursor behavior against the implemented backend persistence models and repository layer. Then implement or connect HTTP endpoints and run the same mapping and client checks with real API responses. The frontend cannot certify endpoint compatibility or live database behavior until that API is available.
