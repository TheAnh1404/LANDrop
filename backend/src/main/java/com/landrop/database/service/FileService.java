package com.landrop.database.service;

import com.landrop.database.model.FileRecord;
import com.landrop.database.repository.FileRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FileService {

    private final FileRepository fileRepository;

    public FileService(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    public FileService() {
        this(new FileRepository());
    }

    public FileRecord registerFile(
        UUID workspaceId,
        UUID userId,
        UUID folderId,
        String name,
        long size,
        String mimeType,
        String sha256,
        String storageKey
    ) throws SQLException {
        if (workspaceId == null) {
            throw new IllegalArgumentException("Workspace ID is required.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("File name cannot be empty.");
        }
        if (size < 0) {
            throw new IllegalArgumentException("File size cannot be negative.");
        }

        FileRecord record = new FileRecord(
            workspaceId,
            userId,
            folderId,
            name.trim(),
            size,
            mimeType,
            sha256,
            storageKey
        );
        return fileRepository.create(record);
    }

    public Optional<FileRecord> getFileById(UUID id) throws SQLException {
        return fileRepository.findById(id);
    }

    public List<FileRecord> listWorkspaceFiles(UUID workspaceId, UUID folderId) throws SQLException {
        return fileRepository.findByWorkspaceId(workspaceId, folderId);
    }

    public List<FileRecord> listUserFiles(UUID userId) throws SQLException {
        return fileRepository.findByUserId(userId);
    }

    public boolean deleteFile(UUID id) throws SQLException {
        return fileRepository.deleteById(id);
    }
}
