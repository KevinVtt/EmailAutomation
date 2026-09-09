package com.emailfilter.controller;

import com.emailfilter.config.CorsConfig;
import com.emailfilter.config.SecurityConfig;
import com.emailfilter.dto.AuthResponse;
import com.emailfilter.model.User;
import com.emailfilter.repository.OAuthTokenRepository;
import com.emailfilter.security.JwtTokenProvider;
import com.emailfilter.security.UserDetailsServiceImpl;
import com.emailfilter.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@TestPropertySource(properties = {
        "google.client-id=test-google-client-id",
        "google.client-secret=test-google-client-secret",
        "google.redirect-uri=http://localhost:8080/api/auth/callback/google",
        "outlook.client-id=test-outlook-client-id",
        "outlook.client-secret=test-outlook-client-secret",
        "outlook.redirect-uri=http://localhost:8080/api/auth/callback/outlook"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthController authController;

    @MockBean
    private AuthService authService;

    @MockBean
    private OAuthTokenRepository oauthTokenRepository;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(authController, "restTemplate", restTemplate);
    }

    @Test
    void getGoogleOAuthUrl_returnsUrl() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authUrl").isString());
    }

    @Test
    void getOutlookOAuthUrl_returnsUrl() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/outlook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authUrl").isString());
    }

    @Test
    void handleCallback_returnsAuthResponse() throws Exception {
        var response = AuthResponse.builder()
                .userId(UUID.randomUUID())
                .email("test@example.com")
                .name("Test")
                .accessToken("token")
                .refreshToken("refresh")
                .build();

        var user = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .build();

        Map<String, Object> tokenResponse = Map.of(
                "access_token", "access-token-123",
                "refresh_token", "refresh-token-123",
                "token_type", "Bearer",
                "scope", "email profile",
                "expires_in", 3600
        );
        Map<String, Object> userInfo = Map.of(
                "id", "google-user-1",
                "email", "test@example.com",
                "name", "Test",
                "picture", "https://example.com/avatar.png"
        );

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(tokenResponse));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(userInfo));
        when(authService.authenticateOAuth2User(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(response);
        when(authService.findByEmail(anyString())).thenReturn(user);
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(any(), anyString()))
                .thenReturn(Optional.empty());

        var body = "{\"authorizationCode\":\"code123\",\"provider\":\"google\"}";

        mockMvc.perform(post("/api/auth/callback/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.accessToken").value("token"));
    }

    @Test
    void refresh_returnsNewTokens() throws Exception {
        var response = AuthResponse.builder()
                .userId(UUID.randomUUID())
                .email("test@example.com")
                .name("Test")
                .accessToken("new-token")
                .refreshToken("new-refresh")
                .build();

        when(authService.refreshAccessToken(anyString())).thenReturn(response);

        var body = "{\"refreshToken\":\"old-refresh\"}";

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-token"));
    }

    @Test
    void refresh_emptyBody_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refresh_missingRefreshToken_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshToken_rejectedOnProtectedEndpoint_returnsUnauthorized() throws Exception {
        when(jwtTokenProvider.isAccessToken(anyString())).thenReturn(false);

        mockMvc.perform(get("/api/emails")
                        .header("Authorization", "Bearer refresh-token"))
                .andExpect(status().isUnauthorized());
    }
}