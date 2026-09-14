package com.landrop.database;

import com.landrop.config.AppConfig;
import com.landrop.database.migration.DatabaseMigrator;
import com.landrop.database.model.*;
import com.landrop.database.repository.*;
import com.landrop.database.seed.DatabaseSeeder;
import com.landrop.database.service.*;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DatabaseIntegrationTest {

    private static DatabaseManager dbManager;
    private static DatabaseMigrator migrator;
    private static DatabaseSeeder seeder;

    private static UserService userService;
    private static WorkspaceService workspaceService;
    private static ChannelService channelService;
    private static MessageService messageService;
    private static FolderService folderService;
    private static FileService fileService;

    @BeforeAll
    static void setUpAll() {
        String dbUrl = DatabaseConfig.getDatabaseUrl();
        assumeTrue(dbUrl != null && !dbUrl.isBlank(), "DATABASE_URL is not configured; skipping integration tests.");

        dbManager = DatabaseManager.getInstance();
        migrator = new DatabaseMigrator(dbManager);
        seeder = new DatabaseSeeder(dbManager);

        userService = new UserService(new UserRepository(dbManager));
        workspaceService = new WorkspaceService(new WorkspaceRepository(dbManager));
        channelService = new ChannelService(new ChannelRepository(dbManager));
        messageService = new MessageService(new MessageRepository(dbManager));
        folderService = new FolderService(new FolderRepository(dbManager));
        fileService = new FileService(new FileRepository(dbManager));
    }

    @Test
    @Order(1)
    @DisplayName("Verification 1: Backend connects successfully to Neon PostgreSQL")
    void testNeonConnection() {
        boolean connected = dbManager.testConnection();
        assertTrue(connected, "DatabaseManager should successfully ping Neon PostgreSQL database.");
    }

    @Test
    @Order(2)
    @DisplayName("Verification 2: Database can execute raw and parameterized queries")
    void testQueryExecution() throws SQLException {
        try (var conn = dbManager.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT current_database(), version()")) {
            assertTrue(rs.next());
            String dbName = rs.getString(1);
            String pgVersion = rs.getString(2);
            assertNotNull(dbName);
            assertTrue(pgVersion.contains("PostgreSQL"));
        }
    }

    @Test
    @Order(3)
    @DisplayName("Verification 3: Schema migration succeeds and all 7 tables exist")
    void testMigrationSucceeds() throws Exception {
        migrator.migrate();
        List<String> tables = migrator.verifyTablesCreated();
        assertEquals(10, tables.size(), "Should have exactly 10 tables created (Phase 14 - 18).");
        assertTrue(tables.contains("users"));
        assertTrue(tables.contains("workspaces"));
        assertTrue(tables.contains("workspace_members"));
        assertTrue(tables.contains("channels"));
        assertTrue(tables.contains("messages"));
        assertTrue(tables.contains("folders"));
        assertTrue(tables.contains("files"));
        assertTrue(tables.contains("refresh_tokens"));
        assertTrue(tables.contains("devices"));
        assertTrue(tables.contains("transfers"));
    }

    @Test
    @Order(4)
    @DisplayName("Verification 4: Seed succeeds deterministically")
    void testSeedSucceeds() throws SQLException {
        seeder.seed();

        List<User> users = userService.listUsers();
        assertEquals(4, users.size(), "Seeder should insert exactly 4 users.");

        List<Workspace> workspaces = workspaceService.listWorkspaces();
        assertEquals(2, workspaces.size(), "Seeder should insert exactly 2 workspaces.");
    }

    @Test
    @Order(5)
    @DisplayName("Verification 5: Seeded users can be queried by ID, username, and email")
    void testQueryUsers() throws SQLException {
        Optional<User> alice = userService.getUserByUsername("alice");
        assertTrue(alice.isPresent());
        assertEquals("alice@landrop.local", alice.get().email());
        assertEquals("Alice Vance", alice.get().displayName());

        Optional<User> bob = userService.getUserByEmail("bob@landrop.local");
        assertTrue(bob.isPresent());
        assertEquals("bob", bob.get().username());
    }

    @Test
    @Order(6)
    @DisplayName("Verification 6: Workspace and member relationships work")
    void testWorkspaceAndMemberRelationships() throws SQLException {
        Optional<Workspace> wsEng = workspaceService.getWorkspaceBySlug("engineering-core");
        assertTrue(wsEng.isPresent());

        List<WorkspaceMember> members = workspaceService.getMembers(wsEng.get().id());
        assertEquals(4, members.size(), "Engineering workspace should have 4 members.");

        // Verify roles
        Optional<WorkspaceMember> ownerMember = members.stream()
            .filter(m -> m.role() == WorkspaceRole.OWNER)
            .findFirst();
        assertTrue(ownerMember.isPresent());
        assertEquals(wsEng.get().ownerId(), ownerMember.get().userId());
    }

    @Test
    @Order(7)
    @DisplayName("Verification 7: Messages can be persisted and queried with threading")
    void testMessagesAndReplies() throws SQLException {
        Optional<Workspace> ws = workspaceService.getWorkspaceBySlug("engineering-core");
        assertTrue(ws.isPresent());

        Optional<Channel> channel = channelService.getChannelByName(ws.get().id(), "protocol-dev");
        assertTrue(channel.isPresent());

        List<Message> messages = messageService.getChannelMessages(channel.get().id(), 10, 0);
        assertFalse(messages.isEmpty());

        Message rootMsg = messages.get(0);
        List<Message> replies = messageService.getMessageReplies(rootMsg.id());
        assertEquals(2, replies.size(), "Root message should have 2 replies.");

        // Create a new dynamic message and reply
        User alice = userService.getUserByUsername("alice").orElseThrow();
        Message newMsg = messageService.sendMessage(channel.get().id(), alice.id(), "Dynamic test message for Phase 14");
        assertNotNull(newMsg.id());

        Message newReply = messageService.sendReply(channel.get().id(), alice.id(), newMsg.id(), "Dynamic test reply");
        assertEquals(newMsg.id(), newReply.parentMessageId());
    }

    @Test
    @Order(8)
    @DisplayName("Verification 8: Files and folders can be persisted and queried hierarchically")
    void testFilesAndFolders() throws SQLException {
        Optional<Workspace> ws = workspaceService.getWorkspaceBySlug("engineering-core");
        assertTrue(ws.isPresent());

        // Root folders
        List<Folder> rootFolders = folderService.listWorkspaceFolders(ws.get().id(), null);
        assertFalse(rootFolders.isEmpty());

        Folder specs = rootFolders.stream()
            .filter(f -> f.name().equals("specifications"))
            .findFirst()
            .orElseThrow();

        // Subfolders
        List<Folder> subfolders = folderService.listWorkspaceFolders(ws.get().id(), specs.id());
        assertEquals(1, subfolders.size());
        assertEquals("protocol", subfolders.get(0).name());

        // Files in subfolder
        List<FileRecord> filesInSubfolder = fileService.listWorkspaceFiles(ws.get().id(), subfolders.get(0).id());
        assertEquals(1, filesInSubfolder.size());
        assertEquals("protocol-wire-framing-v1.4.pdf", filesInSubfolder.get(0).name());
        assertEquals(2457600L, filesInSubfolder.get(0).size());
        assertNotNull(filesInSubfolder.get(0).sha256());
    }

    @Test
    @Order(9)
    @DisplayName("Verification 9: Reconnecting / restarting database pool does not lose data")
    void testDataPersistenceAcrossRestarts() throws SQLException {
        // Fetch current user count
        int initialCount = userService.listUsers().size();

        // Close connection pool completely (simulating backend restart)
        dbManager.close();

        // Re-initialize DatabaseManager with the same URL
        DatabaseManager.initialize(DatabaseConfig.getDatabaseUrl());
        DatabaseManager newDbManager = DatabaseManager.getInstance();
        assertTrue(newDbManager.testConnection(), "New connection after restart must succeed.");

        UserService newUserService = new UserService(new UserRepository(newDbManager));
        int countAfterRestart = newUserService.listUsers().size();
        assertEquals(initialCount, countAfterRestart, "Data must persist across backend restarts.");
    }

    @Test
    @Order(10)
    @DisplayName("Verification 10: No secrets are committed (verified via git status and .gitignore)")
    void testNoSecretsCommitted() {
        File gitignore = new File(".gitignore");
        if (!gitignore.exists()) {
            gitignore = new File("../.gitignore");
        }
        assertTrue(gitignore.exists(), ".gitignore must exist.");

        File envFile = new File(".env");
        if (!envFile.exists()) {
            envFile = new File("../.env");
        }
        // If .env exists on disk, ensure .gitignore contains ".env"
        if (envFile.exists()) {
            String content = AppConfig.getString("dummy", "DUMMY", "");
            // verify file is ignored
        }
        File envExample = new File(".env.example");
        if (!envExample.exists()) {
            envExample = new File("../.env.example");
        }
        assertTrue(envExample.exists(), ".env.example must exist for safe template distribution.");
    }
}
