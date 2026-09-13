package com.landrop.ui.auth.api;

import com.landrop.ui.auth.dto.AuthDto;

import java.util.concurrent.CompletableFuture;

/** Authentication boundary consumed by AuthStateService, never by FXML controllers directly. */
public interface AuthApi {
    CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request);
    CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request);
    CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request);
    CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken);
    CompletableFuture<Void> logout(String accessToken, String refreshToken);
}
