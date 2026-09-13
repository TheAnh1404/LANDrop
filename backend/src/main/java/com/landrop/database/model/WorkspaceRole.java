package com.landrop.database.model;

/**
 * Role hierarchy for workspace membership.
 */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MEMBER,
    GUEST;

    public static WorkspaceRole fromString(String role) {
        if (role == null) {
            return MEMBER;
        }
        try {
            return WorkspaceRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEMBER;
        }
    }
}
