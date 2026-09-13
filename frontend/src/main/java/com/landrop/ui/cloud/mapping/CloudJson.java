package com.landrop.ui.cloud.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.cloud.dto.Dto;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Strict wire decoding: absent fields and scalar coercion cannot silently become valid data. */
public final class CloudJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Map<Class<?>, Set<String>> NULLABLE = Map.of(
            Dto.UserResponse.class, Set.of("email", "avatarUrl"),
            Dto.WorkspaceResponse.class, Set.of("description"),
            Dto.WorkspaceMemberResponse.class, Set.of(),
            Dto.ChannelResponse.class, Set.of("description"),
            Dto.MessageResponse.class, Set.of("replyToId", "fileId"),
            Dto.FolderResponse.class, Set.of("parentId"),
            Dto.FileResponse.class, Set.of("folderId", "sha256"));

    private CloudJson() {}

    public static <T> Dto.ItemResponse<T> readItem(String json, Class<T> responseType) {
        JsonNode root = object(parse(json), "response");
        return new Dto.ItemResponse<>(decode(field(root, "data"), responseType));
    }

    public static <T> Dto.ListResponse<T> readList(String json, Class<T> responseType) {
        JsonNode root = object(parse(json), "response");
        JsonNode data = field(root, "data");
        if (!data.isArray()) {
            throw new IllegalArgumentException("data must be an array");
        }
        JsonNode page = object(field(root, "page"), "page");
        String nextCursor = string(field(page, "nextCursor"), "nextCursor", true);
        var items = new ArrayList<T>(data.size());
        for (JsonNode item : data) {
            items.add(decode(item, responseType));
        }
        return new Dto.ListResponse<>(items, new Dto.PageInfo(nextCursor));
    }

    public static String write(Object response) {
        if (response == null) {
            throw new IllegalArgumentException("response is required");
        }
        try {
            return JSON.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode response", e);
        }
    }

    private static JsonNode parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON response is required");
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed JSON response", e);
        }
    }

    private static <T> T decode(JsonNode node, Class<T> responseType) {
        Set<String> nullable = NULLABLE.get(responseType);
        if (nullable == null) {
            throw new IllegalArgumentException("Unsupported response type: " + responseType);
        }
        object(node, "data item");
        for (RecordComponent component : responseType.getRecordComponents()) {
            String name = component.getName();
            string(field(node, name), name, nullable.contains(name));
        }
        try {
            return JSON.treeToValue(node, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid " + responseType.getSimpleName(), e);
        }
    }

    private static JsonNode object(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return node;
    }

    private static JsonNode field(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
        return value;
    }

    private static String string(JsonNode node, String name, boolean nullable) {
        if (nullable && node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(name + " must be a string" + (nullable ? " or null" : ""));
        }
        return node.textValue();
    }
}
