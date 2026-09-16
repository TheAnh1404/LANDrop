package com.landrop.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * Aggregated KPIs and metrics for the LANDrop Server Control Center (FOUNDATION-03A.8).
 */
public record AdminDashboardDto(
    String status,
    long uptimeSeconds,
    String uptimeFormatted,
    int connectedClients,
    long registeredUsers,
    long activeUsers,
    long disabledUsers,
    long activeSessions,
    long totalSessions,
    long systemAdmins,
    long activeTransfers,
    boolean requiresRestart,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant serverTime
) {}
