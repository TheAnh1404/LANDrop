package com.landrop.auth.service;

import com.landrop.auth.dto.*;
import com.landrop.auth.exception.ConflictException;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.security.PasswordHasher;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.landrop.util.AppLogger;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

        String email = request.email().trim().toLowerCase();
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
        User savedUser = userRepository.create(newUser);
        AppLogger.info(TAG, "Registered new user: " + savedUser.username() + " (" + savedUser.id() + ")");

        // 4. Issue access and refresh tokens
        return issueAuthTokens(savedUser);
    }

    public AuthResponse login(LoginRequest request) throws SQLException {
        if (request == null) {
            throw new ValidationException("Login payload cannot be empty");
        }
        request.validate();

        String identifier = request.resolvedIdentifier();

        // 1. Lookup user by email or username
        Optional<User> userOpt = userRepository.findByEmail(identifier.toLowerCase());
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByUsername(identifier);
        }

        if (userOpt.isEmpty()) {
            AppLogger.warn(TAG, "Failed login attempt: unknown user '" + identifier + "'");
            throw new UnauthorizedException("Invalid email/username or password");
        }

        User user = userOpt.get();

        // 2. Verify password
        if (user.passwordHash() == null || !PasswordHasher.verify(request.password(), user.passwordHash())) {
            AppLogger.warn(TAG, "Failed login attempt: invalid credentials for user '" + identifier + "'");
            throw new UnauthorizedException("Invalid email/username or password");
        }

        AppLogger.info(TAG, "User logged in successfully: " + user.username() + " (" + user.id() + ")");

        // 3. Issue tokens
        return issueAuthTokens(user);
    }

    public AuthResponse refresh(RefreshRequest request) throws SQLException {
        if (request == null) {
            throw new ValidationException("Refresh payload cannot be empty");
        }
        request.validate();

        return refresh(request.refreshToken());
    }

    public AuthResponse refresh(String rawRefreshToken) throws SQLException {
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
            throw new UnauthorizedException("Invalid refresh token");
        }

        RefreshToken storedToken = storedOpt.get();

        // 3. Check revocation and expiration
        if (storedToken.revoked()) {
            AppLogger.warn(TAG, "Refresh rejected: token has been revoked for user " + storedToken.userId());
            throw new UnauthorizedException("Refresh token has been revoked");
        }
        if (storedToken.isExpired()) {
            AppLogger.warn(TAG, "Refresh rejected: database session expired for user " + storedToken.userId());
            throw new UnauthorizedException("Refresh token has expired");
        }

        // 4. Retrieve user
        User user = userRepository.findById(storedToken.userId())
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        // 5. Token rotation: revoke previous refresh token to prevent reuse attacks
        if (!refreshTokenRepository.revokeByTokenHash(tokenHash)) {
            throw new UnauthorizedException("Refresh token has already been used");
        }

        // 6. Issue fresh token pair
        return issueAuthTokens(user);
    }

    public boolean logout(String rawRefreshToken) throws SQLException {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return false;
        }

        String tokenHash = JwtService.hashToken(rawRefreshToken);
        boolean revoked = refreshTokenRepository.revokeByTokenHash(tokenHash);
        if (revoked) {
            AppLogger.info(TAG, "Refresh token session revoked successfully on logout");
        }
        return revoked;
    }

    public int logoutAll(UUID userId) throws SQLException {
        int count = refreshTokenRepository.revokeAllByUserId(userId);
        AppLogger.info(TAG, "Revoked all (" + count + ") refresh sessions for user: " + userId);
        return count;
    }

    public UserDto getCurrentUser(UUID userId) throws SQLException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        return UserDto.from(user);
    }

    private AuthResponse issueAuthTokens(User user) throws SQLException {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        // Store hashed refresh token in database
        String tokenHash = JwtService.hashToken(refreshToken);
        Instant expiresAt = Instant.now().plusSeconds(jwtService.getRefreshExpirySeconds());
        RefreshToken session = new RefreshToken(user.id(), tokenHash, expiresAt);
        refreshTokenRepository.create(session);

        return new AuthResponse(
            accessToken,
            refreshToken,
            jwtService.getAccessExpirySeconds(),
            UserDto.from(user)
        );
    }
}
