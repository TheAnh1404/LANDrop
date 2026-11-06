package com.landrop.database.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigratorTest {

    @Test
    @DisplayName("Verification: V1 to V8 migration scripts are registered and loadable")
    void testAllMigrationsRegisteredAndLoadable() throws Exception {
        List<String> resources = DatabaseMigrator.getMigrationResources();
        assertNotNull(resources);
        assertEquals(8, resources.size(), "Should have exactly 8 registered migration scripts (V1 through V8).");

        for (int i = 0; i < resources.size(); i++) {
            String path = resources.get(i);
            int version = i + 1;
            assertTrue(path.contains("V" + version + "__"), "Resource index " + i + " must correspond to V" + version);

            try (InputStream is = getClass().getResourceAsStream(path)) {
                assertNotNull(is, "Migration resource must exist on classpath: " + path);
                byte[] bytes = is.readAllBytes();
                assertTrue(bytes.length > 0, "Migration resource cannot be empty: " + path);
                String sql = new String(bytes);
                if (version == 8) {
                    assertTrue(sql.contains("join_policy"), "V8 must add join_policy");
                    assertTrue(sql.contains("join_password_hash"), "V8 must add join_password_hash");
                }
            }
        }
    }

    @Test
    @DisplayName("Verification: verifyTablesCreated requires join_policy and join_password_hash on workspaces")
    void testVerifyTablesRequiresJoinSettings() throws Exception {
        // Build mock schema missing join_policy
        Map<String, Set<String>> incompleteSchema = createValidSchemaMap();
        incompleteSchema.get("workspaces").remove("join_policy");

        Connection mockConnMissingJoinPolicy = createMockConnection(incompleteSchema);
        DatabaseMigrator migratorMissing = new DatabaseMigrator(() -> mockConnMissingJoinPolicy);

        SQLException ex1 = assertThrows(SQLException.class, migratorMissing::verifyTablesCreated);
        assertTrue(ex1.getMessage().contains("workspaces.join_policy"), "Should complain about missing join_policy: " + ex1.getMessage());

        // Build mock schema missing join_password_hash
        Map<String, Set<String>> incompleteSchema2 = createValidSchemaMap();
        incompleteSchema2.get("workspaces").remove("join_password_hash");

        Connection mockConnMissingHash = createMockConnection(incompleteSchema2);
        DatabaseMigrator migratorMissingHash = new DatabaseMigrator(() -> mockConnMissingHash);

        SQLException ex2 = assertThrows(SQLException.class, migratorMissingHash::verifyTablesCreated);
        assertTrue(ex2.getMessage().contains("workspaces.join_password_hash"), "Should complain about missing join_password_hash: " + ex2.getMessage());

        // Build valid complete schema
        Map<String, Set<String>> completeSchema = createValidSchemaMap();
        Connection mockConnComplete = createMockConnection(completeSchema);
        DatabaseMigrator migratorComplete = new DatabaseMigrator(() -> mockConnComplete);

        List<String> verified = migratorComplete.verifyTablesCreated();
        assertEquals(10, verified.size());
        assertTrue(verified.contains("workspaces"));
    }

    private Map<String, Set<String>> createValidSchemaMap() {
        Map<String, Set<String>> schema = new HashMap<>();
        schema.put("users", new HashSet<>(List.of("id", "email", "username", "display_name", "avatar_url", "password_hash", "status", "system_role", "created_at", "updated_at")));
        schema.put("workspaces", new HashSet<>(List.of("id", "name", "slug", "description", "owner_id", "join_policy", "join_password_hash", "created_at", "updated_at")));
        schema.put("workspace_members", new HashSet<>(List.of("id", "workspace_id", "user_id", "role", "joined_at", "updated_at")));
        schema.put("channels", new HashSet<>(List.of("id", "workspace_id", "name", "topic", "is_private", "created_at", "updated_at")));
        schema.put("messages", new HashSet<>(List.of("id", "channel_id", "user_id", "parent_message_id", "client_message_id", "content", "created_at", "updated_at", "edited_at", "deleted_at")));
        schema.put("folders", new HashSet<>(List.of("id", "workspace_id", "name", "parent_folder_id", "created_by_id", "created_at", "updated_at", "deleted_at")));
        schema.put("files", new HashSet<>(List.of("id", "workspace_id", "user_id", "folder_id", "name", "original_name", "size", "mime_type", "sha256", "storage_type", "storage_path", "storage_key", "created_at", "updated_at", "deleted_at")));
        schema.put("refresh_tokens", new HashSet<>(List.of("id", "user_id", "token_hash", "device_id", "expires_at", "last_seen_at", "revoked_at", "revoked", "created_at", "updated_at")));
        schema.put("devices", new HashSet<>(List.of("id", "user_id", "client_instance_id", "device_name", "platform", "client_version", "status", "peer_id", "ip_address", "port", "last_seen_at", "created_at", "updated_at")));
        schema.put("transfers", new HashSet<>(List.of("id", "file_id", "sender_user_id", "receiver_user_id", "sender_device_id", "receiver_device_id", "status", "created_at", "started_at", "completed_at", "failed_at", "error_message")));
        return schema;
    }

    private Connection createMockConnection(Map<String, Set<String>> schema) {
        List<Map.Entry<String, String>> rows = new ArrayList<>();
        for (var entry : schema.entrySet()) {
            for (String col : entry.getValue()) {
                rows.add(Map.entry(entry.getKey(), col));
            }
        }

        ResultSet rs = (ResultSet) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{ResultSet.class},
            new InvocationHandler() {
                private int cursor = -1;
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        cursor++;
                        return cursor < rows.size();
                    } else if ("getString".equals(name)) {
                        int index = (int) args[0];
                        if (index == 1) return rows.get(cursor).getKey();
                        if (index == 2) return rows.get(cursor).getValue();
                    } else if ("close".equals(name)) {
                        return null;
                    }
                    return null;
                }
            }
        );

        Statement stmt = (Statement) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Statement.class},
            (proxy, method, args) -> {
                if ("executeQuery".equals(method.getName())) {
                    return rs;
                } else if ("close".equals(method.getName())) {
                    return null;
                }
                return null;
            }
        );

        return (Connection) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if ("createStatement".equals(method.getName())) {
                    return stmt;
                } else if ("close".equals(method.getName())) {
                    return null;
                }
                return null;
            }
        );
    }
}
