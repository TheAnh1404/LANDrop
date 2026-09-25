package com.landrop.admin.security;

import com.landrop.auth.exception.ForbiddenException;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.security.SessionVerifier;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.sun.net.httpserver.HttpExchange;

import java.sql.SQLException;
import java.util.Optional;

/**
 * System Admin authorization middleware (FOUNDATION-03A.2).
 * Guarantees that only authenticated users with systemRole == 'SYSTEM_ADMIN'
 * and status == 'ACTIVE' can access Server Control Center endpoints.
 */
public class AdminAuthMiddleware {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository sessionRepository;

    public AdminAuthMiddleware(JwtService jwtService, UserRepository userRepository) {
        this(jwtService, userRepository, null);
    }

    public AdminAuthMiddleware(JwtService jwtService, UserRepository userRepository,
                               RefreshTokenRepository sessionRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
    }

    public AdminAuthMiddleware(JwtService jwtService) {
        this(jwtService, null);
    }

    /**
     * Authenticates and authorizes a system administrator.
     *
     * @param exchange Current HTTP request
     * @return Verified claims of the system administrator
     * @throws UnauthorizedException If token missing, invalid, or expired, or user disabled
     * @throws ForbiddenException If authenticated user lacks SYSTEM_ADMIN role
     */
    public JwtClaims requireSystemAdmin(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            throw new UnauthorizedException("Authorization header is required");
        }

        String trimmed = authHeader.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("Invalid authorization scheme. Expected 'Bearer <token>'");
        }

        String token = trimmed.substring(7).trim();
        if (token.isEmpty()) {
            throw new UnauthorizedException("Bearer token cannot be empty");
        }

        JwtClaims claims = jwtService.verifyToken(token, false);

        if (!claims.isSystemAdmin()) {
            throw new ForbiddenException("Access denied: requires SYSTEM_ADMIN privileges");
        }

        try {
            if (sessionRepository != null && claims.sessionId() != null && !claims.sessionId().isBlank()) {
                SessionVerifier.requireActive(sessionRepository, claims);
            }
            if (userRepository != null) {
                Optional<User> userOpt = userRepository.findById(claims.userId());
                if (userOpt.isEmpty()) {
                    throw new UnauthorizedException("Admin user account not found");
                }
                User user = userOpt.get();
                if (user.isDisabled()) {
                    throw new UnauthorizedException("Admin user account is disabled");
                }
                if (!user.isSystemAdmin()) {
                    throw new ForbiddenException("Access denied: SYSTEM_ADMIN privileges revoked");
                }
            }
        } catch (SQLException unavailable) {
            throw new UnauthorizedException("Administrator session is unavailable");
        }

        return claims;
    }

    public JwtService getJwtService() {
        return jwtService;
    }
}
