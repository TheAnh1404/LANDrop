package com.landrop.chat;

import com.landrop.peer.ConnectionListener;
import com.landrop.peer.PeerConnection;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service managing peer-to-peer chat operations.
 * <p>
 * Decouples chat business rules and message history from socket/transport details.
 * Supports asynchronous bidirectional messaging, PING/PONG latency probes, and
 * event notification for UI subscribers (CLI, JavaFX).
 */
public class ChatService implements AutoCloseable {

    private final PeerConnection connection;
    private final String localSender;
    private final ChatHistory history;
    private final List<ChatListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Constructs a ChatService using the provided connection, local sender name, and a new ChatHistory.
     *
     * @param connection the active PeerConnection
     * @param localSender the local user/peer identifier
     */
    public ChatService(PeerConnection connection, String localSender) {
        this(connection, localSender, new ChatHistory());
    }

    /**
     * Constructs a ChatService with a custom ChatHistory store.
     *
     * @param connection the active PeerConnection
     * @param localSender the local user/peer identifier
     * @param history the ChatHistory repository
     */
    public ChatService(PeerConnection connection, String localSender, ChatHistory history) {
        this.connection = Objects.requireNonNull(connection, "PeerConnection cannot be null");
        this.localSender = (localSender != null && !localSender.isBlank()) ? localSender : "You";
        this.history = Objects.requireNonNull(history, "ChatHistory cannot be null");

        // Register custom handler for CHAT_MESSAGE and CLIPBOARD_SHARE packets
        this.connection.registerHandler(MessageType.CHAT_MESSAGE, this::handleIncomingChatPacket);
        this.connection.registerHandler(MessageType.CLIPBOARD_SHARE, this::handleIncomingClipboardPacket);

        // Attach connection listener for lifecycle bridging
        this.connection.addListener(new ConnectionListener() {
            @Override
            public void onDisconnected() {
                notifyDisconnected();
            }

            @Override
            public void onError(Exception error) {
                notifyError(error);
            }
        });
    }

    /**
     * Sends a chat message to the remote peer asynchronously over the underlying TCP connection.
     *
     * @param content the text payload of the chat message (must not be null or empty/whitespace)
     * @return the newly created and recorded ChatMessage
     * @throws IllegalArgumentException if content is null or blank
     * @throws IOException if the message cannot be sent due to network or connection errors
     */
    public ChatMessage sendMessage(String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Chat message content cannot be empty or whitespace only");
        }

        String trimmed = content.trim();
        long requestId = connection.getRequestIdGenerator().nextId();
        ChatMessage message = new ChatMessage(requestId, localSender, trimmed, System.currentTimeMillis());

        Packet packet = Packet.text(MessageType.CHAT_MESSAGE, requestId, trimmed);
        connection.send(packet);

        history.add(message);
        notifyMessageSent(message);

        return message;
    }

    /**
     * Handles an incoming CHAT_MESSAGE packet from the remote peer.
     * Chat messages are events and do NOT trigger an automatic network response.
     *
     * @param packet the received CHAT_MESSAGE packet
     */
    private void handleIncomingChatPacket(Packet packet) {
        String remoteSender = connection.getRemotePeerName();
        if (remoteSender == null || remoteSender.isBlank()) {
            remoteSender = "Peer";
        }

        ChatMessage message = new ChatMessage(
                packet.getRequestId(),
                remoteSender,
                packet.payloadAsString(),
                System.currentTimeMillis()
        );

        history.add(message);
        notifyMessageReceived(message);
    }

    /**
     * Sends a clipboard text snippet to the remote peer.
     *
     * @param text the text payload to share
     * @return the created ClipboardMessage
     * @throws IOException if network transmission fails
     */
    public com.landrop.protocol.ClipboardMessage sendClipboard(String text) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Clipboard content cannot be empty");
        }
        long requestId = connection.getRequestIdGenerator().nextId();
        com.landrop.protocol.ClipboardMessage clip = new com.landrop.protocol.ClipboardMessage(requestId, localSender, text.trim());
        Packet packet = Packet.of(MessageType.CLIPBOARD_SHARE, requestId, clip.encodePayload());
        connection.send(packet);
        return clip;
    }

    private void handleIncomingClipboardPacket(Packet packet) {
        try {
            com.landrop.protocol.ClipboardMessage clip = com.landrop.protocol.ClipboardMessage.decodePayload(packet.getRequestId(), packet.getPayload());
            for (ChatListener listener : listeners) {
                try {
                    listener.onClipboardReceived(clip);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            System.err.println("[ChatService] Failed to decode incoming clipboard packet: " + e.getMessage());
        }
    }

    /**
     * Triggers an asynchronous connection latency probe via PING.
     *
     * @return CompletableFuture completing with round-trip latency in milliseconds
     * @throws IOException if network transmission fails
     */
    public CompletableFuture<Double> ping() throws IOException {
        return connection.ping();
    }

    /**
     * Adds a listener for chat events.
     *
     * @param listener the ChatListener to register
     */
    public void addListener(ChatListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a registered chat listener.
     *
     * @param listener the ChatListener to remove
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }

    /**
     * Checks if the underlying connection is currently active.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return connection.isConnected();
    }

    public PeerConnection getConnection() {
        return connection;
    }

    public ChatHistory getHistory() {
        return history;
    }

    public String getLocalSender() {
        return localSender;
    }

    public String getRemotePeerName() {
        return connection.getRemotePeerName();
    }

    private void notifyMessageReceived(ChatMessage message) {
        for (ChatListener listener : listeners) {
            try {
                listener.onMessageReceived(message);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMessageSent(ChatMessage message) {
        for (ChatListener listener : listeners) {
            try {
                listener.onMessageSent(message);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyError(Exception error) {
        for (ChatListener listener : listeners) {
            try {
                listener.onChatError(error);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyDisconnected() {
        for (ChatListener listener : listeners) {
            try {
                listener.onChatDisconnected();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        connection.close();
    }
}
