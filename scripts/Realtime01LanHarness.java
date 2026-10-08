import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.security.PasswordHasher;
import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.config.ServerConfig;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

/** Disposable full-bootstrap runtime for the frontend's real two-client smoke test. */
class Realtime01LanHarness {
    private static final String SCHEMA = "landrop_rt01_lan_" + UUID.randomUUID().toString().replace("-", "");
    private static ServerBootstrap server;
    private static DatabaseManager testDatabase;

    private static void installTestDatabase(String url) throws Exception {
        testDatabase = DatabaseManager.forSchema(url, SCHEMA);
        // Test-only injection: production services share the same singleton pool.
        var singleton = DatabaseManager.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, testDatabase);
    }

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("LANDROP_REALTIME_TEST_DATABASE"))) {
            throw new IllegalStateException("Explicit test-database opt-in is required");
        }
        String url = DatabaseConfig.getDatabaseUrl();
        if (url == null) throw new IllegalStateException("DATABASE_URL is required");
        URI endpoint = URI.create(url.replaceFirst("^jdbc:", ""));
        if (endpoint.getHost().endsWith(".neon.tech") && endpoint.getHost().contains("-pooler.")) {
            url = url.replace(endpoint.getHost(), endpoint.getHost().replace("-pooler.", "."));
        }
        if (endpoint.getQuery() != null && endpoint.getQuery().contains("currentSchema=")) {
            throw new IllegalArgumentException("Use DATABASE_URL without a currentSchema override");
        }
        System.setProperty("landrop.database.url", url);
        boolean created = false;
        try (var admin = DatabaseManager.forSchema(url, "public")) {
            try {
                try (var c = admin.getConnection(); var s = c.createStatement()) {
                    s.execute("CREATE SCHEMA " + SCHEMA);
                    created = true;
                }
                installTestDatabase(url);
                var db = DatabaseManager.getInstance();
                try (var c = db.getConnection(); var s = c.createStatement()) {
                    if (!SCHEMA.equals(c.getSchema())) throw new IllegalStateException("Schema isolation failed");
                    for (String migration : List.of("V1__init_cloud_database_phase14.sql", "V2__auth_sessions_phase15.sql",
                            "V3__realtime_message_identity.sql", "V4__persistent_chat_phase17.sql", "V5__file_management_phase18.sql",
                            "V6__server_control_center_phase03.sql", "V7__authentication_device_enrollment_phase04.sql")) {
                        try (var input = Realtime01LanHarness.class.getResourceAsStream("/db/migration/" + migration)) {
                            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace(
                                    "WHERE typname = 'WorkspaceRole'", "WHERE typname = 'WorkspaceRole' AND typnamespace = current_schema()::regnamespace");
                            s.execute(sql);
                        }
                    }
                }
                String suffix = UUID.randomUUID().toString().replace("-", "");
                String password = "Rt01!" + UUID.randomUUID();
                var users = new UserRepository(db);
                var anh = users.create(new User("anh" + suffix + "@example.test", "anh" + suffix, "Anh", null, PasswordHasher.hash(password)));
                var ken = users.create(new User("ken" + suffix + "@example.test", "ken" + suffix, "Ken", null, PasswordHasher.hash(password)));
                UUID workspace = UUID.randomUUID(), foreign = UUID.randomUUID();
                UUID channel = UUID.randomUUID(), other = UUID.randomUUID(), denied = UUID.randomUUID();
                try (var c = db.getConnection()) {
                    sql(c, "INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,'Realtime LAN',?,?)", workspace, "rt01" + suffix, anh.id());
                    sql(c, "INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,'Foreign',?,?)", foreign, "foreign" + suffix, ken.id());
                    sql(c, "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'OWNER')", workspace, anh.id());
                    sql(c, "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'MEMBER')", workspace, ken.id());
                    sql(c, "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'OWNER')", foreign, ken.id());
                    sql(c, "INSERT INTO channels(id,workspace_id,name) VALUES (?,?,'general')", channel, workspace);
                    sql(c, "INSERT INTO channels(id,workspace_id,name) VALUES (?,?,'other')", other, workspace);
                    sql(c, "INSERT INTO channels(id,workspace_id,name) VALUES (?,?,'foreign')", denied, foreign);
                }
                Files.createDirectories(Path.of("tmp"));
                Files.writeString(Path.of("tmp/realtime01-lan-fixtures.json"), new ObjectMapper().writeValueAsString(Map.of(
                        "anh", anh.username(), "ken", ken.username(), "password", password,
                        "workspace", workspace, "channel", channel, "otherChannel", other, "deniedChannel", denied)));
                start();
                System.out.println("RT01_READY");
                try (var scanner = new Scanner(System.in)) {
                    while (scanner.hasNextLine()) {
                        String command = scanner.nextLine();
                        if ("STOP".equals(command)) break;
                        if ("RESTART".equals(command)) {
                            server.stop();
                            server = null;
                            installTestDatabase(url);
                            start();
                            System.out.println("RT01_RESTARTED");
                        } else if (command.startsWith("COUNT ")) {
                            UUID messageId = UUID.fromString(command.substring(6));
                            try (var c = DatabaseManager.getInstance().getConnection();
                                 var p = c.prepareStatement("SELECT count(*) FROM messages WHERE id=?")) {
                                p.setObject(1, messageId);
                                try (var rows = p.executeQuery()) {
                                    rows.next();
                                    System.out.println("RT01_COUNT " + rows.getInt(1));
                                }
                            }
                        } else if ("REMOVE_KEN".equals(command)) {
                            try (var c = DatabaseManager.getInstance().getConnection()) {
                                sql(c, "DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?", workspace, ken.id());
                            }
                            System.out.println("RT01_MEMBER_REMOVED");
                        }
                        System.out.flush();
                    }
                }
            } finally {
                if (server != null) server.stop();
                if (testDatabase != null) testDatabase.close();
                if (created) {
                    if (!SCHEMA.matches("landrop_rt01_lan_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe schema");
                    try (var c = admin.getConnection(); var s = c.createStatement()) {
                        s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                        try (var p = c.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname=?")) {
                            p.setString(1, SCHEMA);
                            try (var rows = p.executeQuery()) {
                                if (rows.next()) throw new IllegalStateException("Test schema remains");
                            }
                        }
                    }
                    Files.deleteIfExists(Path.of("tmp/realtime01-lan-fixtures.json"));
                    System.out.println("RT01_CLEANED");
                }
            }
        }
    }

    private static void start() throws Exception {
        server = new ServerBootstrap(new ServerConfig("0.0.0.0", 3100, 3101, 1, 1000, 10000, "test", "INFO"));
        server.start();
    }

    private static void sql(Connection connection, String statement, Object... values) throws Exception {
        try (var p = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
            p.executeUpdate();
        }
    }
}
