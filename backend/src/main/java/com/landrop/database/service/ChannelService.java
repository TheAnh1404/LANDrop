package com.landrop.database.service;

import com.landrop.database.model.Channel;
import com.landrop.database.repository.ChannelRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ChannelService {

    private final ChannelRepository channelRepository;

    public ChannelService(ChannelRepository channelRepository) {
        this.channelRepository = channelRepository;
    }

    public ChannelService() {
        this(new ChannelRepository());
    }

    public Channel createChannel(UUID workspaceId, String name, String topic, boolean isPrivate) throws SQLException {
        if (workspaceId == null) {
            throw new IllegalArgumentException("Workspace ID is required.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Channel name cannot be empty.");
        }
        Channel channel = new Channel(workspaceId, name.trim().toLowerCase(), topic, isPrivate);
        return channelRepository.create(channel);
    }

    public Optional<Channel> getChannelById(UUID id) throws SQLException {
        return channelRepository.findById(id);
    }

    public Optional<Channel> getChannelByName(UUID workspaceId, String name) throws SQLException {
        return channelRepository.findByName(workspaceId, name.trim().toLowerCase());
    }

    public List<Channel> listWorkspaceChannels(UUID workspaceId) throws SQLException {
        return channelRepository.findByWorkspaceId(workspaceId);
    }

    public boolean deleteChannel(UUID id) throws SQLException {
        return channelRepository.deleteById(id);
    }
}
