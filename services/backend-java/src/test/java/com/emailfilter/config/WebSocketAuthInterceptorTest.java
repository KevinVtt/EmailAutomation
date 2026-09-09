package com.emailfilter.config;

import com.emailfilter.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketAuthInterceptorTest {

    private JwtTokenProvider tokenProvider;
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(
                "dGhpcyBpcyBhIHZlcnkgbG9uZyBzZWNyZXQga2V5IGZvciB0ZXN0aW5nIHB1cnBvc2VzIG9ubHkgZm9yIGp3dA==",
                900000L,
                604800000L
        );
        interceptor = new WebSocketAuthInterceptor(tokenProvider);
        SecurityContextHolder.clearContext();
    }

    private Message<byte[]> connectMessage(String authorizationHeader, String accessTokenHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        if (accessTokenHeader != null) {
            accessor.setNativeHeader("access_token", accessTokenHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void preSend_connectWithoutToken_rejectsConnection() {
        var message = connectMessage(null, null);

        assertNull(interceptor.preSend(message, null));
    }

    @Test
    void preSend_connectWithValidAccessToken_acceptsConnection() {
        var userId = UUID.randomUUID();
        var token = tokenProvider.generateAccessToken(userId, "test@example.com");
        var message = connectMessage("Bearer " + token, null);

        var result = interceptor.preSend(message, null);

        assertNotNull(result);
        var accessor = StompHeaderAccessor.wrap(result);
        assertNotNull(accessor.getUser());
        assertEquals(userId.toString(), accessor.getUser().getName());
    }

    @Test
    void preSend_connectWithRefreshToken_rejectsConnection() {
        var token = tokenProvider.generateRefreshToken(UUID.randomUUID());
        var message = connectMessage("Bearer " + token, null);

        assertNull(interceptor.preSend(message, null));
    }

    @Test
    void preSend_connectWithInvalidToken_rejectsConnection() {
        var message = connectMessage("Bearer invalid.jwt.token", null);

        assertNull(interceptor.preSend(message, null));
    }

    @Test
    void preSend_connectWithAccessTokenNativeHeader_acceptsConnection() {
        var token = tokenProvider.generateAccessToken(UUID.randomUUID(), "test@example.com");
        var message = connectMessage(null, token);

        var result = interceptor.preSend(message, null);

        assertNotNull(result);
        var accessor = StompHeaderAccessor.wrap(result);
        assertNotNull(accessor.getUser());
    }

    @Test
    void preSend_nonConnectMessage_notRejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertNotNull(interceptor.preSend(message, null));
    }
}