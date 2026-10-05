# Workspace & Members API contract

Base URL: the existing shared HTTP backend, e.g. http://SERVER:3000/api.
All routes require Authorization: Bearer ACCESS_TOKEN and use JSON. Identity is taken from verified JWT and checked against the users table.

Design: add an **existing account by email**, creating membership immediately as MEMBER. No email delivery, invite tokens, pending invitations, or self-join links. OWNER can subsequently promote to ADMIN. No ownership transfer.

Success responses use {"data": ...}; delete/leave return 204 without a body. Auth endpoints retain their existing Phase 15 response shapes.

## Workspace response

```json
{"data":{"id":"uuid","name":"Design","slug":"design-<generated-uuid>","description":null,"ownerId":"uuid","role":"OWNER","createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z"}}
```

The role field is the current caller's membership role. slug is generated on creation and immutable. No ownerId, slug, timestamps or role may be supplied when creating/updating a workspace.

| Method | Route | Body | Success | Permission |
| --- | --- | --- | --- | --- |
| POST | /api/workspaces | {"name":"Design","description":"Optional"} | 201, workspace data | Any authenticated existing user; creator becomes OWNER atomically |
| GET | /api/workspaces | none | 200, data: workspace[] | Only caller's memberships |
| GET | /api/workspaces/:workspaceId | none | 200, workspace data | Member |
| PATCH | /api/workspaces/:workspaceId | {"name":"Renamed","description":null} | 200, workspace data | OWNER or ADMIN |
| DELETE | /api/workspaces/:workspaceId | none | 204 | OWNER only |
| GET | /api/workspaces/:workspaceId/members | none | 200, data: member[] | Member |
| POST | /api/workspaces/:workspaceId/members | {"email":"existing@example.com"} | 201, member data | OWNER or ADMIN; new role always MEMBER |
| PATCH | /api/workspaces/:workspaceId/members/:userId | {"role":"ADMIN"} or {"role":"MEMBER"} | 200, member data | OWNER only; target cannot be OWNER |
| DELETE | /api/workspaces/:workspaceId/members/:userId | none | 204 | OWNER removes ADMIN/MEMBER; ADMIN removes MEMBER; no self-removal here |
| POST | /api/workspaces/:workspaceId/leave | none | 204 | ADMIN or MEMBER; OWNER cannot leave |

Member response:

```json
{"data":{"id":"membership-uuid","workspaceId":"uuid","userId":"uuid","role":"MEMBER","joinedAt":"2026-09-14T00:00:00Z","user":{"id":"uuid","username":"alex","displayName":"Alex","avatarUrl":null}}}
```

User email, passwordHash, refresh tokens and other security fields are never returned by member routes.

## Validation, paging and errors

- UUID route IDs must use canonical lowercase form.
- Name: string, trimmed, 1–100 characters.
- Description: optional/null, at most 2000 characters. Missing PATCH field is unchanged; null clears description; null name is invalid.
- PATCH must include at least one permitted field; unknown fields are rejected.
- Add-member email is trimmed/lowercased and must be a valid basic email address, max 255 characters. Unknown account: 404 USER_NOT_FOUND; duplicate membership: 409 MEMBER_EXISTS.
- Role input is exactly ADMIN or MEMBER; OWNER/GUEST/unknown roles are rejected.
- Request JSON is bounded to 16 KiB; duplicate JSON keys, malformed JSON and unsupported content types are rejected.
- List queries support limit (1–100, default 50) and offset (0–100000, default 0). Responses include page: {"limit":50,"offset":0,"hasMore":false}. Lists have stable creation/join timestamp + ID ordering. Unknown query keys are rejected.
- Nonmember and nonexistent workspace both return 404 WORKSPACE_NOT_FOUND, so existence is not disclosed to outsiders. Insufficient member role returns 403 FORBIDDEN.
- OWNER leave/removal/demotion returns 409 OWNER_PROTECTED; use delete workspace instead.
- Duplicate or conflicting operations produce stable errors without SQL/server details.

```json
{"error":{"code":"FORBIDDEN","message":"This action requires a workspace administrator."}}
```

Other statuses: 400 INVALID_REQUEST, 401 UNAUTHENTICATED, 404 MEMBER_NOT_FOUND / ROUTE_NOT_FOUND, 405 METHOD_NOT_ALLOWED, 413 PAYLOAD_TOO_LARGE, 415 UNSUPPORTED_MEDIA_TYPE, 503 DATABASE_UNAVAILABLE.

## Authorization and database behavior

Existing Phase 14 Workspace, WorkspaceMember and User models are reused. No Phase 16 schema/migration is needed. The historical GUEST enum remains for legacy storage compatibility but is not assigned or accepted by these APIs; unsupported legacy membership roles fail closed and are excluded from workspace/member API lists.

Workspace creation and its OWNER membership are one transaction. Mutations lock the workspace row before reading roles and updating memberships, serializing concurrent API mutations within the workspace and avoiding authorization changes between a check and a write. Permission rules are centralized in WorkspaceAccess.

Workspace deletion uses existing database cascades for memberships, channels/messages and folder/file metadata. It never deletes User rows or remote file bytes. Removing/leaving a member deletes only membership; their User and historical messages/files remain.

Only workspace/member Phase 16 routes are added. No channel-management API, team-chat enhancements, profile feature, or Phase 17 module.
