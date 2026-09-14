package com.landrop.ui.workspace.files.api;

import com.landrop.ui.cloud.model.Domain;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Phase 18 authenticated metadata/request boundary. It never uploads file bytes. */
public interface FileManagerApi {
    CompletableFuture<List<Domain.Folder>> listFolders(String workspaceId, String parentId);
    CompletableFuture<List<Domain.File>> listFiles(String workspaceId, String folderId);
    CompletableFuture<Domain.Folder> createFolder(String workspaceId, String parentId, String name);
    CompletableFuture<Domain.Folder> updateFolder(String folderId, String name, String parentId);
    CompletableFuture<Void> deleteFolder(String folderId);
    CompletableFuture<Domain.File> updateFile(String fileId, String name, String folderId);
    CompletableFuture<Void> deleteFile(String fileId);
    CompletableFuture<Domain.File> registerFile(String workspaceId, String folderId, Path localFile);
    CompletableFuture<List<Domain.Device>> listDevices(String workspaceId, String userId);
    CompletableFuture<Void> requestTransfer(String fileId, String recipientUserId, String deviceId);
}
