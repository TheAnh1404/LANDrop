package com.landrop.ui.auth.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.auth.dto.AuthDto;

/** Strict authentication JSON codec. Successful responses use the shared {data: ...} envelope. */
public final class AuthJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private AuthJson() { }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to encode authentication request", e);
        }
    }

    public static AuthDto.AuthResponse readAuthResponse(String json) {
        JsonNode data = data(json);
        JsonNode user = data.get("user");
        if (user == null || !user.isObject()) {
            throw new IllegalArgumentException("user must be an object");
        }
        return new AuthDto.AuthResponse(
                requiredString(data, "accessToken"), requiredString(data, "refreshToken"),
                requiredString(data, "tokenType"), requiredPositiveLong(data, "expiresIn"), user(user));
    }

    public static AuthDto.RefreshResponse readRefreshResponse(String json) {
        JsonNode data = data(json);
        return new AuthDto.RefreshResponse(
                requiredString(data, "accessToken"), nullableString(data, "refreshToken"),
                requiredString(data, "tokenType"), requiredPositiveLong(data, "expiresIn"));
    }

    public static AuthDto.AuthUserResponse readMeResponse(String json) {
        JsonNode data = data(json);
        JsonNode nested = data.get("user");
        return user(nested != null && nested.isObject() ? nested : data);
    }

    private static AuthDto.AuthUserResponse user(JsonNode data) {
        return new AuthDto.AuthUserResponse(
                requiredString(data, "id"), requiredString(data, "username"),
                requiredString(data, "displayName"), requiredString(data, "email"),
                nullableString(data, "avatarUrl"), requiredString(data, "createdAt"),
                requiredString(data, "updatedAt"));
    }

    private static JsonNode data(String json) {
        if (json == null) throw new IllegalArgumentException("Authentication response is required");
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Authentication response must be an object");
            }
            if (root.has("data") && root.path("data").isObject()) {
                return root.path("data");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed authentication response", e);
        }
    }

    private static String requiredString(JsonNode object, String name) {
        JsonNode node = object.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(name + " must be a nonblank string");
        }
        return node.asText();
    }

    private static String nullableString(JsonNode object, String name) {
        JsonNode node = object.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException(name + " must be a string or null");
        return node.asText();
    }

    private static long requiredPositiveLong(JsonNode object, String name) {
        JsonNode node = object.get(name);
        if (node == null || !node.isIntegralNumber() || node.asLong() < 1) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return node.asLong();
    }
}
