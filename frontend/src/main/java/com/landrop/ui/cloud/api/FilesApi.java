package com.landrop.ui.cloud.api;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.mapping.CloudMapper;
import com.landrop.ui.cloud.model.Domain;

import java.util.concurrent.CompletableFuture;

public final class FilesApi {
    private final RequestClient client;

    FilesApi(RequestClient client) { this.client = client; }

    public CompletableFuture<Domain.Page<Domain.File>> list(String workspaceId, PageRequest page) {
        return client.list("workspaces/" + ApiPaths.segment(workspaceId) + "/files", page,
                Dto.FileResponse.class, CloudMapper::file);
    }
}
