# FOUNDATION-04B — Client Login and Device Enrollment

## Delivery status

FOUNDATION-04B client integration is complete. The server and client now use the same authenticated realtime contract; live PostgreSQL integration tests cover enrollment, binding, revoke, and disabled-account handling. No FOUNDATION-05 work is included.

## 1. Files

New production files under `frontend/src/main/java/com/landrop/ui/`:

- `bootstrap/BootstrapState.java`, `BootstrapErrors.java`, `ClientBootstrapService.java`
- `auth/api/ServerSessionStore.java`

Updated production files:

- `MainApp.java`: startup composition, READY gate, server switch, cleanup.
- `auth/services/AuthStateService.java`: session invalidation, refresh coordination, stale-response rejection, safe errors.
- `auth/api/HttpAuthApi.java`, `AuthenticatedApiTransport.java`, `cloud/api/ApiClient.java`: FOUNDATION-04A HTTP compatibility and terminal auth errors.
- `device/CurrentDeviceService.java`: enrollment and rename independent of LAN.
- `realtime/ClientRealtimeService.java`, `services/WebSocketRealtimeTransport.java`: authenticated binding, reconnect, terminal errors, timer/socket cleanup.
- `controllers/LoginController.java`, `SettingsController.java`, `SetupController.java` and the login/settings FXML resources.

New tests: `ClientBootstrapServiceTest`, `CurrentDeviceServiceTest`, `ServerSessionStoreTest`. Extended tests: `AuthStateServiceTest`, `HttpAuthApiTest`, `ClientRealtimeServiceTest`.

## 2. Bootstrap state machine

`INITIALIZING → CONNECTING (health) → AUTHENTICATING (restore) → AUTH_REQUIRED` when no session exists.

`AUTH_REQUIRED → AUTHENTICATING (login + /auth/me) → ENROLLING_DEVICE → CONNECTING (authenticated realtime) → READY`.

Failure states: `SERVER_UNREACHABLE`, `ERROR`, and transient `SESSION_EXPIRED → AUTH_REQUIRED`. Temporary socket loss uses `RECONNECTING → READY` without another login. Explicit state replaces startup flags; features mount only after READY. A reconnect overlay prevents interaction with the retained feature scene.

## 3. Login architecture

LoginController delegates to ClientBootstrapService, which reuses AuthStateService and HttpAuthApi. Email or username and password are sent to `POST /api/auth/login` as `identifier` and `password`. The password field is cleared after submission. Authentication errors use curated messages rather than backend response details.

System administrators are kept out of the Client flow. `POST /api/auth/login`, `/api/auth/refresh`, and `/api/auth/me` accept regular users only; a `SYSTEM_ADMIN` receives `ADMIN_LOGIN_REQUIRED`. The Server Control Center signs administrators in through `POST /api/admin/auth/login`, while its `/api/admin/*` routes still require the `SYSTEM_ADMIN` claim. This prevents an admin token from entering device enrollment or the client realtime session.

## 4. Session restore and storage

Restore refreshes the stored credential and verifies identity through `GET /api/auth/me`. Access tokens stay in memory. ServerSessionStore scopes refresh credentials by API endpoint and session profile. Windows uses current-user DPAPI; the encrypted value is stored in Preferences. Secrets are passed to the protection subprocess through stdin, not command arguments.

Other platforms and unavailable protection use process memory, requiring login after restart. Legacy unscoped plaintext credentials are cleared at startup rather than migrated to an unknown server. Temporary network errors retain valid stored refresh credentials. Invalidated asynchronous operations cannot restore a logged-out session.

## 5. Device enrollment and rename

The HTTP adapter follows the in-progress 04A `DeviceHttpHandler` and `DeviceDto`:

- `POST /api/devices/enroll`: `clientInstanceId`, `deviceName`, `platform`, `clientVersion`.
- HTTP 201 response: `id`, `userId`, `clientInstanceId`, `name`, and device metadata.
- `PATCH /api/devices/{id}`: `deviceName`; update currentDevice only from the successful response.

Flat responses and the earlier `data` envelope are accepted. Response user/installation IDs must match the authenticated user and installation. The client never generates a server device ID. `DEVICE_ALREADY_OWNED` has a distinct recovery message. Hostname supplies the default device name.

