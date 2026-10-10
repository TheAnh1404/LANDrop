package com.landrop.auth.service;

import com.landrop.auth.dto.*;
import com.landrop.auth.exception.ConflictException;
import com.landrop.auth.exception.ForbiddenException;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.security.PasswordHasher;
import com.landrop.auth.security.SessionVerifier;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.landrop.util.AppLogger;

import java.sql.SQLException;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;

/**
 * High-level orchestration service for user authentication, registration,
 * credential verification, and token session management.
 */
public class AuthService {

    private static final String TAG = "AuthService";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    public AuthService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) throws SQLException {
        if (request == null) {
            throw new ValidationException("Registration payload cannot be empty");
        }
        request.validate();

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String username = request.username().trim();

        // 1. Uniqueness check
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("Email already registered: " + email);
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("Username already taken: " + username);
        }

        // 2. Hash password with OWASP PBKDF2
        String passwordHash = PasswordHasher.hash(request.password());

        // 3. Persist user
        User newUser = new User(
            UUID.randomUUID(),
            email,
            username,
            request.resolvedDisplayName(),
            null,
            passwordHash,
            Instant.now(),
            Instant.now()
        );
        try {
            AuthResponse response = userRepository.inTransaction(conn -> {
                User savedUser = userRepository.create(conn, newUser);
                return issueAuthTokens(conn, savedUser, null);
            });
            AppLogger.info(TAG, "Registered new user: " + newUser.username() + " (" + newUser.id() + ")");
            return response;
        } catch (SQLException e) {
            // Concurrent registrations are resolved by the database's unique constraints.
            if ("23505".equals(e.getSQLState())) {
                throw new ConflictException("Email or username already registered");
            }
            throw e;
        }
    }

    public AuthResponse login(LoginRequest request) throws SQLException {
        return login(request, false);
    }

    /** Authenticates a regular LANDrop Client user. System administrators use the Control Center endpoint. */
    public AuthResponse loginClient(LoginRequest request) throws SQLException {
        return login(request, false);
    }

    /** Authenticates a SYSTEM_ADMIN for the Server Control Center only. */
    public AuthResponse loginAdmin(LoginRequest request) throws SQLException {
        return login(request, true);
    }

    private AuthResponse login(LoginRequest request, boolean adminLogin) throws SQLException {
        if (request == null) {
            throw new ValidationException("Login payload cannot be empty");
        }
        request.validate();

        String identifier = request.resolvedIdentifier();

        // 1. Lookup user by email or username
        Optional<User> userOpt = userRepository.findByEmail(identifier.toLowerCase(Locale.ROOT));
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByUsername(identifier);
        }

        if (userOpt.isEmpty()) {
            AppLogger.warn(TAG, "Failed login attempt: unknown user '" + identifier + "'");
            throw new UnauthorizedException("Invalid email/username or password", AuthErrorCode.INVALID_CREDENTIALS);
        }

        User user = userOpt.get();

        // 2. Verify account is not disabled
        if (user.isDisabled()) {
            AppLogger.warn(TAG, "Failed login attempt: account is disabled for user '" + identifier + "'");
            throw new ForbiddenException("User account is disabled", AuthErrorCode.ACCOUNT_DISABLED);
        }

        // 3. Verify password
        if (user.passwordHash() == null || !PasswordHasher.verify(request.password(), user.passwordHash())) {
            AppLogger.warn(TAG, "Failed login attempt: invalid credentials for user '" + identifier + "'");
            throw new UnauthorizedException("Invalid email/username or password", AuthErrorCode.INVALID_CREDENTIALS);
        }

        if (adminLogin != user.isSystemAdmin()) {
            if (adminLogin) {
                throw new ForbiddenException("Server Control Center access requires a system administrator account", AuthErrorCode.ADMIN_REQUIRED);
            }
            throw new ForbiddenException("System administrators must sign in through the Server Control Center", AuthErrorCode.ADMIN_LOGIN_REQUIRED);
        }

        // 4. Issue tokens
        // Device identity is established only by authenticated enrollment.
        // A login payload cannot pre-bind a session to a client-claimed device.
        AuthResponse response = issueAuthTokens(user);
        AppLogger.info(TAG, "User logged in successfully: " + user.username() + " (" + user.id() + ")");
        OperationalLogService.getInstance().info("USER_LOGIN", user.id().toString(), user.id().toString(),
                adminLogin ? "Administrator signed in." : "Client user signed in.");
        return response;
    }

    public AuthResponse refresh(RefreshRequest request) throws SQLException {
        if (request == null) {
            throw new ValidationException("Refresh payload cannot be empty");
        }
        request.validate();

        return refreshClient(request.refreshToken());
    }

    public AuthResponse refresh(String rawRefreshToken) throws SQLException {
        return refreshClient(rawRefreshToken);
    }

    /** Refreshes a regular Client session. Admin sessions are kept on the Control Center flow. */
    public AuthResponse refreshClient(String rawRefreshToken) throws SQLException {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ValidationException("refreshToken is required");
        }

        // 1. Verify cryptographic signature and expiration of JWT
        JwtClaims claims = jwtService.verifyToken(rawRefreshToken, true);

        // 2. Look up token in DB by hash
        String tokenHash = JwtService.hashToken(rawRefreshToken);
        Optional<RefreshToken> storedOpt = refreshTokenRepository.findByTokenHash(tokenHash);

        if (storedOpt.isEmpty()) {
            AppLogger.warn(TAG, "Refresh rejected: token not found in database session registry");
            throw new UnauthorizedException("Invalid refresh token", AuthErrorCode.UNAUTHORIZED);
        }

        RefreshToken storedToken = storedOpt.get();
        if (!storedToken.userId().equals(claims.userId()) || !storedToken.id().toString().equals(claims.sessionId())) {
            throw new UnauthorizedException("Refresh credential does not match its session", AuthErrorCode.UNAUTHORIZED);
        }

        // 3. Check revocation and expiration
        if (storedToken.revoked()) {
            AppLogger.warn(TAG, "Refresh rejected: token has been revoked for user " + storedToken.userId());
            throw new UnauthorizedException("Refresh token has been revoked", AuthErrorCode.SESSION_REVOKED);
        }
        if (storedToken.isExpired()) {
            AppLogger.warn(TAG, "Refresh rejected: database session expired for user " + storedToken.userId());
            throw new UnauthorizedException("Refresh token has expired", AuthErrorCode.SESSION_EXPIRED);
        }

        // 4. Retrieve user
        User user = userRepository.findById(storedToken.userId())
                .orElseThrow(() -> new UnauthorizedException("User not found", AuthErrorCode.UNAUTHORIZED));

        if (user.isSystemAdmin()) {
            throw new ForbiddenException("System administrators must sign in through the Server Control Center", AuthErrorCode.ADMIN_LOGIN_REQUIRED);
        }

        if (user.isDisabled()) {
            AppLogger.warn(TAG, "Refresh rejected: account is disabled for user " + user.id());
            throw new ForbiddenException("User account is disabled", AuthErrorCode.ACCOUNT_DISABLED);
        }

        // 5. Token rotation: revoke previous refresh token to prevent reuse attacks
        return userRepository.inTransaction(conn -> {
            if (!refreshTokenRepository.revokeByTokenHash(conn, tokenHash)) {
                throw new UnauthorizedException("Refresh token has already been used", AuthErrorCode.SESSION_REVOKED);
            }
            // Failure to persist the replacement must leave the original session usable.
            return issueAuthTokens(conn, user, storedToken.deviceId());
        });
    }

    public boolean logout(String rawRefreshToken) throws SQLException {
        return logoutSession(rawRefreshToken).isPresent();
    }

    /** Revokes only the session represented by this signed refresh credential. */
    public Optional<UUID> logoutSession(String rawRefreshToken) throws SQLException {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }

        JwtClaims claims = jwtService.verifyToken(rawRefreshToken, true);
        String tokenHash = JwtService.hashToken(rawRefreshToken);
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(tokenHash);
        if (found.isEmpty()) {
            throw new UnauthorizedException("Refresh session not found", AuthErrorCode.SESSION_REVOKED);
        }
        RefreshToken session = found.get();
        if (!session.userId().equals(claims.userId()) || !session.id().toString().equals(claims.sessionId())) {
            throw new UnauthorizedException("Refresh credential does not match its session", AuthErrorCode.UNAUTHORIZED);
        }
        boolean revoked = refreshTokenRepository.revokeById(session.id());
        if (revoked) {
            AppLogger.info(TAG, "Refresh token session revoked successfully on logout");
            OperationalLogService.getInstance().info("USER_LOGOUT", session.userId().toString(), session.id().toString(),
                    "Current session signed out.");
            return Optional.of(session.id());
        }
        return Optional.empty();
    }

    /** Access-token logout for clients that have no refresh credential available. */
    public Optional<UUID> logoutCurrentSession(JwtClaims claims) throws SQLException {
        RefreshToken session = SessionVerifier.requireActive(refreshTokenRepository, claims);
        if (!refreshTokenRepository.revokeById(session.id())) return Optional.empty();
        OperationalLogService.getInstance().info("USER_LOGOUT", session.userId().toString(), session.id().toString(),
                "Current session signed out.");
        return Optional.of(session.id());
    }

    public int logoutAll(UUID userId) throws SQLException {
        int count = refreshTokenRepository.revokeAllByUserId(userId);
        AppLogger.info(TAG, "Revoked all (" + count + ") refresh sessions for user: " + userId);
        return count;
    }

    public UserDto getCurrentUser(UUID userId) throws SQLException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found", AuthErrorCode.UNAUTHORIZED));
        return UserDto.from(user);
    }

    /** Resolves identity for the Client API and rejects SYSTEM_ADMIN accounts. */
    public UserDto getCurrentClientUser(UUID userId) throws SQLException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found", AuthErrorCode.UNAUTHORIZED));
        if (user.isSystemAdmin()) {
            throw new ForbiddenException("System administrators must sign in through the Server Control Center", AuthErrorCode.ADMIN_LOGIN_REQUIRED);
        }
        return UserDto.from(user);
    }

    public AuthResponse issueAuthTokens(User user) throws SQLException {
        return issueAuthTokens(user, null);
    }

    public AuthResponse issueAuthTokens(User user, String deviceId) throws SQLException {
        return issueAuthTokens(null, user, deviceId);
    }

    private AuthResponse issueAuthTokens(Connection conn, User user, String deviceId) throws SQLException {
        UUID sessionId = UUID.randomUUID();
        String accessToken = jwtService.generateAccessToken(user, sessionId.toString());
        String refreshToken = jwtService.generateRefreshToken(user, sessionId.toString());

        // Store hashed refresh token in database
        String tokenHash = JwtService.hashToken(refreshToken);
        Instant expiresAt = Instant.now().plusSeconds(jwtService.getRefreshExpirySeconds());
        Instant now = Instant.now();
        RefreshToken session = new RefreshToken(
            sessionId, user.id(), tokenHash, deviceId, expiresAt, now, null, false, now, now
        );
        refreshTokenRepository.create(conn, session);
        OperationalLogService.getInstance().info("SESSION_CREATED", user.id().toString(), session.id().toString(),
                "Authentication session created.");

        return new AuthResponse(
            accessToken,
            refreshToken,
            jwtService.getAccessExpirySeconds(),
            UserDto.from(user)
        );
    }
}
