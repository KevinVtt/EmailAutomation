package com.emailfilter.controller;

import com.emailfilter.config.CorsConfig;
import com.emailfilter.config.SecurityConfig;
import com.emailfilter.dto.AuthResponse;
import com.emailfilter.model.User;
import com.emailfilter.repository.OAuthTokenRepository;
import com.emailfilter.security.JwtTokenProvider;
import com.emailfilter.security.TokenCryptoService;
import com.emailfilter.security.UserDetailsServiceImpl;
import com.emailfilter.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.jayway.jsonpath.JsonPath;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
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
    private TokenCryptoService tokenCryptoService;

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

    private String issueState(String provider) throws Exception {
        var result = mockMvc.perform(get("/api/auth/oauth2/" + provider)).andReturn();
        var authUrl = JsonPath.<String>read(result.getResponse().getContentAsString(), "$.authUrl");
        return UriComponentsBuilder.fromUriString(authUrl).build().getQueryParams().getFirst("state");
    }

    private void stubGoogleCallback() {
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
    }

    @Test
    void getGoogleOAuthUrl_returnsUrl() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authUrl").isString())
                .andExpect(jsonPath("$.authUrl", containsString("state=")));

        var state = issueState("google");
        org.junit.jupiter.api.Assertions.assertNotNull(state);
        org.junit.jupiter.api.Assertions.assertFalse(state.isBlank());
    }

    @Test
    void getOutlookOAuthUrl_returnsUrl() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/outlook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authUrl").isString())
                // User.Read es lo que habilita Graph /me en el callback.
                .andExpect(jsonPath("$.authUrl", containsString("scope=openid+email+profile+User.Read+Mail.Read+Mail.ReadWrite+offline_access")))
                .andExpect(jsonPath("$.authUrl", containsString("state=")));

        var state = issueState("outlook");
        org.junit.jupiter.api.Assertions.assertNotNull(state);
        org.junit.jupiter.api.Assertions.assertFalse(state.isBlank());
    }

    @Test
    void getOAuthUrl_placeholderClientId_returnsBadRequest() throws Exception {
        // El .env de ejemplo trae your-outlook-client-id: no mandamos al usuario a Microsoft con eso.
        var original = ReflectionTestUtils.getField(authController, "outlookClientId");
        ReflectionTestUtils.setField(authController, "outlookClientId", "your-outlook-client-id");
        try {
            mockMvc.perform(get("/api/auth/oauth2/outlook"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("no está configurado")));
        } finally {
            ReflectionTestUtils.setField(authController, "outlookClientId", original);
        }
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

        var body = "{\"authorizationCode\":\"code123\",\"provider\":\"google\",\"state\":\""
                + issueState("google") + "\"}";

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

    @Test
    void handleCallback_outlookPhotoSuccess_returnsDataUrl() throws Exception {
        var response = AuthResponse.builder()
                .userId(UUID.randomUUID())
                .email("test@outlook.com")
                .name("Test User")
                .accessToken("token")
                .refreshToken("refresh")
                .build();

        var user = User.builder()
                .id(UUID.randomUUID())
                .email("test@outlook.com")
                .build();

        Map<String, Object> tokenResponse = Map.of(
                "access_token", "outlook-access-token",
                "refresh_token", "outlook-refresh-token",
                "token_type", "Bearer",
                "scope", "email profile Mail.Read",
                "expires_in", 3600
        );
        Map<String, Object> userInfo = Map.of(
                "id", "outlook-user-1",
                "mail", "test@outlook.com",
                "displayName", "Test User"
        );
        byte[] photoBytes = new byte[]{0x01, 0x02, 0x03, (byte) 0xFF};

        when(restTemplate.exchange(
                eq("https://login.microsoftonline.com/common/oauth2/v2.0/token"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(tokenResponse));
        when(restTemplate.exchange(
                eq("https://graph.microsoft.com/v1.0/me"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(userInfo));
        when(restTemplate.exchange(
                eq("https://graph.microsoft.com/v1.0/me/photo/$value"),
                eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(photoBytes));

        when(authService.authenticateOAuth2User(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(response);
        when(authService.findByEmail(anyString())).thenReturn(user);
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(any(), anyString()))
                .thenReturn(Optional.empty());

        var body = "{\"authorizationCode\":\"code123\",\"state\":\"" + issueState("outlook") + "\"}";

        mockMvc.perform(post("/api/auth/callback/outlook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@outlook.com"));
    }

    @Test
    void handleCallback_outlookPhotoNotFound_avatarUrlEmpty() throws Exception {
        var response = AuthResponse.builder()
                .userId(UUID.randomUUID())
                .email("test@outlook.com")
                .name("Test User")
                .accessToken("token")
                .refreshToken("refresh")
                .build();

        var user = User.builder()
                .id(UUID.randomUUID())
                .email("test@outlook.com")
                .build();

        Map<String, Object> tokenResponse = Map.of(
                "access_token", "outlook-access-token",
                "refresh_token", "outlook-refresh-token",
                "token_type", "Bearer",
                "scope", "email profile Mail.Read",
                "expires_in", 3600
        );
        Map<String, Object> userInfo = Map.of(
                "id", "outlook-user-1",
                "mail", "test@outlook.com",
                "displayName", "Test User"
        );

        when(restTemplate.exchange(
                eq("https://login.microsoftonline.com/common/oauth2/v2.0/token"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(tokenResponse));
        when(restTemplate.exchange(
                eq("https://graph.microsoft.com/v1.0/me"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(userInfo));
        when(restTemplate.exchange(
                eq("https://graph.microsoft.com/v1.0/me/photo/$value"),
                eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "Not Found"));

        when(authService.authenticateOAuth2User(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(response);
        when(authService.findByEmail(anyString())).thenReturn(user);
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(any(), anyString()))
                .thenReturn(Optional.empty());

        var body = "{\"authorizationCode\":\"code123\",\"state\":\"" + issueState("outlook") + "\"}";

        mockMvc.perform(post("/api/auth/callback/outlook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@outlook.com"));
    }

    @Test
    void handleCallback_missingState_returnsUnauthorized() throws Exception {
        var body = "{\"authorizationCode\":\"code123\",\"provider\":\"google\"}";

        mockMvc.perform(post("/api/auth/callback/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void handleCallback_unknownState_returnsUnauthorized() throws Exception {
        var body = "{\"authorizationCode\":\"code123\",\"provider\":\"google\",\"state\":\"forged-state\"}";

        mockMvc.perform(post("/api/auth/callback/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void handleCallback_reusedState_returnsUnauthorized() throws Exception {
        stubGoogleCallback();
        var state = issueState("google");
        var body = "{\"authorizationCode\":\"code123\",\"provider\":\"google\",\"state\":\"" + state + "\"}";

        mockMvc.perform(post("/api/auth/callback/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/callback/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }
}