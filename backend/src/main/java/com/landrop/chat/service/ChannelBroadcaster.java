package com.landrop.chat.service;

import java.util.UUID;

/**
 * Realtime event broadcaster for workspace/channel rooms.
 */
@FunctionalInterface
public interface ChannelBroadcaster {
    void broadcast(UUID channelId, Object event);
}
