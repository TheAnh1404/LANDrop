package com.landrop.ui.cloud.api;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.mapping.CloudMapper;
import com.landrop.ui.cloud.model.Domain;

import java.util.concurrent.CompletableFuture;

public final class FoldersApi {
    private final RequestClient client;

    FoldersApi(RequestClient client) { this.client = client; }

    public CompletableFuture<Domain.Page<Domain.Folder>> list(String workspaceId, PageRequest page) {
        return client.list("workspaces/" + ApiPaths.segment(workspaceId) + "/folders", page,
                Dto.FolderResponse.class, CloudMapper::folder);
    }
}
