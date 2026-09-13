package com.landrop.ui.cloud.api;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.mapping.CloudMapper;
import com.landrop.ui.cloud.model.Domain;

import java.util.concurrent.CompletableFuture;

public final class UsersApi {
    private final RequestClient client;

    UsersApi(RequestClient client) { this.client = client; }

    public CompletableFuture<Domain.User> get(String id) {
        return client.item("users/" + ApiPaths.segment(id), Dto.UserResponse.class, CloudMapper::user);
    }
}
