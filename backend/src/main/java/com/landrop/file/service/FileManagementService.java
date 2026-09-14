package com.landrop.file.service;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.*;
import com.landrop.database.repository.FileRepository;
import com.landrop.database.repository.FolderRepository;
import com.landrop.database.repository.WorkspaceRepository;
import com.landrop.file.dto.FileDto;
import com.landrop.file.dto.FolderDto;
import com.landrop.file.exception.FileException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating workspace-aware file and folder management.
 */
public class FileManagementService {

    private final DatabaseManager databaseManager;
    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;
    private final WorkspaceRepository workspaceRepository;

    public FileManagementService(DatabaseManager databaseManager,
                                 FileRepository fileRepository,
                                 FolderRepository folderRepository,
                                 WorkspaceRepository workspaceRepository) {
        this.databaseManager = databaseManager;
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.workspaceRepository = workspaceRepository;
    }

    public FileManagementService() {
        this(DatabaseManager.getInstance(), new FileRepository(), new FolderRepository(), new WorkspaceRepository());
    }

    // =========================================================================
    // Files
    // =========================================================================

    public List<FileDto> listFiles(UUID userId, UUID workspaceId, UUID folderId, boolean isRootOnly, boolean includeDeleted) throws SQLException {
        requireWorkspaceMember(workspaceId, userId);

        if (folderId != null) {
            Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new FileException(404, "FOLDER_NOT_FOUND", "Folder not found."));
            if (!folder.workspaceId().equals(workspaceId)) {
                throw new FileException(404, "FOLDER_NOT_FOUND", "Folder does not belong to workspace.");
            }
        }

