package com.landrop.ui.models;

import com.landrop.chat.ChatMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Presentation wrapper for chat messages, providing formatted timestamp and alignment info.
 */
public record UIMessage(
        long requestId,
        String sender,
        String content,
        long timestamp,
        boolean isLocal
) {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    public String formattedTime() {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    public static UIMessage from(ChatMessage msg, boolean isLocal) {
        return new UIMessage(msg.requestId(), msg.sender(), msg.content(), msg.timestamp(), isLocal);
    }
}
