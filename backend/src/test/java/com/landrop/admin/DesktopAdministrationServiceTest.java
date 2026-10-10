package com.landrop.admin;

import com.landrop.admin.logging.OperationalLogService;
import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.database.DatabaseManager;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.landrop.server.clients.ClientRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopAdministrationServiceTest {
    static final class Users extends UserRepository {
        final Map<UUID, User> records = new HashMap<>();
        Users() { super((DatabaseManager) null); }
        @Override public Optional<User> findById(UUID id) { return Optional.ofNullable(records.get(id)); }
        @Override public long countSystemAdmins() { return records.values().stream().filter(User::isSystemAdmin).count(); }
        @Override public long countActiveSystemAdmins() {
            return records.values().stream().filter(User::isSystemAdmin).filter(user -> User.STATUS_ACTIVE.equals(user.status())).count();
        }
        @Override public boolean updateStatus(UUID id, String status) { records.put(id, records.get(id).withStatus(status)); return true; }
        @Override public boolean deleteById(UUID id) { return records.remove(id) != null; }
        @Override public boolean updateSystemRole(UUID id, String role) { records.put(id, records.get(id).withSystemRole(role)); return true; }
    }
    static final class Sessions extends RefreshTokenRepository {
        int revocations;
        Sessions() { super((DatabaseManager) null); }
        @Override public int revokeAllByUserId(UUID id) { revocations++; return 2; }
    }
    static final class Clients extends ClientRegistry {
        UUID disconnected;
        @Override public int disconnectUser(UUID id, String code, String message) { disconnected = id; return 1; }
    }

    @Test void localOperatorCannotDisableDeleteOrDemoteFinalActiveAdmin() throws Exception {
        var users = new Users(); var sessions = new Sessions(); var clients = new Clients();
        User active = new User("admin@test.local", "admin", "Admin", null).withSystemRole(User.ROLE_SYSTEM_ADMIN);
        User disabled = new User("disabled@test.local", "disabled", "Disabled", null).withSystemRole(User.ROLE_SYSTEM_ADMIN).withStatus(User.STATUS_DISABLED);
        users.records.put(active.id(), active); users.records.put(disabled.id(), disabled);
        var service = new AdminControlCenterService(users, sessions, clients, null, null, new OperationalLogService());
        assertThrows(ValidationException.class, () -> service.disableUser(active.id(), null, "DESKTOP_OPERATOR"));
        assertThrows(ValidationException.class, () -> service.deleteUser(active.id(), null, "DESKTOP_OPERATOR"));
        assertThrows(ValidationException.class, () -> service.updateUser(active.id(), null, Map.of("systemRole", User.ROLE_USER), "DESKTOP_OPERATOR"));
        assertThrows(ValidationException.class, () -> service.updateUser(active.id(), null,
                Map.of("displayName", "Must not be saved", "systemRole", User.ROLE_USER), "DESKTOP_OPERATOR"));
        assertEquals("Admin", users.records.get(active.id()).displayName());
        assertEquals(User.STATUS_ACTIVE, users.records.get(active.id()).status());
        assertEquals(User.ROLE_SYSTEM_ADMIN, users.records.get(active.id()).systemRole());
        assertEquals(0, sessions.revocations); assertNull(clients.disconnected);
    }

    @Test void disablingFromDesktopRevokesSessionsDisconnectsUserAndAuditsActor() throws Exception {
        var users = new Users(); var sessions = new Sessions(); var clients = new Clients(); var logs = new OperationalLogService();
        User user = new User("member@test.local", "member", "Member", null); users.records.put(user.id(), user);
        var service = new AdminControlCenterService(users, sessions, clients, null, null, logs);
        assertTrue(service.disableUser(user.id(), null, "DESKTOP_OPERATOR"));
        assertEquals(User.STATUS_DISABLED, users.records.get(user.id()).status());
        assertEquals(1, sessions.revocations); assertEquals(user.id(), clients.disconnected);
        var entry = logs.getLogs(1, 0, null, null, null).getFirst();
        assertEquals("ACCOUNT_DISABLED", entry.eventType()); assertEquals("DESKTOP_OPERATOR", entry.actor());
        assertTrue(service.enableUser(user.id(), "DESKTOP_OPERATOR"));
        assertEquals(User.STATUS_ACTIVE, users.records.get(user.id()).status());
    }
}
