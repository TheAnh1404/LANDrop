# LANDrop frontend

The desktop frontend uses Java 21 and JavaFX 21.0.2. Phase 14B adds an isolated, read-only persistent-data foundation for `User`, `Workspace`, `WorkspaceMember`, `Channel`, `Message`, `Folder`, and `File` metadata. It follows the existing Java record conventions. There is no TypeScript application in this repository.

The [Phase 14B API contract](../docs/PHASE-14B-API-CONTRACT.md) is a **proposed frontend v1 contract pending reconciliation with the backend persistence layer and HTTP API**. It documents every field, nullable value, route, pagination rule, error shape, and backend handoff requirement. Backend endpoint handlers and database access are outside this frontend module.

## Code placement

```text
frontend/src/main/java/com/landrop/ui/
  controllers/, services/, models/  Existing JavaFX and LAN presentation code
  cloud/model/                     Frontend persistent domain records
  cloud/dto/                       Expected API JSON response records
  cloud/mapping/                   JSON validation and DTO-to-domain conversion
  cloud/api/                       Outbound HTTP client and offline mock transport
  cloud/CloudDataExample.java       Opt-in development console example
frontend/src/main/resources/com/landrop/ui/cloud/
  phase14-mock.json                 Development fixtures
frontend/src/test/java/com/landrop/ui/cloud/
                                   Contract, transport, and mock checks
docs/PHASE-14B-API-CONTRACT.md       Frontend/backend handoff contract
```

The `cloud/api` package is a frontend client; it does not host HTTP endpoints. Backend schema, migrations, seed, repositories, and endpoint handlers belong to the backend implementation maintained separately. This change adds no backend production code.

## Data boundary

```text
HTTP JSON or development fixtures
                 |
                DTO
                 |
               Mapper
                 |
        Immutable domain records
                 |
          Presentation consumer
```

`com.landrop.ui.cloud.dto.Dto` groups wire records. `com.landrop.ui.cloud.model.Domain` groups domain records, keeping persistent `Message` and `File` distinct from Core chat packets, Java filesystem files, and active transfer state. `CloudJson` decodes response envelopes, and `CloudMapper` converts validated DTOs to domain data. Timestamp strings become `Instant`; decimal-string file sizes become `BigInteger`. All listed JSON fields are required, including fields whose value may be `null`; unknown fields are tolerated.

`CloudApi` provides `users`, `workspaces`, `channels`, `messages`, `files`, and `folders` clients. Every operation is asynchronous and read-only. `CloudApi.mock()` and `CloudApi.http(baseUri)` share the same DTO mapping path, so changing the data source requires no raw-response handling in presentation code. HTTP errors never cause an automatic fallback to mock data.

## Usage

Choose the client at composition time:

```java
import com.landrop.ui.cloud.api.CloudApi;
import com.landrop.ui.cloud.api.PageRequest;
import java.net.URI;
import javafx.application.Platform;

CloudApi api = CloudApi.mock();

// When the backend API is available:
// CloudApi api = CloudApi.http(URI.create("http://localhost:3000/api/"));
// For protected Phase 14B reads:
// CloudApi api = CloudApi.authenticatedHttp(URI.create("http://localhost:3000/api/"), authState);
```

The base URI includes `/api/`; HTTP requests use a 10-second default timeout. An existing presentation consumer can load data without blocking JavaFX:

```java
api.workspaces().list(PageRequest.first())
        .whenComplete((page, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                errorLabel.setText("Could not load workspace data: " + failure.getMessage());
                return;
            }
            workspaceItems.setAll(page.items());
        }));
```

`errorLabel` and `workspaceItems` illustrate caller-owned presentation state; Phase 14B introduces no workspace screen. Other reads follow the same boundary:

```java
api.users().get(userId);
api.workspaces().get(workspaceId);
api.workspaces().members(workspaceId, PageRequest.first());
api.channels().list(workspaceId, PageRequest.first());
api.messages().list(channelId, new PageRequest(25, null));
api.files().list(workspaceId, PageRequest.first());
api.folders().list(workspaceId, PageRequest.first());
```

Pass the returned next cursor in a new `PageRequest` to load another page; stop when it is `null`. Cursors belong to the collection that issued them. Do not call `join()` or `get()` on the JavaFX application thread.

Run `com.landrop.ui.cloud.CloudDataExample.main` from an IDE with the frontend module classpath, or use its smoke test below. This opt-in console example demonstrates read-only mock loading and mapped output without adding UI flows or connecting to the network. Development fixtures include users, one workspace, memberships, channels, text and file messages, folders, and file metadata. Fixture records obey the same proposed API contract and relationship rules as HTTP responses.

## Verification commands

Run from the repository root with JDK 21 or newer and Maven. `-o` uses the local dependency cache; a fresh development environment must first resolve Maven dependencies.

```sh
mvn -o -pl frontend -am package -DskipTests
mvn -o -pl frontend -am test -Dtest=CloudDataExampleTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o test -Dlandrop.transfer.download.dir=/private/tmp/LANDrop-phase14b-tests -DargLine=--enable-native-access=ALL-UNNAMED
```

The first command builds both modules needed by the frontend. The second runs and checks the mock console example without a JavaFX toolkit or network. The third runs the complete regression suite, including existing discovery, chat, file transfer, and JavaFX tests. JavaFX tests require a graphical session; network integration tests bind local sockets. The explicit download directory keeps test downloads separate from normal user downloads. TypeScript checking is not applicable to this JavaFX-only frontend.

These commands describe how to verify the implementation; their inclusion is not a claim that they have run successfully in every environment. A live backend check requires an available API and schema reconciliation.

## Compatibility and scope

Existing `UIStateService`, `UIMessage`, Core chat/realtime, discovery, and LAN transfer flows keep their current behavior and identities. The persistent-data client is not started by the application and does not replace Core storage or network services. Mock data never appears in existing LAN conversations or transfer lists.

Phase 14B contains no workspace management UI, tasks, notifications, search, activity log, production deployment, upload/download service, or Phase 16 work. Phase 15B authentication is documented separately in [PHASE-15B-AUTH.md](../docs/PHASE-15B-AUTH.md); its backend wire contract remains pending Phase 15A reconciliation.
