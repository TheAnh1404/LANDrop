package com.landrop.database;

import com.landrop.database.migration.DatabaseMigrator;
import com.landrop.database.seed.DatabaseSeeder;
import com.landrop.util.AppLogger;

import java.util.List;

/**
 * Command-line utility for executing database migrations, seeds, and verification.
 */
public final class DatabaseCli {

    private static final String TAG = "DatabaseCli";

    public static void main(String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase() : "verify";

        try {
            DatabaseManager dbManager = DatabaseManager.getInstance();

            switch (action) {
                case "migrate" -> {
                    System.out.println("Running database migration against Neon PostgreSQL...");
                    DatabaseMigrator migrator = new DatabaseMigrator(dbManager);
                    migrator.migrate();
                    System.out.println("Migration completed successfully.");
                }
                case "seed" -> {
                    System.out.println("Running deterministic database seed...");
                    DatabaseSeeder seeder = new DatabaseSeeder(dbManager);
                    seeder.seed();
                    System.out.println("Database seed completed successfully.");
                }
                case "verify" -> {
                    System.out.println("Running Phase 14 verification checks...");
                    boolean connected = dbManager.testConnection();
                    if (!connected) {
                        System.err.println("FAIL: Database connection failed.");
                        System.exit(1);
                    }
                    System.out.println("PASS: Database connected successfully.");

                    DatabaseMigrator migrator = new DatabaseMigrator(dbManager);
                    List<String> tables = migrator.verifyTablesCreated();
                    System.out.println("PASS: All " + tables.size() + " tables verified: " + tables);

                    DatabaseSeeder seeder = new DatabaseSeeder(dbManager);
                    seeder.seed();
                    System.out.println("PASS: Seed completed successfully.");
                    System.out.println("ALL PHASE 14 CHECKS PASSED.");
                }
                default -> {
                    System.err.println("Unknown command: " + action + ". Usage: java DatabaseCli [migrate|seed|verify]");
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            AppLogger.error(TAG, "DatabaseCli failed for action: " + action, e);
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            try {
                DatabaseManager.getInstance().close();
            } catch (Exception ignored) {}
        }
    }
}
