package com.resqai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * JWT issuing/validation. Access tokens are short-lived and carry the user id;
 * refresh tokens are opaque-looking JWTs with a dedicated audience and a jti
 * that the server tracks (single active session per user, rotation on use).
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(
        @Value("${resqai.jwt.secret}") String base64Secret,
        @Value("${resqai.jwt.access-ttl-seconds:900}") long accessTtlSeconds,
        @Value("${resqai.jwt.refresh-ttl-seconds:2592000}") long refreshTtlSeconds
    ) {
        byte[] secretBytes;
        try {
            secretBytes = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException e) {
            secretBytes = base64Secret.getBytes(StandardCharsets.UTF_8);
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessTtl = Duration.ofSeconds(accessTtlSeconds);
        this.refreshTtl = Duration.ofSeconds(refreshTtlSeconds);
    }

    public String createAccessToken(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId)
            .id(UUID.randomUUID().toString())
            .audience().add("resqai-api").and()
            .claim("typ", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(accessTtl)))
            .signWith(key)
            .compact();
    }

    public String createRefreshToken(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId)
            .id(UUID.randomUUID().toString())
            .audience().add("resqai-refresh").and()
            .claim("typ", "refresh")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(refreshTtl)))
            .signWith(key)
            .compact();
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    /** Returns claims when the token is a valid refresh token for this service; else null. */
    public Claims validateRefreshToken(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireAudience("resqai-refresh")
                .build()
                .parseSignedClaims(token)
                .getPayload();
            if (!"refresh".equals(claims.get("typ", String.class))) return null;
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the user id when the token is a valid access token; else null. */
    public String validateAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireAudience("resqai-api")
                .build()
                .parseSignedClaims(token)
                .getPayload();
            if (!"access".equals(claims.get("typ", String.class))) return null;
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
