package com.landrop.auth.server;

import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.security.JwtService;
import com.sun.net.httpserver.HttpExchange;

/**
 * Authentication middleware for verifying Bearer JWT tokens on protected HTTP endpoints.
 */
public class AuthMiddleware {

    private final JwtService jwtService;

    public AuthMiddleware(JwtService jwtService) {
        this.jwtService = jwtService;
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

        return jwtService.verifyToken(token, false);
    }
}
