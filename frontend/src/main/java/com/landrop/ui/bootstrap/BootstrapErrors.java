package com.landrop.ui.bootstrap;

import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.realtime.ServerErrorException;

/** Only curated messages cross into the login/bootstrap UI. */
public final class BootstrapErrors {
    private BootstrapErrors() { }
    public static String code(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) error = error.getCause();
        if (error instanceof ApiException api) {
            if ("TOKEN_REVOKED".equals(api.code())) return "SESSION_REVOKED";
            if (api.statusCode() == 401 && !invalidAuth(api.code()) && !"INVALID_CREDENTIALS".equals(api.code())) return "SESSION_EXPIRED";
            return api.code();
        }
        if (error instanceof ServerErrorException server) return server.code();
        if (error instanceof java.io.IOException) return "SERVER_UNREACHABLE";
        return "ERROR";
    }
    public static boolean invalidAuth(String code) {
        return java.util.Set.of("SESSION_EXPIRED", "SESSION_REVOKED", "ACCOUNT_DISABLED", "UNAUTHENTICATED", "UNAUTHORIZED", "ADMIN_LOGIN_REQUIRED").contains(code);
    }
    public static String message(String code) {
        return switch (code) {
            case "INVALID_SERVER_CONFIG" -> "Enter valid HTTP and WebSocket addresses for the same server.";
            case "INVALID_CREDENTIALS" -> "Email or password is incorrect.";
            case "ACCOUNT_DISABLED" -> "Your account is disabled. Contact your server administrator.";
            case "ADMIN_LOGIN_REQUIRED" -> "Admin accounts sign in through the Server Control Center, not the LANDrop Client.";
            case "ADMIN_REQUIRED" -> "A system administrator account is required for the Server Control Center.";
            case "SESSION_EXPIRED", "UNAUTHENTICATED", "UNAUTHORIZED" -> "Your session expired. Please sign in again.";
            case "SESSION_REVOKED" -> "Your session was revoked. Please sign in again.";
            case "SERVER_UNREACHABLE", "NETWORK_ERROR", "TIMEOUT" -> "Server unreachable. Check the address and your network.";
            case "DEVICE_CONFLICT", "DEVICE_ALREADY_OWNED" -> "This installation belongs to another account. Contact your server administrator.";
            case "PROTOCOL_INCOMPATIBLE", "PROTOCOL_VERSION_UNSUPPORTED", "AUTH_BINDING_REQUIRED" -> "This server does not support the required authenticated client protocol.";
            case "ADMIN_DISCONNECT" -> "Disconnected by your server administrator.";
            case "SERVER_SHUTDOWN" -> "The server has shut down.";
            default -> "Unable to complete startup. Please retry or contact your server administrator.";
        };
    }
}
