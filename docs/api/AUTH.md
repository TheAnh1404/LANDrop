# Authentication and Session API Contract

**Status: Completed & Verified (Backend Implementation v1.0.0)**  
**Target Audience: Frontend Engineers (Phase 15B), Security Auditors, API Integrators**

---

## 1. Executive Summary & Architecture

Phase 15A introduces the authentication and user session layer for LANDrop, backed by **Neon PostgreSQL** and implementing zero-external-framework Java 21 LTS security services.

- **Stack**: 100% Java 21 LTS, standard `com.sun.net.httpserver.HttpServer` with Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
- **Cryptographic Password Hashing**: OWASP-recommended **PBKDF2WithHmacSHA256** with 600,000 iterations, 16-byte cryptographically secure random salt, and constant-time verification.
- **JWT Cryptography**: RFC 7519 standard HMAC-SHA256 signed tokens using standard Java SE `javax.crypto.Mac`.
- **Session Persistence & Revocation**: Refresh tokens are cryptographically hashed (SHA-256) and persisted in Neon PostgreSQL (`refresh_tokens` table). Raw refresh tokens are never stored in plaintext in the database.
- **Rotation & Reuse Protection**: Refreshing a session rotates both the access token and the refresh token, revoking the previous session to detect and prevent replay attacks.
- **Security Invariant**: `passwordHash` is **NEVER** exposed in HTTP responses, DTOs, logs, or client-facing models.

---

## 2. Server Configuration & Base URL

- **Default Port**: `3000` (configurable via environment variable `AUTH_PORT`).
- **Base URL**: `http://localhost:3000` (or `http://<host>:<port>`).
- **Content Type**: `application/json; charset=utf-8`.
- **CORS Support**:
  - Automatically enabled on all endpoints.
  - Preflight `OPTIONS` returns HTTP `204 No Content`.
  - Response headers include:
    - `Access-Control-Allow-Origin: *`
    - `Access-Control-Allow-Methods: GET, POST, OPTIONS`
    - `Access-Control-Allow-Headers: Content-Type, Authorization`

---

## 3. Token Specifications & Lifetimes

| Token Type | Alg | Default Lifetime | Storage Location (Frontend) | Intended Usage |
| :--- | :--- | :--- | :--- | :--- |
| **Access Token** | `HS256` | 15 minutes (`900s`) | In-memory only (e.g. state store / memory variable) | Sent in `Authorization: Bearer <token>` for protected endpoints |
| **Refresh Token** | `HS256` | 7 days (`604800s`) | Persistent storage (e.g. Secure Store, OS Keychain, HTTP-only cookie) | Sent to `POST /api/auth/refresh` to obtain new token pair |

### Access Token Claims Payload

```json
{
  "sub": "3ff5fbaa-e14c-47bb-9ef1-18c6d4d3820a",
  "email": "alice@landrop.local",
  "username": "alice",
  "type": "access",
  "iat": 1726243200,
  "exp": 1726244100,
  "jti": "d6a3bf03-8d96-4a1e-8e68-07f9c2d79040"
}
```

---

## 4. Endpoints Specification

### 4.1 Register New User
- **Route**: `POST /api/auth/register`
- **Access**: Public
- **Request Body**:
  ```json
  {
    "email": "alice@landrop.local",
    "username": "alice",
    "password": "Password123!",
    "displayName": "Alice Vance"
  }
  ```
  - `email`: Required, valid email format regex (`^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$`).
  - `username`: Required, 3-30 characters, alphanumeric with `_` or `-`.
  - `password`: Required, minimum 8 characters.
  - `displayName`: Optional. If blank, defaults to `username`.

- **Success Response (HTTP 201 Created)**:
  ```json
  {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "user": {
      "id": "3ff5fbaa-e14c-47bb-9ef1-18c6d4d3820a",
      "email": "alice@landrop.local",
      "username": "alice",
      "displayName": "Alice Vance",
      "avatarUrl": null,
      "createdAt": "2026-09-13T15:30:00Z",
      "updatedAt": "2026-09-13T15:30:00Z"
    }
  }
  ```

- **Error Responses**:
  - `400 Bad Request`: Validation failure (short password, invalid email format).
  - `409 Conflict`: Email already registered or username already taken.

---

### 4.2 Login
- **Route**: `POST /api/auth/login`
- **Access**: Public
- **Request Body**: Accepts login via either `email`, `username`, or generic `emailOrUsername`:
  ```json
  {
    "emailOrUsername": "alice@landrop.local",
    "password": "Password123!"
  }
  ```
  *(Alternative accepted keys: `{"username": "alice", "password": "..."}` or `{"email": "alice@landrop.local", "password": "..."}`)*

