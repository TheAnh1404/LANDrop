package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.TransferRecord;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class TransferRepository {

    private final DatabaseManager databaseManager;

    public TransferRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public TransferRepository() {
        this(DatabaseManager.getInstance());
    }

    public TransferRecord create(TransferRecord record) throws SQLException {
        String sql = """
            INSERT INTO transfers (id, file_id, sender_user_id, receiver_user_id, sender_device_id, receiver_device_id, status, created_at, started_at, completed_at, failed_at, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, file_id, sender_user_id, receiver_user_id, sender_device_id, receiver_device_id, status, created_at, started_at, completed_at, failed_at, error_message
        """;

        Instant now = Instant.now();
        UUID id = record.id() != null ? record.id() : UUID.randomUUID();

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, record.fileId());
            ps.setObject(3, record.senderUserId());
            ps.setObject(4, record.receiverUserId());
            ps.setString(5, record.senderDeviceId());
            ps.setString(6, record.receiverDeviceId());
            ps.setString(7, record.status() != null ? record.status() : "PENDING");
            ps.setTimestamp(8, Timestamp.from(record.createdAt() != null ? record.createdAt() : now));
            ps.setTimestamp(9, record.startedAt() != null ? Timestamp.from(record.startedAt()) : null);
            ps.setTimestamp(10, record.completedAt() != null ? Timestamp.from(record.completedAt()) : null);
            ps.setTimestamp(11, record.failedAt() != null ? Timestamp.from(record.failedAt()) : null);
            ps.setString(12, record.errorMessage());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Failed to create transfer record.");
    }

    public Optional<TransferRecord> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM transfers WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public TransferRecord updateStatus(UUID id, String status, Instant startedAt, Instant completedAt, Instant failedAt, String errorMessage) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE transfers SET status = ?");
        if (startedAt != null) sql.append(", started_at = ?");
        if (completedAt != null) sql.append(", completed_at = ?");
        if (failedAt != null) sql.append(", failed_at = ?");
        if (errorMessage != null) sql.append(", error_message = ?");
        sql.append(" WHERE id = ? RETURNING *");

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, status);
            if (startedAt != null) ps.setTimestamp(idx++, Timestamp.from(startedAt));
            if (completedAt != null) ps.setTimestamp(idx++, Timestamp.from(completedAt));
            if (failedAt != null) ps.setTimestamp(idx++, Timestamp.from(failedAt));
            if (errorMessage != null) ps.setString(idx++, errorMessage);
            ps.setObject(idx, id);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Transfer record not found for update: " + id);
    }

    public List<TransferRecord> findByUserId(UUID userId) throws SQLException {
        String sql = "SELECT * FROM transfers WHERE sender_user_id = ? OR receiver_user_id = ? ORDER BY created_at DESC";
        List<TransferRecord> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<TransferRecord> findByFileId(UUID fileId) throws SQLException {
        String sql = "SELECT * FROM transfers WHERE file_id = ? ORDER BY created_at DESC";
        List<TransferRecord> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    private TransferRecord mapRow(ResultSet rs) throws SQLException {
        Timestamp start = rs.getTimestamp("started_at");
        Timestamp comp = rs.getTimestamp("completed_at");
        Timestamp fail = rs.getTimestamp("failed_at");

        return new TransferRecord(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("file_id"),
            (UUID) rs.getObject("sender_user_id"),
            (UUID) rs.getObject("receiver_user_id"),
            rs.getString("sender_device_id"),
            rs.getString("receiver_device_id"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            start != null ? start.toInstant() : null,
            comp != null ? comp.toInstant() : null,
            fail != null ? fail.toInstant() : null,
            rs.getString("error_message")
        );
    }
}
