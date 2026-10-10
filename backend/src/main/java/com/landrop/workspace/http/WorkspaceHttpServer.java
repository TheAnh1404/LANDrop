package com.landrop.workspace.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.exception.AuthException;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.security.JwtClaims;
import com.landrop.database.model.WorkspaceRole;
import com.landrop.database.service.WorkspaceService;
import com.landrop.util.AppLogger;
import com.landrop.workspace.WorkspaceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.*;

/** Registers the Phase 16 workspace REST handlers on the existing auth HTTP server. */
public final class WorkspaceHttpServer {
    private static final String TAG = "WorkspaceHttpServer";
    private static final int MAX_BODY = 16 * 1024;

    private final AuthMiddleware auth;
    private final WorkspaceService service;
    private final com.landrop.chat.service.ChannelChatService channelService;
    private final com.landrop.file.server.FileHttpServer fileHttpServer;
    private final ObjectMapper mapper;

    public WorkspaceHttpServer(AuthMiddleware auth, WorkspaceService service, ObjectMapper mapper) {
        this(auth, service, new com.landrop.chat.service.ChannelChatService(), mapper);
    }

    public WorkspaceHttpServer(AuthMiddleware auth, WorkspaceService service,
                               com.landrop.chat.service.ChannelChatService channelService, ObjectMapper mapper) {
        this(auth, service, channelService, null, mapper);
    }

