package com.landrop.ui.auth;

import com.landrop.ui.auth.mapping.AuthJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthJsonTest {
    private static final String USER = "\"user\":{\"id\":\"u1\",\"username\":\"ada\",\"displayName\":\"Ada\",\"email\":\"ada@example.com\",\"avatarUrl\":null,\"createdAt\":\"2026-09-13T00:00:00Z\",\"updatedAt\":\"2026-09-13T00:00:00Z\"}";

    @Test
    void readsAuthEnvelopeAndSerializesRequest() {
        var response = AuthJson.readAuthResponse("{\"data\":{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"tokenType\":\"Bearer\",\"expiresIn\":3600," + USER + "}}");
        assertEquals("a", response.accessToken());
        assertEquals("ada", response.user().username());
        assertEquals("{\"email\":\"ada@example.com\",\"password\":\"secret\"}",
                AuthJson.write(new com.landrop.ui.auth.dto.AuthDto.LoginRequest("ada@example.com", "secret")));
    }

    @Test
    void rejectsMissingFieldsWrongTypesAndMalformedResponses() {
        assertThrows(IllegalArgumentException.class, () -> AuthJson.readAuthResponse("{\"data\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> AuthJson.readAuthResponse("{\"data\":{\"accessToken\":4}}"));
        assertThrows(IllegalArgumentException.class, () -> AuthJson.readRefreshResponse("{\"data\":{\"accessToken\":\"a\",\"refreshToken\":null,\"tokenType\":\"Bearer\",\"expiresIn\":0}}"));
        assertThrows(IllegalArgumentException.class, () -> AuthJson.readMeResponse("not-json"));
    }
}