## 6. Identity separation

| Identity | Authority / purpose |
| --- | --- |
| User ID | `/api/auth/me`; signed-in account |
| Device ID | Enrollment response; server-owned device |
| Client Instance ID | Persisted installation UUID; survives logout |
| Peer ID | Existing LAN engine; not an account or device ID |

Settings shows the signed-in user, enrolled device/name/ID, and existing server diagnostics. Bootstrap exposes currentUser, currentDevice, serverConnection, and realtimeConnection; auth state remains in AuthStateService.

## 7. Realtime binding contract

The transport sends the access token only in the WebSocket Authorization header. HELLO includes the enrolled `deviceId`, installation ID, device name, platform, and existing protocol/version metadata. It does not send a self-asserted `userId`.

The server validates the bearer access token from the WebSocket upgrade `Authorization: Bearer <accessToken>` header, resolves the `sid` claim to an active refresh-token session, checks the user and enrolled device ownership/clientInstanceId, then emits the only accepted WELCOME. WELCOME contains `authenticated: true`, `userId`, `deviceId`, `sessionId`, `connectionId`, and compatible protocol metadata. Token and user ID are never sent in HELLO payload. Legacy unauthenticated WELCOME is rejected with `AUTH_BINDING_REQUIRED`; it cannot produce READY.

Reconnect obtains a valid access token, preserves installation/device identity, and repeats the binding. Connection generations reject stale health/connection completions. There is one socket and one heartbeat schedule.

## 8. Logout, expiry, and revocation

Logout calls the server, clears local credentials/user/device binding immediately, closes realtime and subscriptions, and returns to AUTH_REQUIRED. It preserves clientInstanceId and the local device name. SESSION_EXPIRED, SESSION_REVOKED, and ACCOUNT_DISABLED clear invalid auth and stop authenticated retries. HTTP terminal auth errors follow the same invalidation path; generic 401 responses retain the existing single-refresh behavior.

## 9. Server switching

Changing an API or WebSocket endpoint closes the previous bootstrap and socket, invalidates the previous auth service, clears server-bound credentials, rebuilds the HTTP clients, and requires login. Retrying unchanged endpoints preserves the session. Startup validates the API/WebSocket host pairing and disallows HTTPS-to-WS downgrade before sending credentials.

## 10. Regression results

The broad local run completed with **235 backend tests passed** and **111 frontend tests recorded: 109 passed, 2 live gates skipped**, before the final contract alignment. The live `Foundation04AuthenticationDeviceTest` now exercises the aligned bearer handshake, device binding, session revoke, and disabled-account close path.

Latest frontend run: **113 tests recorded: 111 passed, 2 live gates skipped**. Added checks cover startup/login, `/auth/me`, enrollment/rename, restore, revoke/disabled account cleanup, stale refresh/enrollment responses, server-scoped storage, installation persistence, reconnect binding, duplicate socket prevention, and duplicate heartbeat prevention.

The existing multi-device matrix was excluded. Database integration, opt-in PostgreSQL suites, and performance benchmarking were excluded from the broad run. No new multi-device orchestration was added.

## 11. Build and type checking

```powershell
mvn -pl frontend -am verify "-Dtest=*,!DatabaseIntegrationTest,!AuthIntegrationTest,!*PostgresTest,!PerformanceBaselineTest,!MultiDeviceTestMatrixTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl backend -am install "-Dmaven.test.skip=true"
mvn -pl frontend verify "-Dtest=*,!MultiDeviceTestMatrixTest"
```

Backend clean test compilation passes, targeted frontend verification passes (30 tests), and `npm run client:test` passes (5 tests). TypeScript is **not applicable**: this frontend is Java 21/JavaFX, checked by Maven compilation and FXML loading tests.

## 12. Known limitations

- The live PostgreSQL suite depends on `DATABASE_URL`; without it, the suite is skipped.
- Run the completed server for Admin User + Device visibility, app restart, and device rename acceptance.
- Two live LAN gates need `landrop.gate.api` / `landrop.gate.ws`; the recovery gate additionally requires an externally managed server restart.
- macOS/Linux secure persistent credential adapters remain unavailable; those platforms intentionally use memory-only credentials.

FOUNDATION-04B COMPLETE
