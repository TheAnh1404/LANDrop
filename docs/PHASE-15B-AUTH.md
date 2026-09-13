# Phase 15B — Frontend authentication and session

Phase 15B adds authentication to the JavaFX frontend. The implementation is isolated under `frontend/src/main/java/com/landrop/ui/auth/` and keeps LAN discovery, chat, transfer, and realtime services unchanged.

The Phase 15A authentication service was not present in this checkout while this client was implemented. The wire shapes below are therefore the frontend integration contract used by the adapter and tests. They must be reconciled with the Phase 15A backend before connecting a live server.

## API contract used by the frontend adapter

The configured base URL includes `/api`, for example `http://localhost:3000/api`. The JavaFX launcher reads it from `-Dlandrop.auth.base-url=...`; when omitted it defaults to `http://localhost:3000/api`. Requests are JSON and successful responses use the `{ "data": ... }` envelope.

| Method | Route | Request | Successful response |
| --- | --- | --- | --- |
| `POST` | `/api/auth/register` | `username`, `displayName`, `email`, `password` | access/refresh tokens, expiry, user |
| `POST` | `/api/auth/login` | `email`, `password` | access/refresh tokens, expiry, user |
| `POST` | `/api/auth/refresh` | `refreshToken` | access token, optional rotated refresh token, expiry |
| `POST` | `/api/auth/logout` | `refreshToken` and bearer access token when available | `200` JSON or `204` |
| `GET` | `/api/auth/me` | bearer access token | current user |

Authentication responses are expected to contain:

```json
{
  "data": {
    "accessToken": "access-token",
    "refreshToken": "refresh-token",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": {
      "id": "user-id",
      "username": "ada",
      "displayName": "Ada",
      "email": "ada@example.com",
      "avatarUrl": null,
      "createdAt": "2026-09-13T00:00:00Z",
      "updatedAt": "2026-09-13T00:00:00Z"
    }
  }
}
```

The current-user response may contain the user fields directly in `data`; a nested `data.user` shape is also accepted for `/auth/me`. Unknown response fields are ignored, but required authentication fields, token strings, expiry, user ID, username, email, and timestamps are validated. Passwords are never stored in auth state or session storage.

## Frontend architecture

```text
Login/Register FXML controllers
              |
       AuthStateService
              |
            AuthApi
              |
        HttpAuthApi
              |
       Phase 15A HTTP API
```

`AuthModels` contains authentication requests, `AuthUser`, and `AuthSession`. The existing `Domain.User` profile is reused inside `AuthUser`; the authentication-only username is kept beside it because the Phase 14B profile record does not contain a username field.

`AuthStateService` is the single state owner. It exposes JavaFX properties for `user`, `isAuthenticated`, `isLoading`, and `authError`, plus asynchronous `login`, `register`, `restoreSession`, `refreshAccessToken`, and `logout` operations. Controllers do not call HTTP or parse JSON.

`SessionStore` persists only the refresh token. The default desktop implementation uses `PreferencesSessionStore`; tests and callers that need ephemeral sessions can use `InMemorySessionStore`. Access tokens remain memory-only. A production desktop deployment can replace the store with an OS keychain implementation without changing UI or API code.

`AuthenticatedApiTransport` adds `Authorization: Bearer <accessToken>` to Phase 14B reads. A `401` triggers one shared refresh operation, then retries the original request once. Concurrent expired requests share the same in-flight refresh future. If refresh fails, the session and stored token are cleared and the user sees a safe session-expired message.

## Application gate and pages

`MainApp` now shows a loading scene while `restoreSession()` checks the stored refresh token and calls `/auth/me`. Protected Core setup/dashboard content is loaded only after a valid session is established. A missing, expired, or invalid session opens the Login page.

- `auth-login-view.fxml` and `LoginController` provide email, password, submit/loading state, validation, safe API error display, and Register navigation.
- `auth-register-view.fxml` and `RegisterController` provide username, display name, email, password, confirmation, validation, safe API error display, and Login navigation.
- The existing Main view has a Sign out button. Logout closes the active LAN Core state, calls `/auth/logout`, clears the session even if the network call fails, and returns to Login.

The JavaFX page transition is the project's protected-route equivalent:

```text
startup -> session loading -> Login or existing setup/application
Login/Register success -> existing setup -> existing Core application
Sign out -> clear Core/session -> Login
```

No workspace management, task, notification, search, activity, profile management, or Phase 16 UI was added.

## Error behavior

The HTTP adapter maps network failure and timeout to stable `NETWORK_ERROR` and `TIMEOUT` codes. Structured backend status codes are retained in `ApiException`, while `AuthStateService` converts them to user-facing messages:

- invalid credentials: “Email or password is incorrect.”
- duplicate account details: “An account with these details already exists.”
- invalid form data: “Check the information and try again.”
- unavailable service: “The authentication service is unavailable. Try again.”
- invalid restore or refresh: “Your session expired. Please sign in again.”

Raw server messages, stack traces, token values, and proxy response bodies are not displayed in the UI.

## Verification

Focused authentication checks:

```sh
mvn -o -pl frontend -am test -Dtest='com.landrop.ui.auth.**Test' -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl frontend -am test -Dtest=FXMLLoaderTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine=--enable-native-access=ALL-UNNAMED
mvn -o -pl frontend -am test -Dlandrop.transfer.download.dir=/private/tmp/LANDrop-phase15b-tests -DargLine=--enable-native-access=ALL-UNNAMED
```

The first command checks DTO decoding, validation, state transitions, logout cleanup, concurrent refresh, and authenticated retry. The second loads all existing views plus Login/Register FXML. The third is the full backend/frontend regression suite. The current checkout passes 11 focused auth tests, 51 frontend tests, and 170 backend tests. A live Phase 15A check remains pending until the backend exposes the routes and confirms the response names above.

For a local backend, pass the URL as a JVM property when launching JavaFX:

```sh
mvn -pl frontend javafx:run -Dlandrop.auth.base-url=http://localhost:3000/api \\
  -Djavafx.args="--peer-port=5001 --display-name=PeerA"
```
