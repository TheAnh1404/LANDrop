# Phase 14B delivery

Phase 14B frontend implementation is complete using JavaFX-native Java types, as requested for this checkout. Implementation lives in `frontend/`; the shared API contract lives in `docs/`. The existing backend now contains the Phase 14A PostgreSQL schema, migration, seed, models, services, and repositories; this delivery does not modify those backend artifacts or add HTTP endpoint handlers.

The contract is **proposed frontend v1**. Backend database artifacts are present, but the HTTP API and field-level contract reconciliation are not available in this frontend checkout, so live API verification remains pending backend handoff. This delivery does not claim those checks passed.

## Files created

Paths below are relative to the repository root.

```text
docs/PHASE-14B-API-CONTRACT.md
docs/PHASE-14B-DELIVERY.md
frontend/README.md
frontend/src/main/java/com/landrop/ui/cloud/CloudDataExample.java
frontend/src/main/java/com/landrop/ui/cloud/model/Domain.java
frontend/src/main/java/com/landrop/ui/cloud/dto/Dto.java
frontend/src/main/java/com/landrop/ui/cloud/mapping/CloudJson.java
frontend/src/main/java/com/landrop/ui/cloud/mapping/CloudMapper.java
frontend/src/main/java/com/landrop/ui/cloud/api/ApiException.java
frontend/src/main/java/com/landrop/ui/cloud/api/ApiPaths.java
frontend/src/main/java/com/landrop/ui/cloud/api/ApiTransport.java
frontend/src/main/java/com/landrop/ui/cloud/api/ChannelsApi.java
frontend/src/main/java/com/landrop/ui/cloud/api/CloudApi.java
frontend/src/main/java/com/landrop/ui/cloud/api/FilesApi.java
frontend/src/main/java/com/landrop/ui/cloud/api/FoldersApi.java
frontend/src/main/java/com/landrop/ui/cloud/api/HttpApiTransport.java
frontend/src/main/java/com/landrop/ui/cloud/api/MessagesApi.java
frontend/src/main/java/com/landrop/ui/cloud/api/MockApiTransport.java
frontend/src/main/java/com/landrop/ui/cloud/api/PageRequest.java
frontend/src/main/java/com/landrop/ui/cloud/api/RequestClient.java
frontend/src/main/java/com/landrop/ui/cloud/api/UsersApi.java
frontend/src/main/java/com/landrop/ui/cloud/api/WorkspacesApi.java
frontend/src/main/resources/com/landrop/ui/cloud/phase14-mock.json
frontend/src/test/java/com/landrop/ui/cloud/CloudDataExampleTest.java
frontend/src/test/java/com/landrop/ui/cloud/CloudMapperTest.java
frontend/src/test/java/com/landrop/ui/cloud/HttpApiTransportTest.java
frontend/src/test/java/com/landrop/ui/cloud/api/MockApiTransportTest.java
```

## Files modified

| File | Change |
| --- | --- |
| `README.md` | Project overview, Windows/LAN runbook, frontend foundation and API contract links |
| `frontend/pom.xml` | Explicit Jackson Databind 2.17.0 dependency, matching the existing Core version |

## Models, contract, and client

`Domain` defines immutable `User`, `Workspace`, `WorkspaceMember`, `Channel`, `Message`, `Folder`, and `File` records. All carry persistent IDs and creation/update timestamps. `MemberRole` and `MessageType` enumerate membership and message kinds. Nullable references remain explicit; file sizes map exactly from decimal strings to `BigInteger`; timestamps map to `Instant`.

`Dto` defines the seven matching response records and shared `ItemResponse<T>`, `ListResponse<T>`, and `PageInfo` envelopes. `CloudJson` rejects malformed JSON, missing fields, scalar coercion, and duplicate keys. `CloudMapper` validates and converts DTOs before presentation code consumes domain data.

The [API contract](PHASE-14B-API-CONTRACT.md) specifies these eight GET routes:

```text
/api/users/:id
/api/workspaces
/api/workspaces/:workspaceId
/api/workspaces/:workspaceId/members
/api/workspaces/:workspaceId/channels
/api/channels/:channelId/messages
/api/workspaces/:workspaceId/files
/api/workspaces/:workspaceId/folders
```

