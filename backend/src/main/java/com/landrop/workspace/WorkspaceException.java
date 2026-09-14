package com.landrop.workspace;

import com.landrop.auth.exception.AuthException;

/** Stable, safe error for the Phase 16 workspace API. */
public final class WorkspaceException extends AuthException {
    private final String code;

    public WorkspaceException(int status, String code, String message) {
        super(message, status);
        this.code = code;
    }

    public String code() { return code; }
}
