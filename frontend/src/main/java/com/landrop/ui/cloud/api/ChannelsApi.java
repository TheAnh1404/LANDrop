package com.landrop.ui.cloud.api;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.mapping.CloudMapper;
import com.landrop.ui.cloud.model.Domain;

import java.util.concurrent.CompletableFuture;

public final class ChannelsApi {
    private final RequestClient client;

    ChannelsApi(RequestClient client) { this.client = client; }

    public CompletableFuture<Domain.Page<Domain.Channel>> list(String workspaceId, PageRequest page) {
        return client.list("workspaces/" + ApiPaths.segment(workspaceId) + "/channels", page,
                Dto.ChannelResponse.class, CloudMapper::channel);
    }
}
