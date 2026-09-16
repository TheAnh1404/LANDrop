package com.landrop.admin.security;

import com.landrop.auth.security.PasswordHasher;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.landrop.util.AppLogger;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Bootstraps the initial system administrator account (FOUNDATION-03A.1).
 * Completely decoupled from workspace roles (OWNER, ADMIN, MEMBER).
 */
public class AdminBootstrapService {

    private static final String TAG = "AdminBootstrap";
    public static final String FIXED_ADMIN_USERNAME = "admin";
    public static final String FIXED_ADMIN_EMAIL = "admin@landrop.local";
    public static final String FIXED_ADMIN_PASSWORD = "admin123";
    private final UserRepository userRepository;

    public AdminBootstrapService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Ensures the fixed local SYSTEM_ADMIN account exists and always has the
     * configured-in-code credential. Environment values deliberately do not
     * override this account.
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
            if (existing.isPresent()) {
                User user = existing.get();
                userRepository.updateSystemRole(user.id(), User.ROLE_SYSTEM_ADMIN);
                userRepository.updateStatus(user.id(), User.STATUS_ACTIVE);
                userRepository.updatePasswordHash(user.id(), PasswordHasher.hash(FIXED_ADMIN_PASSWORD));
                AppLogger.info(TAG, "Synchronized fixed SYSTEM_ADMIN account '" + user.username() + "'.");
                return userRepository.findById(user.id()).orElse(user);
            }

            String passwordHash = PasswordHasher.hash(FIXED_ADMIN_PASSWORD);
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
