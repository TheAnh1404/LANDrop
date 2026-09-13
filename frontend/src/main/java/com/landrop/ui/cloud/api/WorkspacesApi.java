package com.landrop.ui.cloud.api;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.mapping.CloudMapper;
import com.landrop.ui.cloud.model.Domain;

import java.util.concurrent.CompletableFuture;

public final class WorkspacesApi {
    private final RequestClient client;

    WorkspacesApi(RequestClient client) { this.client = client; }

    public CompletableFuture<Domain.Workspace> get(String id) {
        return client.item("workspaces/" + ApiPaths.segment(id), Dto.WorkspaceResponse.class, CloudMapper::workspace);
    }

    public CompletableFuture<Domain.Page<Domain.Workspace>> list(PageRequest page) {
        return client.list("workspaces", page, Dto.WorkspaceResponse.class, CloudMapper::workspace);
    }

    public CompletableFuture<Domain.Page<Domain.WorkspaceMember>> members(String workspaceId, PageRequest page) {
        return client.list("workspaces/" + ApiPaths.segment(workspaceId) + "/members", page,
                Dto.WorkspaceMemberResponse.class, CloudMapper::member);
    }
}
