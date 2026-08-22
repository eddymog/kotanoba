package com.kotanoba.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret Base64 or plain string, at least 256 bits once decoded — HS256
 *               requires it. The default in application.yml is fine for local
 *               dev only; anything that isn't localhost must override it via
 *               JWT_SECRET, or every JWT this app issues is forgeable by
 *               anyone who reads the source.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long accessTokenTtlMinutes, long refreshTokenTtlDays) {}
