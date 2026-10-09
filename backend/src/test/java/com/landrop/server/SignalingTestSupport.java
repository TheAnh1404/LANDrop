package com.landrop.server;

import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.database.model.*;
import com.landrop.database.repository.*;
import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.realtime.FoundationRealtimeServer;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Real WS authentication with controlled repositories; production WorkspaceAccess still runs. */
public final class SignalingTestSupport {
    public final Workspace workspace = new Workspace("Signaling", "signaling", null, UUID.randomUUID());
    public final Map<UUID, User> users = new ConcurrentHashMap<>();
    public final Map<UUID, RefreshToken> sessions = new ConcurrentHashMap<>();
    public final Map<UUID, WorkspaceRole> members = new ConcurrentHashMap<>();
    public final JwtService jwt = new JwtService("signaling-access-test", "signaling-refresh-test", 600, 3600);

    public SignalingTestSupport(ServerBootstrap bootstrap) { this(bootstrap.getRealtimeServer()); }

    public SignalingTestSupport(FoundationRealtimeServer gateway) {
        gateway.setAuthDependencies(jwt, new UserRepository(null) {
            @Override public Optional<User> findById(UUID id) { return Optional.ofNullable(users.get(id)); }
        }, new DeviceRepository(null), new RefreshTokenRepository(null) {
            @Override public Optional<RefreshToken> findById(UUID id) { return Optional.ofNullable(sessions.get(id)); }
            @Override public boolean updateLastSeen(UUID id, Instant seenAt) { return sessions.containsKey(id); }
        });
        gateway.setSignalingWorkspaceRepository(new WorkspaceRepository(null) {
            @Override public Optional<Workspace> findById(UUID id) {
                return workspace.id().equals(id) ? Optional.of(workspace) : Optional.empty();
            }
            @Override public Optional<WorkspaceMember> findMember(UUID workspaceId, UUID userId) {
                WorkspaceRole role = members.get(userId);
                return role == null ? Optional.empty() : Optional.of(new WorkspaceMember(workspaceId, userId, role));
            }
        });
    }

    public record Identity(User user, UUID sessionId) { }

    public Identity authenticate(FoundationWsClient client) throws Exception {
        String name = "peer-" + UUID.randomUUID();
        User user = new User(name + "@example.test", name, name, null);
        return authenticate(client, user);
    }

    public Identity authenticate(FoundationWsClient client, User user) throws Exception {
        users.put(user.id(), user);
        members.put(user.id(), WorkspaceRole.MEMBER);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        sessions.put(id, new RefreshToken(id, user.id(), id.toString(), null, now.plusSeconds(3600), now, null, false, now, now));
        client.sendJson(Map.of("type", "auth", "requestId", "auth-" + id, "payload", Map.of(
                "accessToken", jwt.generateAccessToken(user, id.toString()), "userId", UUID.randomUUID().toString())));
        var ack = client.receive(3000);
        assertNotNull(ack);
        assertEquals("auth_ack", ack.path("type").asText());
        assertEquals(user.id().toString(), ack.path("payload").path("userId").asText());
        return new Identity(user, id);
    }
}