- **Success Response (HTTP 200 OK)**:
  ```json
  {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "user": {
      "id": "3ff5fbaa-e14c-47bb-9ef1-18c6d4d3820a",
      "email": "alice@landrop.local",
      "username": "alice",
      "displayName": "Alice Vance",
      "avatarUrl": null,
      "createdAt": "2026-09-13T15:30:00Z",
      "updatedAt": "2026-09-13T15:30:00Z"
    }
  }
  ```

- **Error Responses**:
  - `400 Bad Request`: Missing identifier or password.
  - `401 Unauthorized`: Invalid credentials (incorrect password or user not found).

---

### 4.3 Refresh Access Token
- **Route**: `POST /api/auth/refresh`
- **Access**: Public (presents refresh token)
- **Request Body**:
  ```json
  {
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
  ```

- **Success Response (HTTP 200 OK)**:
  ```json
  {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "user": {
      "id": "3ff5fbaa-e14c-47bb-9ef1-18c6d4d3820a",
      "email": "alice@landrop.local",
      "username": "alice",
      "displayName": "Alice Vance",
      "avatarUrl": null,
      "createdAt": "2026-09-13T15:30:00Z",
      "updatedAt": "2026-09-13T15:30:00Z"
    }
  }
  ```
  *Note: The previous refresh token is revoked upon refresh. The frontend client MUST replace both `accessToken` and `refreshToken` in its storage.*

- **Error Responses**:
  - `400 Bad Request`: Missing `refreshToken` parameter.
  - `401 Unauthorized`: Token is expired, signature is invalid, or session was already revoked.

---

### 4.4 Logout
- **Route**: `POST /api/auth/logout`
- **Access**: Public or Authenticated
- **Request Body**:
  ```json
  {
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
  ```

- **Success Response (HTTP 200 OK)**:
  ```json
  {
    "success": true,
    "message": "Logged out successfully"
  }
  ```
  *Note: Marks the session as `revoked = TRUE` in the database. Any subsequent attempts to use this refresh token will be rejected.*

---

### 4.5 Get Current User Profile
- **Route**: `GET /api/auth/me`
- **Access**: Protected
- **Headers**:
  ```http
  Authorization: Bearer <accessToken>
  ```

- **Success Response (HTTP 200 OK)**:
  ```json
  {
    "id": "3ff5fbaa-e14c-47bb-9ef1-18c6d4d3820a",
    "email": "alice@landrop.local",
    "username": "alice",
    "displayName": "Alice Vance",
    "avatarUrl": null,
    "createdAt": "2026-09-13T15:30:00Z",
    "updatedAt": "2026-09-13T15:30:00Z"
  }
  ```

- **Error Responses**:
  - `401 Unauthorized`: Missing `Authorization` header, invalid token format, expired access token, or invalid signature.

---

### 4.6 Service Health Check
- **Route**: `GET /api/auth/health`
- **Access**: Public
- **Success Response (HTTP 200 OK)**:
  ```json
  {
    "status": "UP",
    "phase": "15A",
    "service": "LANDrop-Authentication",
    "timestamp": "2026-09-13T15:58:00Z"
  }
  ```

---

## 5. Standard Error Envelope

All API errors return a standardized JSON body:

```json
{
  "error": "Conflict",
  "message": "Email already registered: alice@landrop.local",
  "status": 409,
  "timestamp": "2026-09-13T15:30:00Z"
}
```

| HTTP Status | Error Name | Trigger Condition |
| :--- | :--- | :--- |
| `400` | `Bad Request` | Malformed JSON, empty payload, password under 8 characters, invalid email format. |
| `401` | `Unauthorized` | Invalid credentials, missing/malformed `Authorization` header, expired or tampered JWT, revoked refresh token. |
| `405` | `Method Not Allowed` | Incorrect HTTP verb used (e.g. GET on `/api/auth/login`). |
| `409` | `Conflict` | User already exists with submitted email or username. |
| `500` | `Internal Server Error` / `Database Error` | Unexpected backend or database exception. |

---

## 6. Frontend Integration Guidelines (Phase 15B)

1. **Token Refresh Interceptor**:
   - Set up refresh handling in the client's HTTP transport.
   - When a request returns `401 Unauthorized`, hold pending requests, dispatch `POST /api/auth/refresh` with the stored `refreshToken`.
   - If refresh succeeds, update the stored `accessToken` and retry the failed request.
   - If refresh returns `401`, purge stored credentials and transition UI to the Login view.

2. **Security & Data Handling**:
   - Do not log or print `accessToken` or `refreshToken` in plain text.
   - Verify that `UserDto` never returns `passwordHash`.