        List<FileRecord> records = fileRepository.findByWorkspaceId(workspaceId, folderId, isRootOnly, includeDeleted);
        return records.stream().map(this::toFileDto).toList();
    }

    public FileDto getFile(UUID userId, UUID fileId) throws SQLException {
        FileRecord file = fileRepository.findById(fileId)
            .orElseThrow(() -> new FileException(404, "FILE_NOT_FOUND", "File not found."));

        requireWorkspaceMember(file.workspaceId(), userId);
        return toFileDto(file);
    }

    public FileDto createFileMetadata(UUID userId, UUID workspaceId, String name, long size, String mimeType, String hash, UUID folderId, String storageType) throws SQLException {
        if (name == null || name.trim().isBlank()) {
            throw new FileException(400, "INVALID_REQUEST", "File name cannot be empty.");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 255) {
            throw new FileException(400, "INVALID_REQUEST", "File name cannot exceed 255 characters.");
        }
        if (size < 0) {
            throw new FileException(400, "INVALID_REQUEST", "File size cannot be negative.");
        }

        requireWorkspaceMember(workspaceId, userId);

        if (folderId != null) {
            Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new FileException(404, "FOLDER_NOT_FOUND", "Folder not found."));
            if (!folder.workspaceId().equals(workspaceId)) {
                throw new FileException(400, "INVALID_REQUEST", "Destination folder does not belong to target workspace.");
            }
            if (folder.isDeleted()) {
                throw new FileException(400, "INVALID_REQUEST", "Cannot add file to a deleted folder.");
            }
        }

        String actualStorageType = (storageType != null && !storageType.isBlank()) ? storageType.trim() : "LAN_ONLY";
        FileRecord toCreate = new FileRecord(
            UUID.randomUUID(),
            workspaceId,
            userId, // uploader
            folderId,
            trimmedName,
            trimmedName,
            size,
            mimeType,
            hash,
            actualStorageType,
            null, // storagePath
            null, null, null
        );

        FileRecord created = fileRepository.create(toCreate);
        return toFileDto(created);
    }

    public FileDto updateFile(UUID userId, UUID fileId, String newName, UUID newFolderId, boolean updateFolder) throws SQLException {
        FileRecord existing = fileRepository.findById(fileId)
            .orElseThrow(() -> new FileException(404, "FILE_NOT_FOUND", "File not found."));

        WorkspaceRole role = requireWorkspaceMember(existing.workspaceId(), userId);
        if (!existing.userId().equals(userId) && role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
            throw new FileException(403, "FORBIDDEN", "Only file uploader or workspace administrator can update this file.");
        }
        if (existing.isDeleted()) {
            throw new FileException(400, "INVALID_REQUEST", "Deleted files cannot be modified.");
        }

        String trimmedName = null;
        if (newName != null) {
            trimmedName = newName.trim();
            if (trimmedName.isBlank() || trimmedName.length() > 255) {
                throw new FileException(400, "INVALID_REQUEST", "File name must be between 1 and 255 characters.");
            }
        }

        if (updateFolder && newFolderId != null) {
            Folder folder = folderRepository.findById(newFolderId)
                .orElseThrow(() -> new FileException(404, "FOLDER_NOT_FOUND", "Destination folder not found."));
            if (!folder.workspaceId().equals(existing.workspaceId())) {
                throw new FileException(400, "INVALID_REQUEST", "Destination folder belongs to another workspace.");
            }
            if (folder.isDeleted()) {
                throw new FileException(400, "INVALID_REQUEST", "Cannot move file into a deleted folder.");
            }
        }

        FileRecord updated = fileRepository.update(fileId, trimmedName, newFolderId, updateFolder);
        return toFileDto(updated);
    }

    public FileDto deleteFile(UUID userId, UUID fileId) throws SQLException {
        FileRecord existing = fileRepository.findById(fileId)
            .orElseThrow(() -> new FileException(404, "FILE_NOT_FOUND", "File not found."));

        WorkspaceRole role = requireWorkspaceMember(existing.workspaceId(), userId);
        if (!existing.userId().equals(userId) && role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
            throw new FileException(403, "FORBIDDEN", "Only file uploader or workspace administrator can delete this file.");
        }

        FileRecord deleted = fileRepository.softDelete(fileId);
        return toFileDto(deleted);
    }

    // =========================================================================
    // Folders
    // =========================================================================

    public List<FolderDto> listFolders(UUID userId, UUID workspaceId, UUID parentId, boolean isRootOnly, boolean includeDeleted) throws SQLException {
        requireWorkspaceMember(workspaceId, userId);
        List<Folder> folders = folderRepository.findByWorkspaceId(workspaceId, parentId, isRootOnly, includeDeleted);
        return folders.stream().map(this::toFolderDto).toList();
    }

    public FolderDto getFolder(UUID userId, UUID folderId) throws SQLException {
        Folder folder = folderRepository.findById(folderId)
            .orElseThrow(() -> new FileException(404, "FOLDER_NOT_FOUND", "Folder not found."));

        requireWorkspaceMember(folder.workspaceId(), userId);
        return toFolderDto(folder);
    }

    public FolderDto createFolder(UUID userId, UUID workspaceId, String name, UUID parentFolderId) throws SQLException {
        if (name == null || name.trim().isBlank()) {
            throw new FileException(400, "INVALID_REQUEST", "Folder name cannot be empty.");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 255) {
            throw new FileException(400, "INVALID_REQUEST", "Folder name cannot exceed 255 characters.");
        }

        requireWorkspaceMember(workspaceId, userId);

        if (parentFolderId != null) {
            Folder parent = folderRepository.findById(parentFolderId)
                .orElseThrow(() -> new FileException(404, "FOLDER_NOT_FOUND", "Parent folder not found."));
            if (!parent.workspaceId().equals(workspaceId)) {
                throw new FileException(400, "INVALID_REQUEST", "Parent folder belongs to another workspace.");
            }
            if (parent.isDeleted()) {
                throw new FileException(400, "INVALID_REQUEST", "Cannot create folder under a deleted folder.");
            }
        }

        Folder folder = new Folder(UUID.randomUUID(), workspaceId, trimmedName, parentFolderId, userId, null, null, null);
        Folder created = folderRepository.create(folder);
        return toFolderDto(created);
    }

    public FolderDto updateFolder(UUID userId, UUID folderId, String newName, UUID newParentFolderId, boolean updateParent) throws SQLException {
        Folder existing = folderRepository.findById(folderId)
            .orElseThrow(() -> new FileException(404, "FOLDER_NOT_FOUND", "Folder not found."));

        WorkspaceRole role = requireWorkspaceMember(existing.workspaceId(), userId);
        if (existing.createdById() != null && !existing.createdById().equals(userId)
                && role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
            throw new FileException(403, "FORBIDDEN", "Only folder creator or workspace administrator can update this folder.");
        }
        if (existing.isDeleted()) {
            throw new FileException(400, "INVALID_REQUEST", "Deleted folders cannot be modified.");
        }

        String trimmedName = null;
        if (newName != null) {
            trimmedName = newName.trim();
            if (trimmedName.isBlank() || trimmedName.length() > 255) {
                throw new FileException(400, "INVALID_REQUEST", "Folder name must be between 1 and 255 characters.");
            }
        }

        if (updateParent) {
            if (newParentFolderId != null) {
                if (newParentFolderId.equals(folderId)) {
                    throw new FileException(400, "INVALID_REQUEST", "Cannot set folder parent to itself.");
                }
                Folder newParent = folderRepository.findById(newParentFolderId)
                    .orElseThrow(() -> new FileException(404, "FOLDER_NOT_FOUND", "Destination parent folder not found."));
                if (!newParent.workspaceId().equals(existing.workspaceId())) {
                    throw new FileException(400, "INVALID_REQUEST", "Destination folder belongs to another workspace.");
                }
                if (newParent.isDeleted()) {
                    throw new FileException(400, "INVALID_REQUEST", "Cannot move into a deleted folder.");
                }
                if (folderRepository.isDescendantOrSelf(newParentFolderId, folderId)) {
                    throw new FileException(400, "INVALID_REQUEST", "Cannot move folder into its own subfolder (cycle detected).");
                }
            }
        }

        Folder updated = folderRepository.update(folderId, trimmedName, newParentFolderId, updateParent);
        return toFolderDto(updated);
    }

    public FolderDto deleteFolder(UUID userId, UUID folderId) throws SQLException {
        Folder existing = folderRepository.findById(folderId)
            .orElseThrow(() -> new FileException(404, "FOLDER_NOT_FOUND", "Folder not found."));

        WorkspaceRole role = requireWorkspaceMember(existing.workspaceId(), userId);
        if (existing.createdById() != null && !existing.createdById().equals(userId)
                && role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
            throw new FileException(403, "FORBIDDEN", "Only folder creator or workspace administrator can delete this folder.");
        }

        Folder deleted = folderRepository.softDelete(folderId);
        return toFolderDto(deleted);
    }

    // =========================================================================
    // Authorization & Mappers
    // =========================================================================

    public WorkspaceRole requireWorkspaceMember(UUID workspaceId, UUID userId) throws SQLException {
        String sql = "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, workspaceId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String roleStr = rs.getString("role");
                    WorkspaceRole role = WorkspaceRole.valueOf(roleStr);
                    if (role == WorkspaceRole.GUEST) {
                        throw new FileException(404, "WORKSPACE_NOT_FOUND", "Workspace not found.");
                    }
                    return role;
                }
            }
        }
        throw new FileException(404, "WORKSPACE_NOT_FOUND", "Workspace not found.");
    }

    public WorkspaceRole requireWorkspaceAdmin(UUID workspaceId, UUID userId) throws SQLException {
        WorkspaceRole role = requireWorkspaceMember(workspaceId, userId);
        if (role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
            throw new FileException(403, "FORBIDDEN", "This action requires a workspace administrator.");
        }
        return role;
    }

    public WorkspaceRole requireWorkspaceOwner(UUID workspaceId, UUID userId) throws SQLException {
        WorkspaceRole role = requireWorkspaceMember(workspaceId, userId);
        if (role != WorkspaceRole.OWNER) {
            throw new FileException(403, "FORBIDDEN", "This action requires the workspace owner.");
        }
        return role;
    }

    public FileDto toFileDto(FileRecord r) {
        return new FileDto(
            r.id(),
            r.workspaceId(),
            r.userId(),
            r.folderId(),
            r.name(),
            r.originalName(),
            r.size(),
            r.mimeType(),
            r.sha256(),
            r.storageType(),
            r.createdAt() != null ? r.createdAt().toString() : null,
            r.updatedAt() != null ? r.updatedAt().toString() : null,
            r.deletedAt() != null ? r.deletedAt().toString() : null
        );
    }

    public FolderDto toFolderDto(Folder f) {
        return new FolderDto(
            f.id(),
            f.workspaceId(),
            f.name(),
            f.parentFolderId(),
            f.createdById(),
            f.createdAt() != null ? f.createdAt().toString() : null,
            f.updatedAt() != null ? f.updatedAt().toString() : null,
            f.deletedAt() != null ? f.deletedAt().toString() : null
        );
    }
}
