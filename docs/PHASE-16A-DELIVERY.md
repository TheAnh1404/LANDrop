# Phase 16A — Workspace & Members delivery

Phase 16A adds the backend workspace foundation on the existing Java 21 HTTP server. It is intentionally limited to workspace persistence, membership and the three basic roles `OWNER`, `ADMIN` and `MEMBER`.

## Implementation

- `WorkspaceService` performs workspace and membership operations and uses transactions for every mutation.
- `WorkspaceRepository` supports scoped transactions, row locks and safe member projections.
- `WorkspaceAccess` is the single authorization helper for member, admin and owner checks. Legacy `GUEST` memberships fail closed.
- `WorkspaceHttpServer` registers `/api/workspaces` routes on the existing `AuthHttpServer`; no second HTTP process or port is required.
- Adding a member is by email for an existing account. It creates a `MEMBER` membership immediately; no email delivery or invitation table was added.
- Existing V1/V2 schema cascades are reused. Workspace deletion removes memberships and workspace-owned child records, never users. No Phase 16 migration was required.

## API

The complete request/response contract, validation rules, pagination and error codes are in [PHASE-16A-API-CONTRACT.md](PHASE-16A-API-CONTRACT.md).

Implemented routes:

```text
POST   /api/workspaces
GET    /api/workspaces
GET    /api/workspaces/:workspaceId
PATCH  /api/workspaces/:workspaceId
DELETE /api/workspaces/:workspaceId
GET    /api/workspaces/:workspaceId/members
POST   /api/workspaces/:workspaceId/members
PATCH  /api/workspaces/:workspaceId/members/:userId
DELETE /api/workspaces/:workspaceId/members/:userId
POST   /api/workspaces/:workspaceId/leave
```

All routes derive identity from the verified Bearer JWT. Member responses contain only public user fields (`id`, username, display name and avatar URL); password and token fields are never selected.

## Verification

Commands run from the repository root:

```sh
mvn -o -pl backend -am test-compile -DskipTests
mvn -o -pl backend -am test -Dtest=WorkspacePostgresTest -Dlandrop.test.postgres=true
git diff --check
```

`WorkspacePostgresTest` creates and drops a random Neon schema and passed 3/3 tests covering owner creation/list isolation, member add/role/remove/leave rules, outsider denial, update and deletion. The compile check passed. Existing Phase 1–15.5A tests remain available; run the backend regression command in the root README to execute them.

The backend regression completed with 195/195 tests. A full frontend test run still reports three failures in pre-existing Phase 14/15 frontend tests (`CloudMapperTest`, `CloudDataExampleTest` and `RealtimeChatServiceTest`); the frontend package itself compiles and packages successfully. These failures are outside the Phase 16A backend surface and were not changed here.

## Scope boundary

No workspace UI, channel management, task, notification, search, activity log, full RBAC or Phase 17 feature was added.
