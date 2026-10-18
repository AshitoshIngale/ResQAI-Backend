package com.resqai;

import com.resqai.security.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtServiceTest {

    private final String secret = Base64.getEncoder().encodeToString(
        "test-only-secret-for-jwt-signing-must-be-long-enough!!".getBytes());

    @Test
    void accessAndRefreshTokensValidateForTheirOwnAudiences() {
        JwtService service = new JwtService(secret, 900, 2592000);
        String access = service.createAccessToken("user-1");
        String refresh = service.createRefreshToken("user-1");

        assertEquals("user-1", service.validateAccessToken(access));
        Claims claims = service.validateRefreshToken(refresh);
        assertNotNull(claims);
        assertEquals("user-1", claims.getSubject());
    }

    @Test
    void refreshTokensAreNotValidAccessTokensAndViceVersa() {
        JwtService service = new JwtService(secret, 900, 2592000);
        String access = service.createAccessToken("user-1");
        String refresh = service.createRefreshToken("user-1");

        assertNull(service.validateAccessToken(refresh), "refresh must not work as access");
        assertNull(service.validateRefreshToken(access), "access must not work as refresh");
    }

    @Test
    void expiredAccessTokensAreRejected() {
        JwtService alreadyExpired = new JwtService(secret, -1, 2592000);
        String access = alreadyExpired.createAccessToken("user-1");
        assertNull(alreadyExpired.validateAccessToken(access));
    }

    @Test
    void tamperedTokensAreRejected() {
        JwtService service = new JwtService(secret, 900, 2592000);
        String access = service.createAccessToken("user-1");
        String tampered = access.substring(0, access.length() - 4) + "AAAA";
        assertNull(service.validateAccessToken(tampered));
    }

    @Test
    void tokensSignedWithADifferentSecretAreRejected() {
        JwtService service = new JwtService(secret, 900, 2592000);
        JwtService attacker = new JwtService(
            Base64.getEncoder().encodeToString("an-entirely-different-secret-value-123456".getBytes()),
            900, 2592000);
        String forged = attacker.createAccessToken("victim");
        assertNull(service.validateAccessToken(forged));
    }
}
