package com.landrop.realtime;

import com.landrop.auth.security.JwtService;
import com.landrop.database.repository.UserRepository;

@FunctionalInterface
public interface SocketAuthenticator {
    SocketPrincipal authenticate(String authorization) throws Exception;

    static SocketAuthenticator jwt(JwtService jwt, UserRepository users) {
        return header -> {
            if (header == null || !header.startsWith("Bearer ") || header.length() > 8192)
                throw new RealtimeException("UNAUTHENTICATED", "Sign in is required.");
            var claims = jwt.verifyToken(header.substring(7), false);
            if (claims.tokenId() == null || users.findById(claims.userId()).isEmpty())
                throw new RealtimeException("UNAUTHENTICATED", "Sign in is required.");
            return new SocketPrincipal(claims.userId(), claims.tokenId(), claims.expiresAt());
        };
    }
}
