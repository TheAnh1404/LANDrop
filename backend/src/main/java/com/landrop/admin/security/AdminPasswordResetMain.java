package com.landrop.admin.security;

/** Password changes for the fixed local SYSTEM_ADMIN account are disabled. */
public final class AdminPasswordResetMain {
    private AdminPasswordResetMain() {}

    public static void main(String[] args) throws Exception {
        throw new UnsupportedOperationException("The local SYSTEM_ADMIN password is fixed and cannot be changed.");
    }
}
