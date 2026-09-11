package com.landrop.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI presentation and user interaction layer for LANDrop real-time chat.
 * <p>
 * Handles standard input on the main interaction thread while asynchronously
 * presenting incoming messages and notifications received from the background reader thread.
 */
public class ChatCli implements ChatListener {

    private final ChatService chatService;
    private final BufferedReader reader;
    private final PrintStream out;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Constructs a ChatCli using standard system I/O (System.in and System.out).
     *
     * @param chatService the underlying ChatService
     */
    public ChatCli(ChatService chatService) {
        this(chatService, System.in, System.out);
    }

    /**
     * Constructs a ChatCli with custom input and output streams for testing or piping.
     *
     * @param chatService the underlying ChatService
     * @param inputStream the input stream for reading user commands and messages
     * @param outputStream the output stream for rendering chat messages and system prompts
     */
    public ChatCli(ChatService chatService, InputStream inputStream, PrintStream outputStream) {
        this.chatService = Objects.requireNonNull(chatService, "ChatService cannot be null");
        this.reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(inputStream, "InputStream cannot be null"), StandardCharsets.UTF_8));
        this.out = Objects.requireNonNull(outputStream, "PrintStream cannot be null");
    }

    /**
     * Starts the CLI interactive loop.
     * Binds chat listener, prints welcome headers, and consumes user input until disconnect or /quit.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        chatService.addListener(this);

        printBanner();

        try {
            String line;
            while (running.get() && chatService.isConnected() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue; // Ignore blank lines
                }

                if (line.startsWith("/")) {
                    handleCommand(line);
                    if (!running.get()) {
                        break;
                    }
                } else {
                    handleChatMessage(line);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                synchronized (out) {
                    out.println("I/O error reading input: " + e.getMessage());
                }
            }
        } finally {
            stop();
        }
    }

    private void printBanner() {
        synchronized (out) {
            out.println("=================================");
            out.println("LANDrop - Peer Chat");
            out.println("=================================");
            out.println();
            out.println("Connected to " + chatService.getRemotePeerName());
            out.println();
            out.println("Type a message and press Enter.");
            out.println("Type /quit to disconnect.");
            out.println("Type /ping to test connection.");
            out.println("Type /help for help.");
            out.println();
        }
    }

    private void handleCommand(String commandLine) {
        String[] parts = commandLine.split("\\s+", 2);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "/quit" -> {
                synchronized (out) {
                    out.println("Disconnecting...");
                }
                stop();
            }
            case "/ping" -> executePing();
            case "/help" -> printHelp();
            default -> {
                synchronized (out) {
                    out.println("Unknown command: " + command + ". Type /help for available commands.");
                    out.println();
                }
            }
        }
    }

    private void executePing() {
        try {
            chatService.ping().thenAccept(latencyMs -> {
                synchronized (out) {
                    out.printf("[PONG] %.2f ms%n%n", latencyMs);
                }
            }).exceptionally(ex -> {
                synchronized (out) {
                    out.println("[PING Failed] " + ex.getMessage());
                    out.println();
                }
                return null;
            });
        } catch (IOException e) {
            synchronized (out) {
                out.println("[PING Failed] Unable to send ping: " + e.getMessage());
                out.println();
            }
        }
    }

    private void printHelp() {
        synchronized (out) {
            out.println("Available commands:");
            out.println("  /ping - Test connection latency");
            out.println("  /help - Show this help message");
            out.println("  /quit - Disconnect and exit chat");
            out.println();
        }
    }

    private void handleChatMessage(String content) {
        try {
            chatService.sendMessage(content);
        } catch (IllegalArgumentException e) {
            synchronized (out) {
                out.println("[Error] " + e.getMessage());
                out.println();
            }
        } catch (IOException e) {
            synchronized (out) {
                out.println("[Error] Failed to send message: " + e.getMessage());
                out.println();
            }
        }
    }

    @Override
    public void onMessageReceived(ChatMessage message) {
        synchronized (out) {
            out.println("[" + message.sender() + "] " + message.content());
            out.println();
        }
    }

    @Override
    public void onMessageSent(ChatMessage message) {
        synchronized (out) {
            out.println("You:");
            out.println(message.content());
            out.println();
        }
    }

    @Override
    public void onChatDisconnected() {
        synchronized (out) {
            out.println("Peer disconnected.");
            out.println();
        }
        running.set(false);
        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onChatError(Exception error) {
        synchronized (out) {
            out.println("[Network Error] " + error.getMessage());
            out.println();
        }
    }

    /**
     * Gracefully stops the CLI and closes the chat service.
     */
    public void stop() {
        running.set(false);
        chatService.removeListener(this);
        chatService.close();
    }

    public boolean isRunning() {
        return running.get();
    }
}
