package com.kotanoba.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Issues, verifies, and revokes refresh tokens. The raw token is a 256-bit
 * random value shown to the caller exactly once, at issuance — only its
 * SHA-256 hash is ever persisted (V3__refresh_token.sql), the same reasoning
 * as password_hash: a leaked table must not hand out usable credentials.
 *
 * <p>Rotation on every refresh (old token revoked, new one issued) means a
 * stolen refresh token has a shelf life bounded by how often the legitimate
 * client refreshes, not by its full TTL.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits

    private final RefreshTokenRepository repository;
    private final Duration refreshTokenTtl;

    public RefreshTokenService(RefreshTokenRepository repository, JwtProperties properties) {
        this.repository = repository;
        this.refreshTokenTtl = Duration.ofDays(properties.refreshTokenTtlDays());
    }

    public String issue(long userId) {
        String rawToken = randomToken();
        repository.save(new RefreshToken(userId, hash(rawToken), Instant.now().plus(refreshTokenTtl)));
        return rawToken;
    }

    /**
     * Validates and rotates: the presented token is revoked and a new one
     * issued for the same user, in one step, so a caller can never end up
     * holding two simultaneously-valid refresh tokens for the same login.
     *
     * @return empty if the token is unknown, expired, or already revoked
     */
    public Optional<RotatedTokens> rotate(String rawToken) {
        Optional<RefreshToken> existing = repository.findByTokenHash(hash(rawToken));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken token = existing.get();
        if (!token.isUsable(Instant.now())) {
            return Optional.empty();
        }
        token.revoke(Instant.now());
        repository.save(token);
        return Optional.of(new RotatedTokens(token.getUserId(), issue(token.getUserId())));
    }

    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            token.revoke(Instant.now());
            repository.save(token);
        });
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JDK's default security providers.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record RotatedTokens(long userId, String newRawToken) {}
}
