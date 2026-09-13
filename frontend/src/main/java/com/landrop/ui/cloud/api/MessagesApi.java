package com.landrop.ui.cloud.api;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.mapping.CloudMapper;
import com.landrop.ui.cloud.model.Domain;

import java.util.concurrent.CompletableFuture;

public final class MessagesApi {
    private final RequestClient client;

    MessagesApi(RequestClient client) { this.client = client; }

    public CompletableFuture<Domain.Page<Domain.Message>> list(String channelId, PageRequest page) {
        return client.list("channels/" + ApiPaths.segment(channelId) + "/messages", page,
                Dto.MessageResponse.class, CloudMapper::message);
    }
}
