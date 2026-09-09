package com.emailfilter.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class OutlookService {

    private final RestTemplate restTemplate = new RestTemplate();

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
            var response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

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

        restTemplate.exchange(
                "https://graph.microsoft.com/v1.0/me/messages/" + messageId + "/move",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("destinationId", "deleteditems"), headers),
                Map.class
        );
    }

    private void patchMessage(String accessToken, String messageId, Map<String, Object> updates) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var entity = new HttpEntity<>(updates, headers);

        restTemplate.exchange(
                "https://graph.microsoft.com/v1.0/me/messages/" + messageId,
                HttpMethod.PATCH,
                entity,
                Map.class
        );
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
