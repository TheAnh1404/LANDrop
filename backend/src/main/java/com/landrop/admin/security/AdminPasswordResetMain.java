package com.landrop.admin.security;

import com.landrop.auth.security.PasswordHasher;
import com.landrop.database.DatabaseManager;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;

/**
 * Explicit administrative recovery command. The password is read from the
 * LANDROP_ADMIN_RESET_PASSWORD environment variable and never logged.
 */
public final class AdminPasswordResetMain {
    private AdminPasswordResetMain() {}

    public static void main(String[] args) throws Exception {
        String password = System.getenv("LANDROP_ADMIN_RESET_PASSWORD");
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Set LANDROP_ADMIN_RESET_PASSWORD to at least 8 characters.");
        }

        UserRepository users = new UserRepository(DatabaseManager.getInstance());
        User admin = users.findFirstSystemAdmin()
                .orElseThrow(() -> new IllegalStateException("No SYSTEM_ADMIN account exists."));
        users.updatePasswordHash(admin.id(), PasswordHasher.hash(password));
        System.out.println("Admin password reset for username: " + admin.username());
    }
}
