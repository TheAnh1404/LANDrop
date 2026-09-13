package com.landrop.ui.cloud.api;

import com.landrop.ui.cloud.mapping.CloudJson;
import com.landrop.ui.cloud.mapping.CloudMapper;
import com.landrop.ui.cloud.model.Domain;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

final class RequestClient {
    private final ApiTransport transport;

    RequestClient(ApiTransport transport) { this.transport = Objects.requireNonNull(transport); }

    <S, T> CompletableFuture<T> item(String path, Class<S> dto, Function<S, T> mapper) {
        return transport.get(path, Map.of()).thenApply(json -> decode(
                () -> mapper.apply(CloudJson.readItem(json, dto).data())));
    }

    <S, T> CompletableFuture<Domain.Page<T>> list(
            String path, PageRequest page, Class<S> dto, Function<S, T> mapper) {
        Objects.requireNonNull(page, "page");
        return transport.get(path, page.query()).thenApply(json -> decode(
                () -> CloudMapper.page(CloudJson.readList(json, dto), mapper)));
    }

    private static <T> T decode(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(0, "INVALID_RESPONSE", "API response does not match the Phase 14 contract", e);
        }
    }
}
