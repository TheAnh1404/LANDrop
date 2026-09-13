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
        if (rawDatabaseUrl == null || rawDatabaseUrl.isBlank()) {
            throw new IllegalArgumentException(
                "DATABASE_URL is not defined. Set the DATABASE_URL environment variable or configure .env."
            );
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

        // Sensible connection pool properties for cloud databases (Neon)
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("stringtype", "unspecified");

        this.dataSource = new HikariDataSource(config);
    }

    private static void configureConnection(HikariConfig config, String rawUrl) {
        String standardUrl = rawUrl.trim();
        if (standardUrl.startsWith("jdbc:postgresql://")) {
            standardUrl = "postgresql://" + standardUrl.substring("jdbc:postgresql://".length());
        } else if (standardUrl.startsWith("postgres://")) {
            standardUrl = "postgresql://" + standardUrl.substring("postgres://".length());
        }

        try {
            java.net.URI uri = new java.net.URI(standardUrl);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath();
            String query = uri.getQuery();
            String userInfo = uri.getUserInfo();

            StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://");
            jdbcUrl.append(host).append(":").append(port);
            if (path != null && !path.isEmpty()) {
                jdbcUrl.append(path);
            } else {
                jdbcUrl.append("/neondb");
            }

            if (query != null && !query.isEmpty()) {
                String[] params = query.split("&");
                java.util.List<String> cleanParams = new java.util.ArrayList<>();
                for (String p : params) {
                    // channel_binding is for libpq/scram; filter out for JDBC compatibility
                    if (p.startsWith("channel_binding=")) {
                        continue;
                    }
                    cleanParams.add(p);
                }
                if (!cleanParams.isEmpty()) {
                    jdbcUrl.append("?").append(String.join("&", cleanParams));
                }
            }

            config.setJdbcUrl(jdbcUrl.toString());

            if (userInfo != null && userInfo.contains(":")) {
                int colonIdx = userInfo.indexOf(':');
                config.setUsername(userInfo.substring(0, colonIdx));
                config.setPassword(userInfo.substring(colonIdx + 1));
            }
            AppLogger.info(TAG, "Configured Neon PostgreSQL connection: " + maskUrl(jdbcUrl.toString()));
        } catch (java.net.URISyntaxException e) {
            AppLogger.warn(TAG, "Failed to parse URI, falling back to direct URL: " + e.getMessage());
            config.setJdbcUrl(formatJdbcUrl(rawUrl));
        }
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    String url = DatabaseConfig.getDatabaseUrl();
                    instance = new DatabaseManager(url);
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

    public Connection getConnection() throws SQLException {
        if (closed.get()) {
            throw new SQLException("DatabaseManager has been closed.");
        }
        return dataSource.getConnection();
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

    private static String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll("://([^:]+):([^@]+)@", "://$1:***@");
    }
}
