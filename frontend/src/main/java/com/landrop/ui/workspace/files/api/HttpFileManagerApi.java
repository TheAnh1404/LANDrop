package com.landrop.ui.workspace.files.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.api.ApiClient;
import com.landrop.ui.cloud.model.Domain;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** HTTP adapter for the Phase 18A metadata + LAN-transfer-request API. */
public final class HttpFileManagerApi implements FileManagerApi {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AuthStateService auth; private final ApiClient client;
    public HttpFileManagerApi(URI apiBase, AuthStateService auth) { this.auth = auth; this.client = new ApiClient(apiBase, auth); }
    @Override public CompletableFuture<List<Domain.Folder>> listFolders(String ws, String parent) { return request("GET", "workspaces/" + e(ws) + "/folders" + query("parentId", parent), null).thenApply(j -> array(j, this::folder)); }
    @Override public CompletableFuture<List<Domain.File>> listFiles(String ws, String folder) { return request("GET", "workspaces/" + e(ws) + "/files" + query("folderId", folder), null).thenApply(j -> array(j, this::file)); }
    @Override public CompletableFuture<Domain.Folder> createFolder(String ws, String parent, String name) { return request("POST", "workspaces/" + e(ws) + "/folders", json(map("name",name,"parentId",parent))).thenApply(j -> folder(data(j))); }
    @Override public CompletableFuture<Domain.Folder> updateFolder(String id, String name, String parent) { return request("PATCH", "folders/" + e(id), json(map("name",name,"parentId",parent))).thenApply(j -> folder(data(j))); }
    @Override public CompletableFuture<Void> deleteFolder(String id) { return request("DELETE", "folders/" + e(id), null).thenApply(x -> null); }
    @Override public CompletableFuture<Domain.File> updateFile(String id, String name, String folder) { return request("PATCH", "files/" + e(id), json(map("name",name,"folderId",folder))).thenApply(j -> file(data(j))); }
    @Override public CompletableFuture<Void> deleteFile(String id) { return request("DELETE", "files/" + e(id), null).thenApply(x -> null); }
    @Override public CompletableFuture<Domain.File> registerFile(String ws, String folder, Path path) {
        try { String mime = Files.probeContentType(path);
            Map<String,Object> metadata = map("name",path.getFileName().toString(),"folderId",folder,"size",Long.toString(Files.size(path)),"mimeType",mime == null ? "application/octet-stream" : mime,"storageType","LAN_ONLY");
            return request("POST", "workspaces/" + e(ws) + "/files", json(metadata)).thenApply(j -> file(data(j)));
        } catch (Exception ex) { return CompletableFuture.failedFuture(ex); }
    }
    @Override public CompletableFuture<List<Domain.Device>> listDevices(String ws, String user) { return request("GET", "users/" + e(user) + "/devices", null).thenApply(j -> array(j, this::device)); }
    @Override public CompletableFuture<Void> requestTransfer(String file, String user, String device) { return request("POST", "transfers", json(map("fileId",file,"receiverUserId",user,"receiverDeviceId",device))).thenApply(x -> null); }
    private CompletableFuture<String> request(String method, String path, String body) { return client.request(method, path, body); }
    private static <T> List<T> array(String json, java.util.function.Function<JsonNode,T> map) { JsonNode d=data(json); List<T> out=new ArrayList<>(); if(d.isArray()) for(JsonNode n:d) out.add(map.apply(n)); return List.copyOf(out); }
    private static JsonNode root(String s) { try{return JSON.readTree(s);}catch(Exception e){throw new ApiException(0,"INVALID_RESPONSE","File API returned invalid JSON",e);} }
    private static JsonNode data(String s) { JsonNode r=root(s); return r.has("data")?r.get("data"):r; }
    private static ApiException error(int status,String json) { try {JsonNode e=root(json).path("error");return new ApiException(status,t(e,"code")==null?"HTTP_ERROR":t(e,"code"),t(e,"message")==null?"File request failed.":t(e,"message"));}catch(Exception x){return new ApiException(status,"HTTP_ERROR","File request failed with HTTP "+status);} }
    private static String json(Object value) { try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Cannot encode file request",e);} }
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]); return m; }
    private static String e(String s){return URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8);} private static String query(String key,String val){return val==null?"":"?"+key+"="+e(val);} private static String t(JsonNode n,String k){return n!=null&&n.hasNonNull(k)?n.get(k).asText():null;}
    private static Instant at(JsonNode n,String... keys){for(String k:keys){String s=t(n,k);if(s!=null)try{return Instant.parse(s);}catch(Exception ignored){}}return Instant.now();}
    private Domain.Folder folder(JsonNode n){String parent=t(n,"parentId");if(parent==null)parent=t(n,"parentFolderId");return new Domain.Folder(t(n,"id"),t(n,"workspaceId"),parent,t(n,"name"),t(n,"createdById")==null?auth.user().id():t(n,"createdById"),at(n,"createdAt"),at(n,"updatedAt","createdAt"));}
    private Domain.File file(JsonNode n){String size=t(n,"sizeBytes");if(size==null&&n.hasNonNull("size"))size=n.get("size").asText();String uploader=t(n,"uploadedById");if(uploader==null)uploader=t(n,"uploaderId");String hash=t(n,"sha256");if(hash==null)hash=t(n,"hash");return new Domain.File(t(n,"id"),t(n,"workspaceId"),t(n,"folderId"),uploader==null?auth.user().id():uploader,t(n,"name"),t(n,"mimeType"),new java.math.BigInteger(size==null?"0":size),hash,at(n,"createdAt"),at(n,"updatedAt","createdAt"));}
    private Domain.Device device(JsonNode n){String label=t(n,"label");if(label==null)label=t(n,"deviceName");String id=t(n,"id");if(id==null)id=t(n,"deviceId");return new Domain.Device(id,t(n,"userId"),label==null?"Device":label,n.path("lanAvailable").asBoolean(n.path("isOnline").asBoolean(n.path("reachable").asBoolean(false))));}
}
