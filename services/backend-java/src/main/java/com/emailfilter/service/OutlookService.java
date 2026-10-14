package com.emailfilter.service;

import com.emailfilter.exception.PermanentApiException;
import com.emailfilter.exception.TransientApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutlookService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final RetryTemplate externalApiRetryTemplate;

    @Value("${spring.security.oauth2.client.provider.outlook.token-uri}")
    private String tokenUri;

    public List<Map<String, Object>> fetchEmails(String accessToken, int maxResults) {
        var allMessages = new ArrayList<Map<String, Object>>();
        var headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        var entity = new HttpEntity<>(headers);

        var url = "https://graph.microsoft.com/v1.0/me/messages?$top=" + maxResults +
                "&$orderby=receivedDateTime DESC&$select=id,subject,from,toRecipients,bodyPreview,body,isRead,isDraft,receivedDateTime,categories";

        while (url != null && allMessages.size() < maxResults) {
            final String currentUrl = url;
            var response = retryTemplateExecute(() -> restTemplate.exchange(
                    currentUrl,
                    HttpMethod.GET,
                    entity,
                    Map.class
            ));

            var body = response.getBody();
            if (body == null || !body.containsKey("value")) break;

            @SuppressWarnings("unchecked")
            var messages = (List<Map<String, Object>>) body.get("value");
            allMessages.addAll(messages);

            url = (String) body.get("@odata.nextLink");
        }

        return allMessages.size() > maxResults
                ? allMessages.subList(0, maxResults)
                : allMessages;
    }

    public void markAsRead(String accessToken, String messageId) {
        patchMessage(accessToken, messageId, Map.of("isRead", true));
    }

    public void markAsUnread(String accessToken, String messageId) {
        patchMessage(accessToken, messageId, Map.of("isRead", false));
    }

    public void toggleStar(String accessToken, String messageId, boolean starred) {
        patchMessage(accessToken, messageId, Map.of("flag", Map.of("flagStatus", starred ? "flagged" : "notFlagged")));
    }

    public void moveToTrash(String accessToken, String messageId) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        var entity = new HttpEntity<>(headers);

        retryTemplateExecute(() -> {
            restTemplate.exchange(
                    "https://graph.microsoft.com/v1.0/me/messages/" + messageId + "/move",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("destinationId", "deleteditems"), headers),
                    Map.class
            );
            return null;
        });
    }

    public String sendEmail(String accessToken, String to, String subject, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        var payload = Map.of(
                "message", Map.of(
                        "subject", subject,
                        "body", Map.of("contentType", "Text", "content", body),
                        "toRecipients", List.of(Map.of("emailAddress", Map.of("address", to)))
                ),
                "saveToSentItems", true
        );

        retryTemplateExecute(() -> {
            restTemplate.exchange(
                    "https://graph.microsoft.com/v1.0/me/sendMail",
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    Map.class
            );
            return null;
        });

        // Graph returns 202 Accepted with no body — generate a local id for tracking
        var messageId = UUID.randomUUID().toString();
        log.info("Outlook message sent: id={}, to={}, subject={}", messageId, to, subject);
        return messageId;
    }

    private void patchMessage(String accessToken, String messageId, Map<String, Object> updates) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var entity = new HttpEntity<>(updates, headers);

        retryTemplateExecute(() -> {
            restTemplate.exchange(
                    "https://graph.microsoft.com/v1.0/me/messages/" + messageId,
                    HttpMethod.PATCH,
                    entity,
                    Map.class
            );
            return null;
        });
    }

    private <T> T retryTemplateExecute(RetryCall<T> call) {
        return externalApiRetryTemplate.execute(context -> {
            try {
                return call.execute();
            } catch (RestClientException e) {
                throw handleGraphApiException(e);
            }
        });
    }

    private RuntimeException handleGraphApiException(RestClientException e) {
        if (e instanceof HttpClientErrorException hcee) {
            int status = hcee.getStatusCode().value();
            if (status == 429) {
                // Si Graph manda Retry-After, respetalo en vez del backoff calculado
                String retryAfter = hcee.getResponseHeaders() != null
                        ? hcee.getResponseHeaders().getFirst("Retry-After") : null;
                if (retryAfter != null) {
                    sleepQuietly(Long.parseLong(retryAfter) * 1000);
                }
                return new TransientApiException("Rate limit alcanzado (429)", e);
            }
            if (status >= 400 && status < 500) {
                // 401/403 (token vencido, permisos revocados) y otros 4xx no se arreglan reintentando
                return new PermanentApiException("Graph API error permanente (HTTP " + status + ")", e);
            }
            return new TransientApiException("Graph API error transitorio (HTTP " + status + ")", e);
        }
        if (e instanceof HttpServerErrorException) {
            return new TransientApiException("Graph API error de servidor (5xx)", e);
        }
        return new RuntimeException(e);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface RetryCall<T> {
        T execute();
    }

    public String refreshAccessToken(String refreshToken, String clientId, String clientSecret, String tenant) {
        var body = new LinkedMultiValueMap<String, String>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");
        body.add("scope", "Mail.Read Mail.ReadWrite offline_access");
        var headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);

        var entity = new HttpEntity<>(body, headers);
        var response = restTemplate.exchange(
                tokenUri.replace("{" + "tenant" + "}", tenant),
                HttpMethod.POST,
                entity,
                Map.class
        );
        return (String) response.getBody().get("access_token");
    }
}
