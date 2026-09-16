package com.landrop.admin.security;

import com.landrop.auth.security.PasswordHasher;
import com.landrop.config.AppConfig;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.landrop.util.AppLogger;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Bootstraps the initial system administrator account (FOUNDATION-03A.1).
 * Completely decoupled from workspace roles (OWNER, ADMIN, MEMBER).
 */
public class AdminBootstrapService {

    private static final String TAG = "AdminBootstrap";
    private final UserRepository userRepository;

    public AdminBootstrapService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Ensures at least one SYSTEM_ADMIN user exists in the database.
     *
     * @return The bootstrapped or existing system admin User record, or null if no database
     */
    public User ensureSystemAdminExists() {
        if (userRepository == null) {
            AppLogger.info(TAG, "Database repository unavailable; skipping admin bootstrap.");
            return null;
        }

        try {
            long adminCount = userRepository.countSystemAdmins();
            if (adminCount > 0) {
                AppLogger.info(TAG, "System admin account exists (" + adminCount + " registered).");
                return null;
            }

            String adminUsername = AppConfig.getString("landrop.admin.username", "LANDROP_ADMIN_USERNAME", "admin");
            if (adminUsername == null || adminUsername.isBlank()) {
                adminUsername = AppConfig.getString("admin.username", "ADMIN_USERNAME", "admin");
            }

            String adminEmail = AppConfig.getString("landrop.admin.email", "LANDROP_ADMIN_EMAIL", "admin@landrop.local");
            if (adminEmail == null || adminEmail.isBlank()) {
                adminEmail = AppConfig.getString("admin.email", "ADMIN_EMAIL", "admin@landrop.local");
            }

            String adminPassword = AppConfig.getString("landrop.admin.password", "LANDROP_ADMIN_PASSWORD", null);
            if (adminPassword == null || adminPassword.isBlank()) {
                adminPassword = AppConfig.getString("admin.password", "ADMIN_PASSWORD", null);
            }

            boolean generatedPassword = false;
            if (adminPassword == null || adminPassword.isBlank()) {
                adminPassword = generateRandomPassword();
                generatedPassword = true;
            }

            // Check if user already exists with this username or email
            Optional<User> existingByUsername = userRepository.findByUsername(adminUsername);
            Optional<User> existingByEmail = userRepository.findByEmail(adminEmail);

            Optional<User> existing = existingByUsername.isPresent() ? existingByUsername : existingByEmail;
            if (existing.isPresent()) {
                User user = existing.get();
                userRepository.updateSystemRole(user.id(), User.ROLE_SYSTEM_ADMIN);
                userRepository.updateStatus(user.id(), User.STATUS_ACTIVE);
                userRepository.updatePasswordHash(user.id(), PasswordHasher.hash(adminPassword));
                AppLogger.info(TAG, "Promoted existing user '" + user.username() + "' to SYSTEM_ADMIN.");
                return userRepository.findById(user.id()).orElse(user);
            }

            String passwordHash = PasswordHasher.hash(adminPassword);
            User newAdmin = new User(
                UUID.randomUUID(),
                adminEmail,
                adminUsername,
                "System Administrator",
                null,
                passwordHash,
                User.STATUS_ACTIVE,
                User.ROLE_SYSTEM_ADMIN,
                Instant.now(),
                Instant.now()
            );

            User saved = userRepository.create(newAdmin);
            AppLogger.info(TAG, "Bootstrapped initial SYSTEM_ADMIN user: " + saved.username() + " (" + saved.email() + ")");
            if (generatedPassword) {
                System.out.println("==================================================");
                System.out.println(" [LANDrop Control Center] Generated Admin Password:");
                System.out.println(" Username: " + adminUsername);
                System.out.println(" Password: " + adminPassword);
                System.out.println(" (Please change this password or set LANDROP_ADMIN_PASSWORD in environment)");
                System.out.println("==================================================");
            }
            return saved;
        } catch (SQLException e) {
            AppLogger.error(TAG, "Failed to bootstrap system administrator account", e);
            return null;
        }
    }

    private static String generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
