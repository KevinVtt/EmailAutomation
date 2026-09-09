package com.emailfilter.controller;

import com.emailfilter.dto.AuthRequest;
import com.emailfilter.dto.AuthResponse;
import com.emailfilter.model.OAuthToken;
import com.emailfilter.model.User;
import com.emailfilter.repository.OAuthTokenRepository;
import com.emailfilter.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final OAuthTokenRepository oauthTokenRepository;
    private RestTemplate restTemplate = new RestTemplate();

    @Value("${google.client-id}")
    private String googleClientId;

    @Value("${google.client-secret}")
    private String googleClientSecret;

    @Value("${google.redirect-uri}")
    private String googleRedirectUri;

    @Value("${outlook.client-id}")
    private String outlookClientId;

    @Value("${outlook.client-secret}")
    private String outlookClientSecret;

    @Value("${outlook.redirect-uri}")
    private String outlookRedirectUri;

    @Value("${outlook.tenant:common}")
    private String outlookTenant;

    @GetMapping("/oauth2/{provider}")
    public ResponseEntity<Map<String, String>> initiateOAuth2(@PathVariable String provider) {
        var authUrl = switch (provider) {
            case "google" -> "https://accounts.google.com/o/oauth2/v2/auth?" +
                    "client_id=" + googleClientId +
                    "&redirect_uri=" + googleRedirectUri +
                    "&response_type=code" +
                    "&scope=email+profile+https://www.googleapis.com/auth/gmail.readonly+https://www.googleapis.com/auth/gmail.modify" +
                    "&access_type=offline" +
                    "&prompt=consent";
            case "outlook" -> "https://login.microsoftonline.com/" + outlookTenant +
                    "/oauth2/v2.0/authorize?" +
                    "client_id=" + outlookClientId +
                    "&redirect_uri=" + outlookRedirectUri +
                    "&response_type=code" +
                    "&scope=openid+email+profile+Mail.Read+Mail.ReadWrite+offline_access";
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
        return ResponseEntity.ok(Map.of("authUrl", authUrl));
    }

    @PostMapping("/callback/{provider}")
    public ResponseEntity<?> handleCallback(
            @PathVariable String provider,
            @Valid @RequestBody AuthRequest request) {
        log.info("OAuth callback received for provider={}, code length={}", provider,
                request.getAuthorizationCode() != null ? request.getAuthorizationCode().length() : 0);
        try {
            log.info("Exchanging authorization code for tokens...");
            var result = exchangeCodeForToken(provider, request.getAuthorizationCode());
            var userInfo = (Map<String, Object>) result.get("user");
            var tokenData = (Map<String, Object>) result.get("token");
            log.info("Token exchange successful for email={}", userInfo.get("email"));

            log.info("Authenticating OAuth2 user...");
            var response = authService.authenticateOAuth2User(
                    provider,
                    userInfo.get("email").toString(),
                    userInfo.getOrDefault("name", "").toString(),
                    userInfo.get("id").toString(),
                    userInfo.getOrDefault("avatarUrl", "").toString()
            );
            log.info("User authenticated successfully, userId={}", response.getUserId());

            log.info("Looking up user for OAuth token storage...");
            var user = authService.findByEmail(userInfo.get("email").toString());
            log.info("Saving OAuth token...");
            saveOAuthToken(user, provider, tokenData);
            log.info("OAuth token saved successfully");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("OAuth callback failed for provider={}", provider, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Error al autenticar con " + provider));
        }
    }

    private void saveOAuthToken(User user, String provider, Map<String, Object> tokenData) {
        var token = OAuthToken.builder()
                .user(user)
                .provider(provider)
                .accessToken(tokenData.get("access_token").toString())
                .refreshToken(tokenData.getOrDefault("refresh_token", "").toString())
                .tokenType(tokenData.getOrDefault("token_type", "Bearer").toString())
                .scope(tokenData.getOrDefault("scope", "").toString())
                .build();
        if (tokenData.containsKey("expires_in")) {
            int expiresIn = ((Number) tokenData.get("expires_in")).intValue();
            token.setExpiresAt(Instant.now().plusSeconds(expiresIn));
        }
        oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(user.getId(), provider)
                .ifPresentOrElse(
                        existing -> {
                            existing.setAccessToken(token.getAccessToken());
                            if (!token.getRefreshToken().isBlank()) existing.setRefreshToken(token.getRefreshToken());
                            if (token.getExpiresAt() != null) existing.setExpiresAt(token.getExpiresAt());
                            oauthTokenRepository.save(existing);
                        },
                        () -> oauthTokenRepository.save(token)
                );
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) Map<String, String> body) {
        if (body == null || body.get("refreshToken") == null || body.get("refreshToken").isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        var response = authService.refreshAccessToken(body.get("refreshToken"));
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForToken(String provider, String code) {
        return switch (provider) {
            case "google" -> exchangeGoogleCode(code);
            case "outlook" -> exchangeOutlookCode(code);
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeGoogleCode(String code) {
        var body = new LinkedMultiValueMap<String, String>();
        body.add("code", code);
        body.add("client_id", googleClientId);
        body.add("client_secret", googleClientSecret);
        body.add("redirect_uri", googleRedirectUri);
        body.add("grant_type", "authorization_code");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        var tokenResponse = restTemplate.exchange(
                "https://oauth2.googleapis.com/token",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        ).getBody();

        if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
            throw new RuntimeException("Failed to exchange Google authorization code for token");
        }

        var accessToken = tokenResponse.get("access_token").toString();

        var userHeaders = new HttpHeaders();
        userHeaders.setBearerAuth(accessToken);

        var userInfo = restTemplate.exchange(
                "https://www.googleapis.com/oauth2/v2/userinfo",
                HttpMethod.GET,
                new HttpEntity<>(userHeaders),
                Map.class
        ).getBody();

        if (userInfo == null) {
            throw new RuntimeException("Failed to fetch Google user info");
        }

        return Map.of(
                "user", Map.of(
                        "id", userInfo.getOrDefault("id", ""),
                        "email", userInfo.getOrDefault("email", ""),
                        "name", userInfo.getOrDefault("name", ""),
                        "avatarUrl", userInfo.getOrDefault("picture", "")
                ),
                "token", tokenResponse
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeOutlookCode(String code) {
        var body = new LinkedMultiValueMap<String, String>();
        body.add("code", code);
        body.add("client_id", outlookClientId);
        body.add("client_secret", outlookClientSecret);
        body.add("redirect_uri", outlookRedirectUri);
        body.add("grant_type", "authorization_code");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        var tokenResponse = restTemplate.exchange(
                "https://login.microsoftonline.com/" + outlookTenant + "/oauth2/v2.0/token",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        ).getBody();

        if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
            throw new RuntimeException("Failed to exchange Outlook authorization code for token");
        }

        var accessToken = tokenResponse.get("access_token").toString();

        var userHeaders = new HttpHeaders();
        userHeaders.setBearerAuth(accessToken);

        var userInfo = restTemplate.exchange(
                "https://graph.microsoft.com/v1.0/me",
                HttpMethod.GET,
                new HttpEntity<>(userHeaders),
                Map.class
        ).getBody();

        if (userInfo == null) {
            throw new RuntimeException("Failed to fetch Outlook user info");
        }

        String avatarUrl = "";
        try {
            var photoResponse = restTemplate.exchange(
                    "https://graph.microsoft.com/v1.0/me/photo/$value",
                    HttpMethod.GET,
                    new HttpEntity<>(userHeaders),
                    byte[].class
            );
            if (photoResponse.getBody() != null && photoResponse.getBody().length > 0) {
                avatarUrl = "data:image/jpeg;base64," +
                        java.util.Base64.getEncoder().encodeToString(photoResponse.getBody());
            }
        } catch (Exception e) {
            log.warn("Could not fetch Outlook user photo: {}", e.getMessage());
        }

        return Map.of(
                "user", Map.of(
                        "id", userInfo.getOrDefault("id", ""),
                        "email", userInfo.getOrDefault("mail", userInfo.getOrDefault("userPrincipalName", "")),
                        "name", userInfo.getOrDefault("displayName", ""),
                        "avatarUrl", avatarUrl
                ),
                "token", tokenResponse
        );
    }
}
