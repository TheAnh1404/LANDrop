package com.landrop.database;

import com.landrop.util.AppLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe connection pool manager for Neon PostgreSQL database.
 */
public final class DatabaseManager implements AutoCloseable {

    private static final String TAG = "DatabaseManager";
    private static volatile DatabaseManager instance;

    private final HikariDataSource dataSource;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private DatabaseManager(String rawDatabaseUrl) {
        this(rawDatabaseUrl, null);
    }

    private DatabaseManager(String rawDatabaseUrl, String schema) {
        if (rawDatabaseUrl == null || rawDatabaseUrl.isBlank()) {
            throw new IllegalArgumentException(
                "DATABASE_URL is not defined. Set the DATABASE_URL environment variable or configure .env."
            );
        }

        if (schema != null && !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid database schema name");
        }
        HikariConfig config = new HikariConfig();
        configureConnection(config, rawDatabaseUrl);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(DatabaseConfig.getMaximumPoolSize());
        config.setMinimumIdle(DatabaseConfig.getMinimumIdle());
        config.setConnectionTimeout(DatabaseConfig.getConnectionTimeoutMs());
        config.setIdleTimeout(DatabaseConfig.getIdleTimeoutMs());
        config.setMaxLifetime(DatabaseConfig.getMaxLifetimeMs());
        config.setPoolName("LANDrop-Neon-Pool");
        if (schema != null) config.setSchema(schema);

        // Sensible connection pool properties for cloud databases (Neon)
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("stringtype", "unspecified");

        this.dataSource = new HikariDataSource(config);
    }

    /** Independent pool for schema-isolated integration runs; does not replace the application singleton. */
    public static DatabaseManager forSchema(String databaseUrl, String schema) {
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}"))
            throw new IllegalArgumentException("Invalid schema name");
        return new DatabaseManager(databaseUrl, schema);
    }

    static void configureConnection(HikariConfig config, String rawUrl) {
        String standardUrl = rawUrl.trim();
        if (standardUrl.startsWith("jdbc:postgresql://")) {
            standardUrl = standardUrl.substring("jdbc:".length());
        } else if (standardUrl.startsWith("postgres://")) {
            standardUrl = "postgresql://" + standardUrl.substring("postgres://".length());
        }

        try {
            java.net.URI uri = new java.net.URI(standardUrl);
            if (!"postgresql".equals(uri.getScheme()) || uri.getHost() == null || uri.getFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException("DATABASE_URL must be a valid PostgreSQL connection URL");
            }
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            String userInfo = uri.getRawUserInfo();
            StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://");
            jdbcUrl.append(host).append(":").append(port);
            jdbcUrl.append(path == null || path.isEmpty() || "/".equals(path) ? "/neondb" : path);
            java.util.List<String> cleanParams = new java.util.ArrayList<>();
            if (query != null && !query.isEmpty()) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    String key = decode(pair[0]);
                    String value = pair.length > 1 ? decode(pair[1]) : "";
                    if ("user".equals(key)) config.setUsername(value);
                    else if ("password".equals(key)) config.setPassword(value);
                    else if (!"channel_binding".equals(key)) cleanParams.add(param);
                }
            }
            if (!cleanParams.isEmpty()) jdbcUrl.append("?").append(String.join("&", cleanParams));
            if (userInfo != null) {
                String[] credentials = userInfo.split(":", 2);
                config.setUsername(decode(credentials[0].replace("+", "%2B")));
                if (credentials.length > 1) config.setPassword(decode(credentials[1].replace("+", "%2B")));
            }
            config.setJdbcUrl(jdbcUrl.toString());
            AppLogger.info(TAG, "Configured PostgreSQL connection.");
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            // URI exception messages can contain the password. Never include the raw URL or cause.
            throw new IllegalArgumentException("Invalid DATABASE_URL; check PostgreSQL scheme, host and URL encoding");
        }
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static DatabaseManager getInstance() {
        if (instance == null || instance.closed.get()) {
            synchronized (DatabaseManager.class) {
                if (instance == null || instance.closed.get()) {
                    String url = DatabaseConfig.getDatabaseUrl();
                    instance = new DatabaseManager(url, System.getProperty("landrop.database.schema"));
                }
            }
        }
        return instance;
    }

    public static synchronized void initialize(String databaseUrl) {
        if (instance != null) {
            instance.close();
        }
        instance = new DatabaseManager(databaseUrl);
    }

    /** Shuts down an existing pool without opening a new connection during cleanup. */
    public static synchronized void closeIfInitialized() {
        if (instance != null) instance.close();
    }

    public Connection getConnection() throws SQLException {
        if (closed.get()) {
            throw new SQLException("DatabaseManager has been closed.");
        }
        return dataSource.getConnection();
    }

    @FunctionalInterface
    public interface Transaction<T> {
        T execute(Connection connection) throws SQLException;
    }

    public <T> T inTransaction(Transaction<T> transaction) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = transaction.execute(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { conn.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    public boolean testConnection() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 as ping")) {
            return rs.next() && rs.getInt("ping") == 1;
        } catch (SQLException e) {
            AppLogger.error(TAG, "Database connection test failed", e);
            return false;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            AppLogger.info(TAG, "Closing HikariCP connection pool...");
            dataSource.close();
            synchronized (DatabaseManager.class) {
                if (instance == this) {
                    instance = null;
                }
            }
        }
    }

    public static String formatJdbcUrl(String url) {
        String trimmed = url.trim();
        if (trimmed.startsWith("jdbc:postgresql://")) {
            return trimmed;
        }
        if (trimmed.startsWith("postgresql://")) {
            return "jdbc:postgresql://" + trimmed.substring("postgresql://".length());
        }
        if (trimmed.startsWith("postgres://")) {
            return "jdbc:postgresql://" + trimmed.substring("postgres://".length());
        }
        return trimmed;
    }

}
