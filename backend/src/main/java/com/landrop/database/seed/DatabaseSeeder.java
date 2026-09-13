package com.landrop.database.seed;

import com.landrop.auth.security.PasswordHasher;
import com.landrop.database.DatabaseManager;
import com.landrop.database.model.*;
import com.landrop.database.repository.*;
import com.landrop.util.AppLogger;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Deterministic, idempotent development seed generator for Phase 14 Neon database.
 */
public final class DatabaseSeeder {

    private static final String TAG = "DatabaseSeeder";

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ChannelRepository channelRepository;
    private final MessageRepository messageRepository;
    private final FolderRepository folderRepository;
    private final FileRepository fileRepository;

    public DatabaseSeeder(DatabaseManager databaseManager) {
        this.userRepository = new UserRepository(databaseManager);
        this.workspaceRepository = new WorkspaceRepository(databaseManager);
        this.channelRepository = new ChannelRepository(databaseManager);
        this.messageRepository = new MessageRepository(databaseManager);
        this.folderRepository = new FolderRepository(databaseManager);
        this.fileRepository = new FileRepository(databaseManager);
    }

    public DatabaseSeeder() {
        this(DatabaseManager.getInstance());
    }

    public void seed() throws SQLException {
        AppLogger.info(TAG,"Starting deterministic database seed for Phase 14...");

        // 1. Clean existing records in reverse foreign key order
        AppLogger.info(TAG,"Cleaning existing database records for clean idempotent seed...");
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        messageRepository.deleteAll();
        channelRepository.deleteAll();
        workspaceRepository.deleteAllMembers();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        // 2. Deterministic Users
        AppLogger.info(TAG,"Seeding Users with default password 'Password123!'...");
        String defaultPasswordHash = PasswordHasher.hash("Password123!");

        User alice = userRepository.create(new User(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "alice@landrop.local",
            "alice",
            "Alice Vance",
            "https://avatars.landrop.local/alice.png",
            defaultPasswordHash,
            Instant.now(),
            Instant.now()
        ));

        User bob = userRepository.create(new User(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "bob@landrop.local",
            "bob",
            "Bob Chen",
            "https://avatars.landrop.local/bob.png",
            defaultPasswordHash,
            Instant.now(),
            Instant.now()
        ));

        User charlie = userRepository.create(new User(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "charlie@landrop.local",
            "charlie",
            "Charlie Kim",
            "https://avatars.landrop.local/charlie.png",
            defaultPasswordHash,
            Instant.now(),
            Instant.now()
        ));

        User dana = userRepository.create(new User(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "dana@landrop.local",
            "dana",
            "Dana Scully",
            "https://avatars.landrop.local/dana.png",
            defaultPasswordHash,
            Instant.now(),
            Instant.now()
        ));

        // 3. Deterministic Workspaces
        AppLogger.info(TAG,"Seeding Workspaces...");
        Workspace wsEngineering = workspaceRepository.create(new Workspace(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "Engineering Core",
            "engineering-core",
            "Primary engineering workspace for LANDrop protocol, swarm engine, and cloud database foundation",
            alice.id(),
            Instant.now(),
            Instant.now()
        ));

        Workspace wsProduct = workspaceRepository.create(new Workspace(
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            "Product & Design",
            "product-design",
            "Product specifications, user experience research, and client architecture",
            bob.id(),
            Instant.now(),
            Instant.now()
        ));

        // 4. Workspace Memberships
        AppLogger.info(TAG,"Seeding Workspace Members...");
        workspaceRepository.addMember(new WorkspaceMember(
            UUID.fromString("ab000001-0000-4000-8000-000000000001"),
            wsEngineering.id(),
            alice.id(),
            WorkspaceRole.OWNER,
            Instant.now(),
            Instant.now()
        ));
        workspaceRepository.addMember(new WorkspaceMember(
            UUID.fromString("ab000002-0000-4000-8000-000000000002"),
            wsEngineering.id(),
            bob.id(),
            WorkspaceRole.ADMIN,
            Instant.now(),
            Instant.now()
        ));
        workspaceRepository.addMember(new WorkspaceMember(
            UUID.fromString("ab000003-0000-4000-8000-000000000003"),
            wsEngineering.id(),
            charlie.id(),
            WorkspaceRole.MEMBER,
            Instant.now(),
            Instant.now()
        ));
        workspaceRepository.addMember(new WorkspaceMember(
            UUID.fromString("ab000004-0000-4000-8000-000000000004"),
            wsEngineering.id(),
            dana.id(),
            WorkspaceRole.MEMBER,
            Instant.now(),
            Instant.now()
        ));

        workspaceRepository.addMember(new WorkspaceMember(
            UUID.fromString("ab000005-0000-4000-8000-000000000005"),
            wsProduct.id(),
            bob.id(),
            WorkspaceRole.OWNER,
            Instant.now(),
            Instant.now()
        ));
        workspaceRepository.addMember(new WorkspaceMember(
            UUID.fromString("ab000006-0000-4000-8000-000000000006"),
            wsProduct.id(),
            alice.id(),
            WorkspaceRole.MEMBER,
            Instant.now(),
            Instant.now()
        ));
        workspaceRepository.addMember(new WorkspaceMember(
            UUID.fromString("ab000007-0000-4000-8000-000000000007"),
            wsProduct.id(),
            charlie.id(),
            WorkspaceRole.MEMBER,
            Instant.now(),
            Instant.now()
        ));

        // 5. Channels
        AppLogger.info(TAG,"Seeding Channels...");
        Channel chGeneral = channelRepository.create(new Channel(
            UUID.fromString("c1111111-1111-4111-8111-111111111111"),
            wsEngineering.id(),
            "general",
            "General engineering coordination and announcements",
            false,
            Instant.now(),
            Instant.now()
        ));

        Channel chProtocolDev = channelRepository.create(new Channel(
            UUID.fromString("c2222222-2222-4222-8222-222222222222"),
            wsEngineering.id(),
            "protocol-dev",
            "Binary TCP framing and multi-source swarming protocol development",
            false,
            Instant.now(),
            Instant.now()
        ));

        Channel chDatabaseInfra = channelRepository.create(new Channel(
            UUID.fromString("c3333333-3333-4333-8333-333333333333"),
            wsEngineering.id(),
            "database-infra",
            "Neon PostgreSQL cloud database foundation and schema migrations",
            true,
            Instant.now(),
            Instant.now()
        ));

        channelRepository.create(new Channel(
            UUID.fromString("c4444444-4444-4444-8444-444444444444"),
            wsProduct.id(),
            "general",
            "Product roadmap, milestones, and release strategy",
            false,
            Instant.now(),
            Instant.now()
        ));

        // 6. Messages with Thread Reply Structure
        AppLogger.info(TAG,"Seeding Messages & Thread Replies...");
        Message rootMsg = messageRepository.create(new Message(
            UUID.fromString("d1111111-1111-4111-8111-111111111111"),
            chProtocolDev.id(),
            alice.id(),
            null,
            "LANDrop Phase 13 Core swarming protocol has passed all 182 test cases with bit-exact integrity verification.",
            Instant.now(),
            Instant.now()
        ));

        messageRepository.create(new Message(
            UUID.fromString("d2222222-2222-4222-8222-222222222222"),
            chProtocolDev.id(),
            bob.id(),
            rootMsg.id(),
            "Excellent! The concurrent positional FileChannel writes achieved 382 MB/s on loopback.",
            Instant.now(),
            Instant.now()
        ));

        messageRepository.create(new Message(
            UUID.fromString("d3333333-3333-4333-8333-333333333333"),
            chProtocolDev.id(),
            charlie.id(),
            rootMsg.id(),
            "Mid-download late joining and failover chunk reassignment verified cleanly.",
            Instant.now(),
            Instant.now()
        ));

        messageRepository.create(new Message(
            UUID.fromString("d4444444-4444-4444-8444-444444444444"),
            chDatabaseInfra.id(),
            dana.id(),
            null,
            "Phase 14A cloud database foundation successfully initialized with Neon PostgreSQL in 100% Java.",
            Instant.now(),
            Instant.now()
        ));

        // 7. Folders (Hierarchical)
        AppLogger.info(TAG,"Seeding Folders...");
        Folder folderSpecs = folderRepository.create(new Folder(
            UUID.fromString("f1111111-1111-4111-8111-111111111111"),
            wsEngineering.id(),
            "specifications",
            null,
            Instant.now(),
            Instant.now()
        ));

        Folder folderProtocol = folderRepository.create(new Folder(
            UUID.fromString("f2222222-2222-4222-8222-222222222222"),
            wsEngineering.id(),
            "protocol",
            folderSpecs.id(),
            Instant.now(),
            Instant.now()
        ));

        Folder folderReleases = folderRepository.create(new Folder(
            UUID.fromString("f3333333-3333-4333-8333-333333333333"),
            wsEngineering.id(),
            "releases",
            null,
            Instant.now(),
            Instant.now()
        ));

        folderRepository.create(new Folder(
            UUID.fromString("f4444444-4444-4444-8444-444444444444"),
            wsProduct.id(),
            "mockups",
            null,
            Instant.now(),
            Instant.now()
        ));

        // 8. Files
        AppLogger.info(TAG,"Seeding Files...");
        fileRepository.create(new FileRecord(
            UUID.fromString("e1111111-1111-4111-8111-111111111111"),
            wsEngineering.id(),
            alice.id(),
            folderProtocol.id(),
            "protocol-wire-framing-v1.4.pdf",
            2457600L,
            "application/pdf",
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "workspaces/engineering-core/specs/protocol-wire-framing-v1.4.pdf",
            Instant.now(),
            Instant.now()
        ));

        fileRepository.create(new FileRecord(
            UUID.fromString("e2222222-2222-4222-8222-222222222222"),
            wsEngineering.id(),
            bob.id(),
            folderSpecs.id(),
            "architecture-diagram-phase14.png",
            1048576L,
            "image/png",
            "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8",
            "workspaces/engineering-core/specs/architecture-diagram-phase14.png",
            Instant.now(),
            Instant.now()
        ));

        fileRepository.create(new FileRecord(
            UUID.fromString("e3333333-3333-4333-8333-333333333333"),
            wsEngineering.id(),
            alice.id(),
            folderReleases.id(),
            "landrop-core-v0.1.0-frozen.jar",
            16777216L,
            "application/java-archive",
            "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a",
            "workspaces/engineering-core/releases/landrop-core-v0.1.0-frozen.jar",
            Instant.now(),
            Instant.now()
        ));

        fileRepository.create(new FileRecord(
            UUID.fromString("e4444444-4444-4444-8444-444444444444"),
            wsProduct.id(),
            charlie.id(),
            null,
            "desktop-ui-prototype.fig",
            5242880L,
            "application/octet-stream",
            "ef2d127de37b942baad06145e54b0c619a1f22327b2ebbcfbec78f5564afe39d",
            "workspaces/product-design/mockups/desktop-ui-prototype.fig",
            Instant.now(),
            Instant.now()
        ));

        fileRepository.create(new FileRecord(
            UUID.fromString("e5555555-5555-4555-8555-555555555555"),
            wsEngineering.id(),
            dana.id(),
            null,
            "DATABASE_SETUP.md",
            8192L,
            "text/markdown",
            "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
            "workspaces/engineering-core/DATABASE_SETUP.md",
            Instant.now(),
            Instant.now()
        ));

        AppLogger.info(TAG,"✅ Deterministic seed completed successfully!");
    }
}
