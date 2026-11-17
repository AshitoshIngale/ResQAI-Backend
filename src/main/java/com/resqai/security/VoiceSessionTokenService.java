package com.resqai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Short-lived access tokens that authorize a BROWSER voice session for ONE
 * emergency incident. These are separate from user JWTs on purpose:
 *
 *  - The bystander is not an authenticated user; the session is authorized
 *    by the fact that a verified emergency report exists for the incident.
 *  - Tokens are short-lived (default 15 minutes) and bound to a single
 *    incidentId + sessionId, so a leaked token cannot authorize any other
 *    incident and expires quickly.
 *  - The WebSocket handshake rejects anything but a valid, unexpired,
 *    incident-matching token (replay of a token for another incident fails;
 *    expiry bounds the replay window).
 *
 * Signing reuses the server JWT secret but a distinct audience/purpose so a
 * voice token can never be used as a user access token, and vice versa.
 */
@Service
public class VoiceSessionTokenService {

    public static final String PURPOSE = "resqai-emergency-voice";

    private final SecretKey key;
    private final Duration ttl;

    public VoiceSessionTokenService(
            @Value("${resqai.jwt.secret:}") String jwtSecret,
            @Value("${resqai.voice.token-ttl-seconds:900}") long ttlSeconds) {
        byte[] secretBytes = decode(jwtSecret);
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    private static byte[] decode(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "resqai.jwt.secret must be configured (voice tokens share the signing key)");
        }
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public record IssuedToken(String token, String sessionId, Instant expiresAt) {}

    /**
     * Issues a token for one voice session. The sessionId is server-generated
     * (UUID) so the browser can never choose its own identity.
     */
    public IssuedToken issue(String incidentId, String sessionId) {
        Instant expiresAt = Instant.now().plus(ttl);
        String token = Jwts.builder()
            .audience().add(PURPOSE).and()
            .subject(incidentId)
            .id(UUID.randomUUID().toString())
            .claim("sid", sessionId)
            .issuedAt(new Date())
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact();
        return new IssuedToken(token, sessionId, expiresAt);
    }

    /**
     * Validates a token and returns its claims ONLY when it is a genuine,
     * unexpired voice token for the expected incident.
     *
     * @throws JwtTokenException when the token is invalid, expired, or bound
     *         to a different incident.
     */
    public Claims validate(String token, String expectedIncidentId) throws JwtTokenException {
        if (token == null || token.isBlank()) {
            throw new JwtTokenException("missing voice token");
        }
        try {
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireAudience(PURPOSE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            String incident = claims.getSubject();
            if (incident == null || !incident.equals(expectedIncidentId)) {
                throw new JwtTokenException("token is bound to a different incident");
            }
            String sid = claims.get("sid", String.class);
            if (sid == null || sid.isBlank()) {
                throw new JwtTokenException("token is missing its session binding");
            }
            return claims;
        } catch (JwtTokenException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtTokenException("invalid or expired voice token");
        }
    }

    public static class JwtTokenException extends Exception {
        public JwtTokenException(String message) { super(message); }
    }
}