Items use `{data: entity}`; lists use `{data: [], page: {nextCursor}}`. All lists support `limit` (default 50, maximum 100) and an opaque cursor, ordered by creation time and ID. Missing resources and empty collections have distinct responses. The contract includes error envelopes, relationships, enum values, and required/null rules.

Use `CloudApi.mock()` for development and `CloudApi.http(apiBaseUri)` for the future backend. Both return `CompletableFuture` domain results through the same DTO validation and mapper. HTTP uses configurable timeout handling, encoded IDs/query parameters, and structured errors; it never substitutes fixtures on failure. See [frontend usage and code placement](../frontend/README.md).

## Mock data

The fixture contains 3 users, 1 workspace, 3 memberships (owner/admin/member), 3 channels including an empty channel, 4 messages (text, reply, and file references), 2 folders forming a root/nested hierarchy, and 3 metadata-only files including an unfiled file. The dataset uses fictional identities and internally consistent foreign keys.

`CloudDataExample` renders every entity category through the public mapped mock client in an opt-in console example. No workspace management or other new product screen was added. Mock data is not inserted into LAN chat or transfer state.

## Verification results

Verified on 2026-09-13 with Maven 3.9.16 and JDK 26.0.2.1, compiling for Java 21:

| Check | Result |
| --- | --- |
| Frontend and prerequisite backend packaging | BUILD SUCCESS; frontend JAR generated |
| Java compilation | No compilation errors |
| TypeScript | Not applicable: JavaFX-native types only, per user instruction |
| Existing backend/Core suite | 170 passed |
| Existing frontend JavaFX/FXML and service suite | 12 passed |
| New mapper/JSON checks | 10 passed |
| New mock route/pagination/fixture checks | 7 passed |
| New HTTP transport/mapping/error checks using a local test server | 8 passed |
| New mock presentation example | 1 passed |
| Total | **208 passed, 0 failures, 0 errors, 0 skipped** |
| Whitespace check | `git diff --check` passed |
| Agreed Prisma schema/live API | Pending backend artifacts; not tested |

The first full-suite attempt inside the sandbox failed because socket binding was denied (`Operation not permitted`), before frontend tests ran. The same command passed outside the sandbox with the required permission. Maven emitted existing JavaFX dependency-model warnings without build or test failures.

Compatibility verification covers automated LAN discovery, chat/realtime, transfer/resume/swarming/failover, and JavaFX layout/service tests. No manual multi-machine LAN or visual mock screen test was performed. Existing Core source, frontend controllers, `UIStateService`, and `UIMessage` are unchanged; persistent IDs are not inferred from peer names or packet request IDs. No Core compatibility issue was found in these checks.

## Exact build, test, and final inspection commands used

Run from `/Users/theanhmac/Project/LANDrop`:

```sh
mvn -pl frontend -am -Dtest=CloudMapperTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -o -pl frontend -am -Dtest=MockApiTransportTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -o -pl frontend -am package -DskipTests
mvn -o test -Dlandrop.transfer.download.dir=/private/tmp/LANDrop-phase14b-tests -DargLine=--enable-native-access=ALL-UNNAMED
git diff --check
git diff --name-only
git ls-files --others --exclude-standard
```

The full-suite command was executed once inside the sandbox and then repeated outside it after the socket restriction was identified. Mapper checks were repeated after adding the self-reply validation. The full suite includes the HTTP, mock example, and existing JavaFX/Core tests.

Test counts were confirmed from Maven Surefire reports with:

```sh
awk '/Tests run:/ {tests += $3; failures += $5; errors += $7; skipped += $9} END {print "backend tests=" tests ", failures=" failures ", errors=" errors ", skipped=" skipped}' backend/target/surefire-reports/*.txt
awk '/Tests run:/ {tests += $3; failures += $5; errors += $7; skipped += $9} END {print "frontend tests=" tests ", failures=" failures ", errors=" errors ", skipped=" skipped}' frontend/target/surefire-reports/*.txt
```

No authentication, login/register, workspace management UI, task, notification, search, activity log, production deployment, or Phase 15 functionality was added.

**PHASE 14B COMPLETE**
