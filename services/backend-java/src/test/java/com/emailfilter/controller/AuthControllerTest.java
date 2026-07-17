package com.emailfilter.controller;

import com.emailfilter.dto.AuthRequest;
import com.emailfilter.dto.AuthResponse;
import com.emailfilter.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

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

        when(authService.authenticateOAuth2User(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(response);

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
}
