package com.emailfilter.service;

import com.emailfilter.dto.AuthResponse;
import com.emailfilter.model.User;
import com.emailfilter.repository.UserRepository;
import com.emailfilter.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, jwtTokenProvider);
    }

    @Test
    void authenticateOAuth2User_existingUser_returnsTokens() {
        var userId = UUID.randomUUID();
        var user = User.builder()
                .id(userId)
                .email("test@example.com")
                .name("Test User")
                .provider("google")
                .providerId("12345")
                .build();

        when(userRepository.findByProviderAndProviderId("google", "12345"))
                .thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(userId, "test@example.com"))
                .thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken(userId))
                .thenReturn("refresh-token");

        var result = authService.authenticateOAuth2User("google", "test@example.com", "Test User", "12345", "");

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
        assertEquals("test@example.com", result.getEmail());
        assertEquals("access-token", result.getAccessToken());
        assertEquals("refresh-token", result.getRefreshToken());
        verify(userRepository, never()).save(any());
    }

    @Test
    void authenticateOAuth2User_newUser_createsAndReturnsTokens() {
        var userId = UUID.randomUUID();
        var newUser = User.builder()
                .id(userId)
                .email("new@example.com")
                .name("New User")
                .provider("google")
                .providerId("67890")
                .build();

        when(userRepository.findByProviderAndProviderId("google", "67890"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(newUser);
        when(jwtTokenProvider.generateAccessToken(userId, "new@example.com"))
                .thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken(userId))
                .thenReturn("refresh-token");

        var result = authService.authenticateOAuth2User("google", "new@example.com", "New User", "67890", "");

        assertNotNull(result);
        assertEquals("new@example.com", result.getEmail());
        verify(userRepository).save(any());
    }

    @Test
    void refreshAccessToken_validToken_returnsNewTokens() {
        var userId = UUID.randomUUID();
        var user = User.builder()
                .id(userId)
                .email("test@example.com")
                .name("Test User")
                .build();

        when(jwtTokenProvider.validateToken("valid-refresh-token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("valid-refresh-token")).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(userId, "test@example.com")).thenReturn("new-access");
        when(jwtTokenProvider.generateRefreshToken(userId)).thenReturn("new-refresh");

        var result = authService.refreshAccessToken("valid-refresh-token");

        assertNotNull(result);
        assertEquals("new-access", result.getAccessToken());
        assertEquals("new-refresh", result.getRefreshToken());
    }

    @Test
    void refreshAccessToken_invalidToken_throwsException() {
        when(jwtTokenProvider.validateToken("invalid-token")).thenReturn(false);

        assertThrows(RuntimeException.class, () -> authService.refreshAccessToken("invalid-token"));
    }
}
