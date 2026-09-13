package com.landrop.database.service;

import com.landrop.database.model.Message;
import com.landrop.database.repository.MessageRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MessageService {

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public MessageService() {
        this(new MessageRepository());
    }

    public Message sendMessage(UUID channelId, UUID userId, String content) throws SQLException {
        return sendReply(channelId, userId, null, content);
    }

    public Message sendReply(UUID channelId, UUID userId, UUID parentMessageId, String content) throws SQLException {
        if (channelId == null) {
            throw new IllegalArgumentException("Channel ID is required.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Message content cannot be empty.");
        }
        Message msg = new Message(channelId, userId, parentMessageId, content.trim());
        return messageRepository.create(msg);
    }

    public Optional<Message> getMessageById(UUID id) throws SQLException {
        return messageRepository.findById(id);
    }

    public List<Message> getChannelMessages(UUID channelId, int limit, int offset) throws SQLException {
        return messageRepository.findByChannelId(channelId, limit, offset);
    }

    public List<Message> getMessageReplies(UUID parentMessageId) throws SQLException {
        return messageRepository.findReplies(parentMessageId);
    }

    public boolean deleteMessage(UUID id) throws SQLException {
        return messageRepository.deleteById(id);
    }
}
