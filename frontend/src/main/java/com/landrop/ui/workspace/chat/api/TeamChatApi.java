package com.landrop.ui.workspace.chat.api;

import com.landrop.ui.cloud.model.Domain;

import java.util.concurrent.CompletableFuture;

/** Authenticated HTTP contract for the Phase 17 workspace chat surface. */
public interface TeamChatApi {
    record MessagePage(java.util.List<Domain.Message> messages, String nextCursor) { }

    CompletableFuture<java.util.List<Domain.Channel>> listChannels(String workspaceId);
    CompletableFuture<Domain.Channel> createChannel(String workspaceId, String name, String description);
    CompletableFuture<MessagePage> listMessages(String channelId, int limit, String before);
    CompletableFuture<Domain.Message> sendMessage(String channelId, String content, String replyToId);
    CompletableFuture<Domain.Message> updateMessage(String messageId, String content);
    CompletableFuture<Domain.Message> deleteMessage(String messageId);
}
