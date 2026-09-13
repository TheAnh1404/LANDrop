package com.landrop.ui.cloud.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Read-only JSON boundary shared by HTTP and development fixtures. */
@FunctionalInterface
public interface ApiTransport {
    CompletableFuture<String> get(String path, Map<String, String> query);

    /** Optional headers are used by authenticated Phase 15 requests. */
    default CompletableFuture<String> get(String path, Map<String, String> query,
                                         Map<String, String> headers) {
        return get(path, query);
    }
}
