package com.kotanoba.user;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Access tokens only. Signed and verified with one symmetric HMAC key — no
 * external IdP, no JWK rotation, no OAuth2 Authorization Server. That
 * machinery solves a problem this single-user app doesn't have; a shared
 * secret is the boring, correct-sized answer here.
 *
 * <p>The token carries the user id as its subject and nothing else sensitive
 * — email is deliberately left out so the JWT payload (base64, not
 * encrypted, readable by anyone who has the token) doesn't leak it.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMinutes(properties.accessTokenTtlMinutes());
    }

    public String issueAccessToken(long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(Long.toString(userId))
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(accessTokenTtl)))
            .signWith(key)
            .compact();
    }

    /** Empty when the token is missing, malformed, expired, or has a bad signature. */
    public Optional<Long> verifyAndGetUserId(String token) {
        try {
            String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
            return Optional.of(Long.parseLong(subject));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }
}
