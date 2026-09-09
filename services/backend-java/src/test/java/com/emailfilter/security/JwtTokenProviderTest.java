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
    void generateAccessToken_hasTypeAccess() {
        var token = tokenProvider.generateAccessToken(UUID.randomUUID(), "test@example.com");

        assertTrue(tokenProvider.isAccessToken(token));
    }

    @Test
    void generateRefreshToken_hasTypeRefresh() {
        var token = tokenProvider.generateRefreshToken(UUID.randomUUID());

        assertFalse(tokenProvider.isAccessToken(token));
    }

    @Test
    void isAccessToken_invalidToken_returnsFalse() {
        assertFalse(tokenProvider.isAccessToken("invalid.jwt.token"));
    }

    @Test
    void constructor_shortSecret_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider(
                "too-short-secret",
                900000L,
                604800000L
        ));
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
