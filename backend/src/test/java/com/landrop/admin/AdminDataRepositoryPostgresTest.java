package com.landrop.admin;

import com.landrop.database.DatabaseManager;
import com.landrop.database.repository.AdminDataRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Requires an explicitly named test database, never the application's DATABASE_URL. */
@EnabledIfSystemProperty(named = "landrop.test.postgres", matches = "true")
class AdminDataRepositoryPostgresTest {
    private static DatabaseManager database;
    private static final String SCHEMA = "landrop_admin_test_" + UUID.randomUUID().toString().replace("-", "");
    private static String url;
    private static boolean created;

    @BeforeAll static void setup() throws Exception {
        url = System.getProperty("landrop.test.database.url", System.getenv("TEST_DATABASE_URL"));
        Assumptions.assumeTrue(url != null && !url.isBlank(), "Set TEST_DATABASE_URL to a dedicated test database");
        try (var pool = DatabaseManager.forSchema(url, "public"); var connection = pool.getConnection(); var query = connection.createStatement()) {
            query.execute("CREATE SCHEMA " + SCHEMA); created = true;
        }
        database = DatabaseManager.forSchema(url, SCHEMA);
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            assertEquals(SCHEMA, connection.getSchema());
            for (String resource : List.of("V1__init_cloud_database_phase14.sql", "V2__auth_sessions_phase15.sql",
                    "V3__realtime_message_identity.sql", "V4__persistent_chat_phase17.sql", "V5__file_management_phase18.sql",
                    "V6__server_control_center_phase03.sql", "V7__authentication_device_enrollment_phase04.sql")) {
                try (var input = AdminDataRepositoryPostgresTest.class.getResourceAsStream("/db/migration/" + resource)) {
                    assertNotNull(input);
                    String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("WHERE typname = 'WorkspaceRole'", "WHERE typname = 'WorkspaceRole' AND typnamespace = current_schema()::regnamespace");
                    statement.execute(sql);
                }
            }
            statement.execute("""
                INSERT INTO users (id, email, username, display_name) VALUES ('11111111-1111-1111-1111-111111111111', 'test@example.test', 'test', 'Test');
                INSERT INTO workspaces (id, name, slug, owner_id) VALUES ('22222222-2222-2222-2222-222222222222', 'Test', 'test', '11111111-1111-1111-1111-111111111111');
                INSERT INTO channels (id, workspace_id, name) VALUES ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 'general');
                INSERT INTO messages (channel_id, user_id, content)
                    SELECT '33333333-3333-3333-3333-333333333333'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'Message ' || n FROM generate_series(1, 101) n;
                INSERT INTO folders (workspace_id, name) VALUES ('22222222-2222-2222-2222-222222222222', 'Documents');
                INSERT INTO files (id, workspace_id, user_id, name, size) VALUES ('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'test.txt', 32);
                INSERT INTO transfers (file_id, sender_user_id, receiver_user_id) VALUES ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111');
                INSERT INTO devices (id, user_id, client_instance_id, device_name) VALUES ('test-device', '11111111-1111-1111-1111-111111111111', 'test-installation', 'Test laptop');
                """);
        }
    }

    @AfterAll static void cleanup() throws Exception {
        if (database != null) database.close();
        if (created) {
            try (var pool = DatabaseManager.forSchema(url, "public"); var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    @Test void allProjectionsReadMigratedSchemaAndExcludeCredentialsAndStoragePaths() throws Exception {
        var repository = new AdminDataRepository(database);
        for (var dataset : AdminDataRepository.Dataset.values()) {
            var page = repository.read(dataset, 0, 100);
            assertTrue(page.total() >= 1, dataset.toString()); assertFalse(page.rows().isEmpty());
            assertTrue(page.columns().stream().noneMatch(c -> c.contains("password") || c.contains("token") || c.equals("storage_path") || c.equals("storage_key")));
        }
        try (var connection = database.getConnection()) { assertFalse(connection.isReadOnly(), "Pool must reset read-only transactions before normal writes"); }
    }

    @Test void pagesAreBoundedAndStableAndInvalidRequestsAreRejected() throws Exception {
        var repository = new AdminDataRepository(database);
        var first = repository.read(AdminDataRepository.Dataset.MESSAGES, 0, 100);
        var last = repository.read(AdminDataRepository.Dataset.MESSAGES, 100, 100);
        assertEquals(101, first.total()); assertEquals(100, first.rows().size()); assertEquals(1, last.rows().size());
        assertFalse(first.rows().stream().anyMatch(row -> row.getFirst().equals(last.rows().getFirst().getFirst())));
        assertEquals(first, repository.read(AdminDataRepository.Dataset.MESSAGES, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> repository.read(null, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> repository.read(AdminDataRepository.Dataset.FILES, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> repository.read(AdminDataRepository.Dataset.FILES, 0, 101));
    }
}
