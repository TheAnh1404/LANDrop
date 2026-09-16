package com.landrop.auth.server;

import com.landrop.auth.exception.ForbiddenException;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.security.JwtService;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.sun.net.httpserver.HttpExchange;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Authentication middleware for verifying Bearer JWT tokens on protected HTTP endpoints.
 */
public class AuthMiddleware {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public AuthMiddleware(JwtService jwtService) {
        this(jwtService, null);
    }

    public AuthMiddleware(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * Extracts and validates the Bearer token from the incoming exchange.
     *
     * @param exchange Current HTTP exchange
     * @return Verified JWT claims
     * @throws UnauthorizedException If token is missing, invalid, or expired
     */
    public JwtClaims authenticate(HttpExchange exchange) {
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

        if (userRepository != null) {
            try {
                Optional<User> userOpt = userRepository.findById(claims.userId());
                if (userOpt.isPresent() && userOpt.get().isDisabled()) {
                    throw new ForbiddenException("User account is disabled", AuthErrorCode.ACCOUNT_DISABLED);
                }
            } catch (SQLException ignored) {
                // Database connectivity issues should not crash token verification if offline
            }
        }

        return claims;
    }

    public JwtService getJwtService() {
        return jwtService;
    }

    public UserRepository getUserRepository() {
        return userRepository;
    }
}
