package com.kotanoba.user;

public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {

    static AuthResponse of(String accessToken, String refreshToken, long expiresInSeconds) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }
}
