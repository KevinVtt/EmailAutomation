package com.emailfilter.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(
                "dGhpcyBpcyBhIHZlcnkgbG9uZyBzZWNyZXQga2V5IGZvciB0ZXN0aW5nIHB1cnBvc2VzIG9ubHkgZm9yIGp3dA==",
                900000L,
                604800000L
        );
    }

    @Test
    void generateAccessToken_returnsValidToken() {
        var userId = UUID.randomUUID();
        var token = tokenProvider.generateAccessToken(userId, "test@example.com");

        assertNotNull(token);
        assertTrue(tokenProvider.validateToken(token));
        assertEquals(userId, tokenProvider.getUserIdFromToken(token));
    }

    @Test
    void generateRefreshToken_returnsValidToken() {
        var userId = UUID.randomUUID();
        var token = tokenProvider.generateRefreshToken(userId);

        assertNotNull(token);
        assertTrue(tokenProvider.validateToken(token));
        assertEquals(userId, tokenProvider.getUserIdFromToken(token));
    }

    @Test
    void validateToken_invalidToken_returnsFalse() {
        assertFalse(tokenProvider.validateToken("invalid.jwt.token"));
    }

    @Test
    void validateToken_emptyToken_returnsFalse() {
        assertFalse(tokenProvider.validateToken(""));
    }
}
