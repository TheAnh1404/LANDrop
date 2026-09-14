package com.landrop.realtime.cli;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Native Java integration harness. Product identity is always fetched from /auth/me. */
public final class RealtimeClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI auth;
    private final URI endpoint;
    private final String device;
    private final Path sessionFile;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> pending = new ConcurrentHashMap<>();
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
    private String accessToken;
    private volatile WebSocket socket;

    private RealtimeClient(String[] args) {
        auth = URI.create(args[0].replaceAll("/+$", ""));
        endpoint = URI.create(args[1].replaceAll("/+$", "") + "/api/realtime/channels/" + UUID.fromString(args[2]));
        if (!Set.of("http", "https").contains(auth.getScheme()) || !Set.of("ws", "wss").contains(endpoint.getScheme())
                || auth.getUserInfo() != null || endpoint.getUserInfo() != null) throw new IllegalArgumentException("Invalid server URLs");
        device = args[3]; sessionFile = Path.of(args[4]).toAbsolutePath();
    }
    private JsonNode call(String operation, Object payload, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(auth + "/api/auth/" + operation)).timeout(Duration.ofSeconds(15));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (payload == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() > 299)
            throw new IOException("Authentication failed (HTTP " + response.statusCode() + ").");
        return JSON.readTree(response.body());
    }
    private void remember(JsonNode tokens) throws Exception {
        accessToken = tokens.path("accessToken").asText();
        String refresh = tokens.path("refreshToken").asText();
        if (accessToken.isBlank() || refresh.isBlank()) throw new IOException("Invalid authentication response.");
        // One file per device and backend; never store passwords or access tokens.
        Path temporary = Files.createTempFile(sessionFile.getParent(), ".landrop-session-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, refresh);
            try { Files.move(temporary, sessionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, sessionFile, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private void refresh() throws Exception {
        remember(call("refresh", Map.of("refreshToken", Files.readString(sessionFile).trim()), null));
        identify();
    }
    private void identify() throws Exception {
        JsonNode me = call("me", null, accessToken);
        System.out.println("Authenticated userId=" + me.path("id").asText() + " device=" + device);
    }
    private void authenticate() throws Exception {
        if (Files.exists(sessionFile)) {
            try { refresh(); return; }
            catch (IOException e) { System.out.println("Could not restore session; sign in again."); }
        }
        System.out.print("Email or username: ");
        String identifier = input.readLine();
        Console console = System.console();
        String password;
        if (console != null) password = new String(console.readPassword("Password: "));
        else { System.out.print("Password (terminal input may be visible): "); password = input.readLine(); }
        remember(call("login", Map.of("emailOrUsername", identifier, "password", password), null));
        identify();
    }
    private void connect() {
        var listener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            @Override public void onOpen(WebSocket ws) { ws.request(1); }
            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    try {
                        JsonNode event = JSON.readTree(buffer.toString());
                        switch (event.path("type").asText()) {
                            case "message.created" -> {
                                JsonNode message = event.path("message");
                                pending.remove(message.path("clientMessageId").asText());
                                display(message);
                            }
                            case "history" -> {
                                for (JsonNode message : event.path("page").path("messages")) display(message);
                                System.out.println("History cursor: " + event.path("page").path("nextCursor"));
                            }
                            case "error" -> System.out.println("Chat error: " + event.path("code").asText());
                            case "connected" -> System.out.println("Socket connected: " + event.path("socketId").asText());
                            default -> { }
                        }
                    } catch (Exception e) { System.out.println("Invalid event received."); }
                    buffer.setLength(0);
                }
                ws.request(1);
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                System.out.println("Socket closed (" + code + "). Use /reconnect to refresh access and recover history.");
                return CompletableFuture.completedFuture(null);
            }
            @Override public void onError(WebSocket ws, Throwable error) {
                System.out.println("Connection lost. Use /reconnect.");
            }
        };
        socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken).header("X-Device-Label", device)
                .buildAsync(endpoint, listener).join();
        history(null);
    }
    private void display(JsonNode message) {
        if (seen.add(message.path("id").asText()))
            System.out.println(message.path("id").asText() + " senderId=" + message.path("senderId").asText()
                    + " " + message.path("content").asText().replace("\n", " ").replace("\r", " "));
    }
    private void send(Object command) {
        try { socket.sendText(JSON.writeValueAsString(command), true).join(); }
        catch (Exception e) { System.out.println("Send failed. Use /reconnect then /retry."); }
    }
    private void history(String cursor) {
        var request = new LinkedHashMap<String, Object>();
        request.put("type", "history"); request.put("requestId", UUID.randomUUID().toString());
        request.put("limit", 50); if (cursor != null) request.put("before", cursor);
        send(request);
    }
    private void run() throws Exception {
        authenticate(); connect();
        System.out.println("Type a message; /history [cursor], /retry, /reconnect, /logout, /quit.");
        try {
            for (String line; (line = input.readLine()) != null;) {
                if (line.equals("/quit")) break;
                if (line.equals("/logout")) {
                    try { call("logout", Map.of("refreshToken", Files.readString(sessionFile).trim()), accessToken); }
                    finally { Files.deleteIfExists(sessionFile); }
                    break;
                }
                if (line.equals("/reconnect")) {
                    if (socket != null) socket.abort();
                    refresh(); connect(); continue;
                }
                if (line.equals("/retry")) { for (var request : pending.values()) send(request); continue; }
                if (line.startsWith("/history")) { history(line.length() > 9 ? line.substring(9).trim() : null); continue; }
                String id = UUID.randomUUID().toString();
                var request = Map.of("type", "message.send", "requestId", UUID.randomUUID().toString(),
                        "clientMessageId", id, "content", line);
                pending.put(id, request); send(request);
            }
        } finally { if (socket != null) socket.sendClose(1000, "Client exited").join(); }
    }
    public static void main(String[] args) {
        if (args.length != 5) {
            System.out.println("Usage: RealtimeClient <http(s)://host:authPort> <ws(s)://host:wsPort> <channel UUID> <deviceLabel> <sessionFile>");
            return;
        }
        try { new RealtimeClient(args).run(); }
        catch (Exception e) { System.err.println("Client stopped. Check server URLs, credentials, channel membership and session file."); }
    }
}
