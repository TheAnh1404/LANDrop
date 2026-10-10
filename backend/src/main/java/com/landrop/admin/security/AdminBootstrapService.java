package com.landrop.admin.security;

import com.landrop.auth.security.PasswordHasher;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.landrop.util.AppLogger;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Bootstraps the initial system administrator account (FOUNDATION-03A.1 & Security Phase).
 * Supports generating secure random credentials and requiring password change upon first login.
 */
public class AdminBootstrapService {

    private static final String TAG = "AdminBootstrap";
    public static final String FIXED_ADMIN_USERNAME = "admin";
    public static final String FIXED_ADMIN_EMAIL = "admin@landrop.local";
    public static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    @Deprecated
    public static final String FIXED_ADMIN_PASSWORD = DEFAULT_ADMIN_PASSWORD;

    private final UserRepository userRepository;
    private boolean requireRandomPassword = false;
    private boolean preserveExistingPassword = false;
    private volatile String lastEffectivePassword = null;
    private volatile boolean passwordGenerated = false;

    public AdminBootstrapService(UserRepository userRepository) {
        this(userRepository, false, false);
    }

    public AdminBootstrapService(UserRepository userRepository, boolean requireRandomPassword, boolean preserveExistingPassword) {
        this.userRepository = userRepository;
        this.requireRandomPassword = requireRandomPassword;
        this.preserveExistingPassword = preserveExistingPassword;
    }

    public void setRequireRandomPassword(boolean requireRandomPassword) {
        this.requireRandomPassword = requireRandomPassword;
    }

    public void setPreserveExistingPassword(boolean preserveExistingPassword) {
        this.preserveExistingPassword = preserveExistingPassword;
    }

    public String getLastEffectivePassword() {
        return lastEffectivePassword;
    }

    public boolean isPasswordGenerated() {
        return passwordGenerated;
    }

    /**
     * Generates a cryptographically strong temporary password.
     */
    public static String generateSecurePassword(int length) {
        final String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        final String lower = "abcdefghijklmnopqrstuvwxyz";
        final String digits = "0123456789";
        final String specials = "!@#$%^&*";
        final String all = upper + lower + digits + specials;

        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        sb.append(upper.charAt(random.nextInt(upper.length())));
        sb.append(lower.charAt(random.nextInt(lower.length())));
        sb.append(digits.charAt(random.nextInt(digits.length())));
        sb.append(specials.charAt(random.nextInt(specials.length())));
        for (int i = 4; i < length; i++) {
            sb.append(all.charAt(random.nextInt(all.length())));
        }
        // Shuffle characters
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    /**
     * Resolves the effective administrator password based on environment, system properties,
     * random password requirement, or default credentials.
     */
    private String resolveAdminPassword() {
        String configured = com.landrop.config.EnvironmentFiles.resolve("landrop.admin.password", "ADMIN_PASSWORD");
        if (configured != null && !configured.isBlank()) {
            passwordGenerated = false;
            return configured;
        }

        boolean forceRandom = requireRandomPassword ||
                Boolean.getBoolean("landrop.admin.random-password") ||
                Boolean.getBoolean("landrop.admin.require-random-password");

        if (forceRandom) {
            passwordGenerated = true;
            String randomPass = generateSecurePassword(16);
            AppLogger.warn(TAG, "===============================================================");
            AppLogger.warn(TAG, "GENERATED RANDOM SYSTEM_ADMIN PASSWORD: " + randomPass);
            AppLogger.warn(TAG, "Security Notice: Please change this password upon first login.");
            AppLogger.warn(TAG, "===============================================================");
            return randomPass;
        }

        passwordGenerated = false;
        return DEFAULT_ADMIN_PASSWORD;
    }

    /**
     * Ensures the SYSTEM_ADMIN account exists.
     *
     * @return The bootstrapped or existing system admin User record, or null if no database
     */
    public User ensureSystemAdminExists() {
        if (userRepository == null) {
            AppLogger.info(TAG, "Database repository unavailable; skipping admin bootstrap.");
            return null;
        }

        try {
            Optional<User> existingByUsername = userRepository.findByUsername(FIXED_ADMIN_USERNAME);
            Optional<User> existingByEmail = userRepository.findByEmail(FIXED_ADMIN_EMAIL);

            Optional<User> existing = existingByUsername.isPresent() ? existingByUsername : existingByEmail;
            String targetPassword = resolveAdminPassword();
            this.lastEffectivePassword = targetPassword;

            if (existing.isPresent()) {
                User user = existing.get();
                userRepository.updateSystemRole(user.id(), User.ROLE_SYSTEM_ADMIN);
                userRepository.updateStatus(user.id(), User.STATUS_ACTIVE);

                if (!preserveExistingPassword) {
                    userRepository.updatePasswordHash(user.id(), PasswordHasher.hash(targetPassword));
                    AppLogger.info(TAG, "Synchronized SYSTEM_ADMIN account '" + user.username() + "'.");
                } else {
                    AppLogger.info(TAG, "Preserved existing SYSTEM_ADMIN credentials for '" + user.username() + "'.");
                }
                return userRepository.findById(user.id()).orElse(user);
            }

            String passwordHash = PasswordHasher.hash(targetPassword);
            User newAdmin = new User(
                UUID.randomUUID(),
                FIXED_ADMIN_EMAIL,
                FIXED_ADMIN_USERNAME,
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
            return saved;
        } catch (SQLException e) {
            AppLogger.error(TAG, "Failed to bootstrap system administrator account", e);
            return null;
        }
    }

}
