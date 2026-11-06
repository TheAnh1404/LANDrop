package com.landrop.database.migration;

import com.landrop.database.DatabaseManager;
import com.landrop.util.AppLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Automates execution and verification of SQL schema migrations against Neon PostgreSQL.
 */
public final class DatabaseMigrator {

    private static final String TAG = "DatabaseMigrator";
    private static final List<String> MIGRATION_RESOURCES = List.of(
        "/db/migration/V1__init_cloud_database_phase14.sql",
        "/db/migration/V2__auth_sessions_phase15.sql",
        "/db/migration/V3__realtime_message_identity.sql",
        "/db/migration/V4__persistent_chat_phase17.sql",
        "/db/migration/V5__file_management_phase18.sql",
        "/db/migration/V6__server_control_center_phase03.sql",
        "/db/migration/V7__authentication_device_enrollment_phase04.sql",
        "/db/migration/V8__workspace_join_password.sql"
    );

    public static List<String> getMigrationResources() {
        return MIGRATION_RESOURCES;
    }

    @FunctionalInterface
    public interface ConnectionProvider {
        Connection getConnection() throws SQLException;
    }

    private final ConnectionProvider connectionProvider;

    public DatabaseMigrator(DatabaseManager databaseManager) {
        this(Objects.requireNonNull(databaseManager, "databaseManager cannot be null")::getConnection);
    }

    public DatabaseMigrator(ConnectionProvider connectionProvider) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider cannot be null");
    }

    public DatabaseMigrator() {
        this(DatabaseManager.getInstance());
    }

    /** Applies pending migrations atomically, serializing concurrent server startups. */
    public void migrate() throws SQLException, IOException {
        AppLogger.info(TAG, "Checking PostgreSQL schema migrations...");
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_advisory_xact_lock(hashtext(current_database()), hashtext(current_schema() || ':landrop:migrations'))");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS landrop_schema_history (
                        version INTEGER PRIMARY KEY,
                        resource TEXT NOT NULL,
                        checksum VARCHAR(64) NOT NULL,
                        installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
                for (int index = 0; index < MIGRATION_RESOURCES.size(); index++) {
                    String resource = MIGRATION_RESOURCES.get(index);
                    String migrationSql = loadMigrationScript(resource);
                    String checksum = checksum(migrationSql);
                    try (PreparedStatement existing = conn.prepareStatement("SELECT checksum FROM landrop_schema_history WHERE version = ?")) {
                        existing.setInt(1, index + 1);
                        try (ResultSet rs = existing.executeQuery()) {
                            if (rs.next()) {
                                if (!checksum.equals(rs.getString(1))) {
                                    throw new SQLException("Applied migration has changed: " + resource + ". Add a new migration instead.");
                                }
                                continue;
                            }
                        }
                    }
                    // V1 predates schema isolation; scope its enum probe without rewriting an applied resource.
                    String executableSql = migrationSql.replace("WHERE typname = 'WorkspaceRole'",
                        "WHERE typname = 'WorkspaceRole' AND typnamespace = current_schema()::regnamespace");
                    List<String> statements = parseSqlStatements(executableSql);
                    for (String sql : statements) {
                        if (!sql.isBlank()) stmt.execute(sql);
                    }
                    try (PreparedStatement record = conn.prepareStatement("INSERT INTO landrop_schema_history (version, resource, checksum) VALUES (?, ?, ?)")) {
                        record.setInt(1, index + 1);
                        record.setString(2, resource);
                        record.setString(3, checksum);
                        record.executeUpdate();
                    }
                    AppLogger.info(TAG, "Applied migration " + resource + " (" + statements.size() + " DDL commands).");
                }
                conn.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                try { conn.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
        verifyTablesCreated();
    }

    private static String checksum(String script) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(script.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Verifies every column used by persistent APIs in the active schema. */
    public List<String> verifyTablesCreated() throws SQLException {
        Map<String, List<String>> required = Map.ofEntries(
            Map.entry("users", List.of("id", "email", "username", "display_name", "avatar_url", "password_hash", "status", "system_role", "created_at", "updated_at")),
            Map.entry("workspaces", List.of("id", "name", "slug", "description", "owner_id", "join_policy", "join_password_hash", "created_at", "updated_at")),
            Map.entry("workspace_members", List.of("id", "workspace_id", "user_id", "role", "joined_at", "updated_at")),
            Map.entry("channels", List.of("id", "workspace_id", "name", "topic", "is_private", "created_at", "updated_at")),
            Map.entry("messages", List.of("id", "channel_id", "user_id", "parent_message_id", "client_message_id", "content", "created_at", "updated_at", "edited_at", "deleted_at")),
            Map.entry("folders", List.of("id", "workspace_id", "name", "parent_folder_id", "created_by_id", "created_at", "updated_at", "deleted_at")),
            Map.entry("files", List.of("id", "workspace_id", "user_id", "folder_id", "name", "original_name", "size", "mime_type", "sha256", "storage_type", "storage_path", "storage_key", "created_at", "updated_at", "deleted_at")),
            Map.entry("refresh_tokens", List.of("id", "user_id", "token_hash", "device_id", "expires_at", "last_seen_at", "revoked_at", "revoked", "created_at", "updated_at")),
            Map.entry("devices", List.of("id", "user_id", "client_instance_id", "device_name", "platform", "client_version", "status", "peer_id", "ip_address", "port", "last_seen_at", "created_at", "updated_at")),
            Map.entry("transfers", List.of("id", "file_id", "sender_user_id", "receiver_user_id", "sender_device_id", "receiver_device_id", "status", "created_at", "started_at", "completed_at", "failed_at", "error_message"))
        );
        Map<String, Set<String>> actual = new HashMap<>();
        try (Connection conn = connectionProvider.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = current_schema()")) {
            while (rs.next()) actual.computeIfAbsent(rs.getString(1), ignored -> new HashSet<>()).add(rs.getString(2));
        }
        List<String> foundTables = new ArrayList<>();
        for (var entry : required.entrySet()) {
            Set<String> columns = actual.get(entry.getKey());
            if (columns == null) throw new SQLException("Required table missing in active schema: " + entry.getKey());
            for (String column : entry.getValue()) {
                if (!columns.contains(column)) throw new SQLException("Required column missing: " + entry.getKey() + "." + column);
            }
            foundTables.add(entry.getKey());
        }
        AppLogger.info(TAG, "Verified all " + foundTables.size() + " persistent API tables and columns.");
        return foundTables;
    }

    private String loadMigrationScript(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Migration resource not found on classpath: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        }
    }

    private List<String> parseSqlStatements(String sqlScript) {
        List<String> statements = new ArrayList<>();
        // Split on semicolon, taking care of DO $$ ... $$; block
        StringBuilder current = new StringBuilder();
        boolean insideDollarQuote = false;

        String[] lines = sqlScript.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") && !insideDollarQuote) {
                continue;
            }

            if (trimmed.contains("$$")) {
                int count = 0;
                int idx = 0;
                while ((idx = trimmed.indexOf("$$", idx)) != -1) {
                    count++;
                    idx += 2;
                }
                if (count % 2 != 0) {
                    insideDollarQuote = !insideDollarQuote;
                }
            }

            current.append(line).append("\n");

            if (!insideDollarQuote && trimmed.endsWith(";")) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
            }
        }

        if (!current.toString().trim().isEmpty()) {
            statements.add(current.toString().trim());
        }

        return statements;
    }
}
