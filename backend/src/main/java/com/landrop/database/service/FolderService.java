package com.landrop.database.service;

import com.landrop.database.model.Folder;
import com.landrop.database.repository.FolderRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FolderService {

    private final FolderRepository folderRepository;

    public FolderService(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    public FolderService() {
        this(new FolderRepository());
    }

    public Folder createFolder(UUID workspaceId, String name, UUID parentFolderId) throws SQLException {
        if (workspaceId == null) {
            throw new IllegalArgumentException("Workspace ID is required.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Folder name cannot be empty.");
        }
        Folder folder = new Folder(workspaceId, name.trim(), parentFolderId);
        return folderRepository.create(folder);
    }

    public Optional<Folder> getFolderById(UUID id) throws SQLException {
        return folderRepository.findById(id);
    }

    public List<Folder> listWorkspaceFolders(UUID workspaceId, UUID parentFolderId) throws SQLException {
        return folderRepository.findByWorkspaceId(workspaceId, parentFolderId);
    }

    public boolean deleteFolder(UUID id) throws SQLException {
        return folderRepository.deleteById(id);
    }
}
