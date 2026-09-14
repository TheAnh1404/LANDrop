package com.landrop.database.migration;

import com.landrop.database.DatabaseManager;
import com.landrop.util.AppLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Automates execution and verification of SQL schema migrations against Neon PostgreSQL.
 */
public final class DatabaseMigrator {

    private static final String TAG = "DatabaseMigrator";
    private static final List<String> MIGRATION_RESOURCES = List.of(
        "/db/migration/V1__init_cloud_database_phase14.sql",
        "/db/migration/V2__auth_sessions_phase15.sql",
        "/db/migration/V3__realtime_message_identity.sql"
    );

    private final DatabaseManager databaseManager;

    public DatabaseMigrator(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public DatabaseMigrator() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Executes database migrations V1–V3 against the connected database.
     */
    public void migrate() throws SQLException, IOException {
        AppLogger.info(TAG, "Starting cloud database schema migration (V1–V3)...");

        for (String resource : MIGRATION_RESOURCES) {
            String migrationSql = loadMigrationScript(resource);
            List<String> statements = parseSqlStatements(migrationSql);

            try (Connection conn = databaseManager.getConnection()) {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : statements) {
                        if (!sql.isBlank()) {
                            stmt.execute(sql);
                        }
                    }
                    conn.commit();
                    AppLogger.info(TAG, "Applied migration " + resource + " (" + statements.size() + " DDL commands).");
                } catch (SQLException e) {
                    conn.rollback();
                    AppLogger.error(TAG, "Migration " + resource + " failed, transaction rolled back", e);
                    throw e;
                }
            }
        }

        verifyTablesCreated();
    }

    /**
     * Verifies that all required Phase 14 & 15 tables exist in PostgreSQL.
     */
    public List<String> verifyTablesCreated() throws SQLException {
        String[] requiredTables = {
            "users",
            "workspaces",
            "workspace_members",
            "channels",
            "messages",
            "folders",
            "files",
            "refresh_tokens"
        };

        List<String> foundTables = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String table : requiredTables) {
                String checkSql = "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '" + table + "')";
                try (ResultSet rs = stmt.executeQuery(checkSql)) {
                    if (rs.next() && rs.getBoolean(1)) {
                        foundTables.add(table);
                    } else {
                        throw new SQLException("Required table missing in public schema: " + table);
                    }
                }
            }

            // Verify password_hash column in users
            String colCheckSql = "SELECT EXISTS (SELECT FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'password_hash')";
            try (ResultSet rs = stmt.executeQuery(colCheckSql)) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    throw new SQLException("Required column password_hash missing in users table");
                }
            }
        }
        AppLogger.info(TAG, "Verified all " + foundTables.size() + " tables in Neon PostgreSQL: " + foundTables);
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