    public WorkspaceHttpServer(AuthMiddleware auth, WorkspaceService service,
                               com.landrop.chat.service.ChannelChatService channelService,
                               com.landrop.file.server.FileHttpServer fileHttpServer,
                               ObjectMapper mapper) {
        this.auth = Objects.requireNonNull(auth);
        this.service = Objects.requireNonNull(service);
        this.channelService = Objects.requireNonNull(channelService);
        this.fileHttpServer = fileHttpServer != null ? fileHttpServer
            : new com.landrop.file.server.FileHttpServer(auth, new com.landrop.file.service.FileManagementService(), new com.landrop.file.service.TransferCoordinationService(), mapper);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public void register(HttpServer server) {
        server.createContext("/api/workspaces", this::handle);
        server.createContext("/api/workspaces/", this::handle);
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { send(exchange, 204, null); return; }
        String path = exchange.getRequestURI().getPath();
        if (path.matches("^/api/workspaces/[0-9a-fA-F-]+/qr$")) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String tail = path.substring("/api/workspaces/".length());
                String[] parts = tail.split("/", -1);
                qrCode(exchange, uuid(parts[0]));
                return;
            }
            method(exchange, "GET");
            return;
        }
        try {
            JwtClaims claims = auth.authenticate(exchange);
            String base = "/api/workspaces";
            if (path.equals(base)) {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) create(exchange, claims.userId());
                else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) list(exchange, claims.userId());
                else method(exchange, "GET, POST");
                return;
            }
            String tail = path.substring((base + "/").length());
            String[] parts = tail.split("/", -1);
            if (parts.length == 1 && !parts[0].isBlank()) {
                UUID id = uuid(parts[0]);
                switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
                    case "GET" -> detail(exchange, claims.userId(), id);
                    case "PATCH" -> update(exchange, claims.userId(), id);
                    case "DELETE" -> { service.delete(claims.userId(), id); send(exchange, 204, null); }
                    default -> method(exchange, "GET, PATCH, DELETE");
                }
                return;
            }
            if (parts.length == 2 && "preview".equals(parts[1])) {
                UUID id = uuid(parts[0]);
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) preview(exchange, claims.userId(), id);
                else method(exchange, "GET");
                return;
            }
            if (parts.length == 2 && "join".equals(parts[1])) {
                UUID id = uuid(parts[0]);
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) join(exchange, claims.userId(), id);
                else method(exchange, "POST");
                return;
            }
            if (parts.length == 2 && "join-settings".equals(parts[1])) {
                UUID id = uuid(parts[0]);
                if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) updateJoinSettings(exchange, claims.userId(), id);
                else method(exchange, "PATCH");
                return;
            }
            if (parts.length == 2 && "members".equals(parts[1])) {
                UUID id = uuid(parts[0]);
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) members(exchange, claims.userId(), id);
                else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) addMember(exchange, claims.userId(), id);
                else method(exchange, "GET, POST");
                return;
            }
            if (parts.length == 3 && "members".equals(parts[1])) {
                UUID workspace = uuid(parts[0]);
                UUID target = uuid(parts[2]);
                if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) role(exchange, claims.userId(), workspace, target);
                else if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) { service.remove(claims.userId(), workspace, target); send(exchange, 204, null); }
                else method(exchange, "PATCH, DELETE");
                return;
            }
            if (parts.length == 2 && "leave".equals(parts[1]) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                service.leave(claims.userId(), uuid(parts[0])); send(exchange, 204, null); return;
            }
            if (parts.length == 2 && "channels".equals(parts[1])) {
                UUID id = uuid(parts[0]);
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) listChannels(exchange, claims.userId(), id);
                else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) createChannel(exchange, claims.userId(), id);
                else method(exchange, "GET, POST");
                return;
            }
            if (parts.length == 2 && "files".equals(parts[1])) {
                UUID id = uuid(parts[0]);
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) fileHttpServer.listWorkspaceFiles(exchange, claims.userId(), id);
                else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) fileHttpServer.createWorkspaceFile(exchange, claims.userId(), id);
                else method(exchange, "GET, POST");
                return;
            }
            if (parts.length == 2 && "folders".equals(parts[1])) {
                UUID id = uuid(parts[0]);
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) fileHttpServer.listWorkspaceFolders(exchange, claims.userId(), id);
                else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) fileHttpServer.createWorkspaceFolder(exchange, claims.userId(), id);
                else method(exchange, "GET, POST");
                return;
            }
            sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
        } catch (Exception error) {
            handleError(exchange, error);
        }
    }

    private void create(HttpExchange e, UUID user) throws IOException, SQLException {
        JsonNode body = body(e, Set.of("name", "description"));
        var result = service.create(user, text(body, "name", true), text(body, "description", false));
        send(e, 201, Map.of("data", workspace(result)));
    }

    private void list(HttpExchange e, UUID user) throws IOException, SQLException {
        PageArgs p = page(e); var result = service.list(user, p.limit, p.offset);
        send(e, 200, Map.of("data", result.items().stream().map(this::workspace).toList(), "pagination", pagination(result)));
    }

    private void detail(HttpExchange e, UUID user, UUID id) throws IOException, SQLException {
        send(e, 200, Map.of("data", workspace(service.get(user, id))));
    }

    private void update(HttpExchange e, UUID user, UUID id) throws IOException, SQLException {
        JsonNode body = body(e, Set.of("name", "description"));
        boolean hn = body.has("name"), hd = body.has("description");
        var result = service.update(user, id, text(body, "name", true), text(body, "description", false), hn, hd);
        send(e, 200, Map.of("data", workspace(result)));
    }

    private void members(HttpExchange e, UUID user, UUID id) throws IOException, SQLException {
        PageArgs p = page(e); var result = service.members(user, id, p.limit, p.offset);
        send(e, 200, Map.of("data", result.items().stream().map(this::member).toList(), "pagination", pagination(result)));
    }

    private void addMember(HttpExchange e, UUID user, UUID id) throws IOException, SQLException {
        JsonNode body = body(e, Set.of("email", "userId", "username"));
        String email = body.hasNonNull("email") ? text(body, "email", false) : null;
        String userIdStr = body.hasNonNull("userId") ? text(body, "userId", false) : null;
        String username = body.hasNonNull("username") ? text(body, "username", false) : null;
        UUID targetUserId = null;
        if (userIdStr != null && !userIdStr.isBlank()) {
            try { targetUserId = UUID.fromString(userIdStr); } catch (Exception ignored) {}
        }
        send(e, 201, Map.of("data", member(service.addMember(user, id, email, targetUserId, username))));
    }

    private void role(HttpExchange e, UUID user, UUID workspace, UUID target) throws IOException, SQLException {
        JsonNode body = body(e, Set.of("role"));
        WorkspaceRole role;
        try { role = WorkspaceRole.valueOf(text(body, "role", true).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new WorkspaceException(400, "INVALID_REQUEST", "Role must be ADMIN or MEMBER."); }
        send(e, 200, Map.of("data", member(service.role(user, workspace, target, role))));
    }

    private void listChannels(HttpExchange e, UUID user, UUID workspaceId) throws IOException, SQLException {
        var channels = channelService.listChannels(user, workspaceId);
        send(e, 200, Map.of("data", channels));
    }

    private void createChannel(HttpExchange e, UUID user, UUID workspaceId) throws IOException, SQLException {
        JsonNode body = body(e, Set.of("name", "description", "topic"));
        String name = text(body, "name", true);
        String desc = text(body, "description", false);
        if (desc == null) desc = text(body, "topic", false);
        var created = channelService.createChannel(user, workspaceId, name, desc);
        send(e, 201, Map.of("data", created));
    }

    private void preview(HttpExchange e, UUID user, UUID id) throws IOException, SQLException {
        var p = service.getPreview(user, id);
        Map<String, Object> data = map(
            "id", p.id(),
            "name", p.name(),
            "slug", p.slug(),
            "description", p.description(),
            "ownerName", p.ownerName(),
            "membersCount", p.membersCount(),
            "joinPolicy", p.joinPolicy(),
            "hasPassword", p.hasPassword(),
            "isMember", p.isMember(),
            "role", p.role() != null ? p.role().name() : null
        );
        send(e, 200, Map.of("data", data));
    }

    private void join(HttpExchange e, UUID user, UUID id) throws IOException, SQLException {
        JsonNode body = body(e, Set.of("password"));
        String password = body.hasNonNull("password") ? text(body, "password", false) : null;
        var member = service.join(user, id, password);
        send(e, 200, Map.of("data", member(member)));
    }

    private void updateJoinSettings(HttpExchange e, UUID user, UUID id) throws IOException, SQLException {
        JsonNode body = body(e, Set.of("joinPolicy", "password"));
        String policy = body.hasNonNull("joinPolicy") ? text(body, "joinPolicy", false) : null;
        String password = body.hasNonNull("password") ? text(body, "password", false) : null;
        var view = service.updateJoinSettings(user, id, policy, password);
        send(e, 200, Map.of("data", workspace(view)));
    }

    private void qrCode(HttpExchange e, UUID id) throws IOException {
        String origin = e.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) {
            String host = e.getRequestHeaders().getFirst("Host");
            origin = host != null ? "http://" + host : "http://localhost:3000";
        }
        String joinUrl = origin + "/w/" + id;
        try {
            java.awt.image.BufferedImage qrImage = com.landrop.server.desktop.QrCodeGenerator.generateQrCode(joinUrl, 320, java.awt.Color.BLACK, java.awt.Color.WHITE);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(qrImage, "png", baos);
            byte[] bytes = baos.toByteArray();
            e.getResponseHeaders().set("Content-Type", "image/png");
            e.getResponseHeaders().set("Cache-Control", "public, max-age=60");
            e.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = e.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception ex) {
            sendError(e, 500, "QR_GENERATION_FAILED", "Không thể tạo mã QR cho workspace.");
        }
    }

    private Map<String,Object> workspace(WorkspaceService.WorkspaceView v) {
        var w = v.workspace();
        boolean hasPassword = w.joinPasswordHash() != null && !w.joinPasswordHash().isBlank();
        return map("id", w.id(), "name", w.name(), "slug", w.slug(), "description", w.description(),
            "ownerId", w.ownerId(), "role", v.role(), "joinPolicy", w.joinPolicy() != null ? w.joinPolicy() : "INVITE_ONLY",
            "hasPassword", hasPassword, "createdAt", w.createdAt(), "updatedAt", w.updatedAt());
    }
    private Map<String,Object> member(WorkspaceService.MemberView v) {
        return map("id", v.member().id(), "workspaceId", v.member().workspaceId(), "userId", v.member().userId(),
                "role", v.member().role(), "joinedAt", v.member().joinedAt(),
                "user", map("id", v.member().userId(), "username", v.username(),
                        "displayName", v.displayName(), "avatarUrl", v.avatarUrl()));
    }
    private static Map<String,Object> map(Object... values) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2) m.put((String)values[i],values[i+1]); return m; }
    private static Map<String,Object> pagination(WorkspaceService.Page<?> p) { return map("limit",p.limit(),"offset",p.offset(),"hasMore",p.hasMore()); }

    private JsonNode body(HttpExchange e, Set<String> allowed) throws IOException {
        String type=e.getRequestHeaders().getFirst("Content-Type");
        if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json")) throw new WorkspaceException(415,"UNSUPPORTED_MEDIA_TYPE","Content-Type must be application/json.");
        byte[] bytes; try(InputStream in=e.getRequestBody()){bytes=in.readNBytes(MAX_BODY+1);} if(bytes.length>MAX_BODY) throw new WorkspaceException(413,"PAYLOAD_TOO_LARGE","Request body is too large.");
        if(bytes.length==0) throw new WorkspaceException(400,"INVALID_REQUEST","Request body cannot be empty.");
        try(JsonParser parser=mapper.getFactory().createParser(bytes)){ JsonNode n=mapper.readTree(parser); if(n==null||!n.isObject()) throw new WorkspaceException(400,"INVALID_REQUEST","Request body must be a JSON object."); n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) throw new IllegalArgumentException("unknown");}); return n; }
        catch(WorkspaceException x){throw x;} catch(Exception x){throw new WorkspaceException(400,"INVALID_REQUEST","Request body is invalid JSON.");}
    }
    private static String text(JsonNode n,String field,boolean required){JsonNode v=n.get(field);if(v==null||v.isNull()){if(required)throw new WorkspaceException(400,"INVALID_REQUEST",field+" is required.");return null;}if(!v.isTextual())throw new WorkspaceException(400,"INVALID_REQUEST",field+" must be a string.");return v.asText();}
    private record PageArgs(int limit,int offset){}
    private static PageArgs page(HttpExchange e){Set<String> allowed=Set.of("limit","offset");Map<String,String> q=new HashMap<>();String raw=e.getRequestURI().getRawQuery();if(raw!=null&&!raw.isBlank())for(String part:raw.split("&")){String[] kv=part.split("=",2);if(kv.length!=2||!allowed.contains(kv[0]))throw new WorkspaceException(400,"INVALID_REQUEST","Unsupported query parameter.");q.put(kv[0],kv[1]);}try{return new PageArgs(q.containsKey("limit")?Integer.parseInt(q.get("limit")):50,q.containsKey("offset")?Integer.parseInt(q.get("offset")):0);}catch(NumberFormatException x){throw new WorkspaceException(400,"INVALID_REQUEST","Pagination values must be integers.");}}
    private static UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equals(value.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();return id;}catch(Exception x){throw new WorkspaceException(400,"INVALID_REQUEST","Invalid UUID.");}}
    private void method(HttpExchange e,String allowed)throws IOException{e.getResponseHeaders().set("Allow",allowed);sendError(e,405,"METHOD_NOT_ALLOWED","Allowed methods: "+allowed);}
    private void send(HttpExchange e,int status,Object body)throws IOException{if(body==null){e.sendResponseHeaders(status,-1);e.close();return;}byte[] bytes=mapper.writeValueAsBytes(body);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(status,bytes.length);try(OutputStream out=e.getResponseBody()){out.write(bytes);}}
    private void sendError(HttpExchange e,int status,String code,String message)throws IOException{send(e,status,Map.of("error",Map.of("code",code,"message",message)));}
    private void handleError(HttpExchange e,Exception x)throws IOException{if(x instanceof com.landrop.file.exception.FileException f){sendError(e,f.getStatusCode(),f.code(),f.getMessage());return;}if(x instanceof com.landrop.chat.service.ChatException c){sendError(e,c.getStatus(),c.code(),c.getMessage());return;}if(x instanceof WorkspaceException w){sendError(e,w.getStatusCode(),w.code(),w.getMessage());return;}if(x instanceof AuthException a){sendError(e,a.getStatusCode(),"UNAUTHENTICATED","Authentication required.");return;}if(x instanceof SQLException){AppLogger.error(TAG,"Workspace database request failed",x);sendError(e,503,"DATABASE_UNAVAILABLE","Workspace data is temporarily unavailable.");return;}AppLogger.error(TAG,"Workspace request failed",x);sendError(e,500,"INTERNAL_ERROR","The request could not be completed.");}
}
