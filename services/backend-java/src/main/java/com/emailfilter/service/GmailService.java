package com.emailfilter.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailService {

    private static final String APPLICATION_NAME = "Email Filter AI";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private Gmail buildGmailClient(String accessToken) {
        try {
            com.google.api.client.http.HttpRequestInitializer requestInitializer =
                    request -> request.getHeaders().setAuthorization("Bearer " + accessToken);
            return new Gmail.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JSON_FACTORY,
                    requestInitializer)
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to build Gmail client", e);
        }
    }

    public Map<String, Object> fetchEmails(String accessToken, int maxResults, String pageToken) {
        var gmail = buildGmailClient(accessToken);

        try {
            var request = gmail.users().messages().list("me")
                    .setMaxResults((long) maxResults);
            if (pageToken != null && !pageToken.isBlank()) {
                request.setPageToken(pageToken);
            }

            var response = request.execute();
            var messages = response.getMessages();
            var result = new HashMap<String, Object>();

            if (messages == null || messages.isEmpty()) {
                result.put("emails", Collections.emptyList());
                result.put("nextPageToken", null);
                return result;
            }

            var emails = messages.stream()
                    .map(msg -> fetchMessageDetail(gmail, msg))
                    .collect(Collectors.toList());

            result.put("emails", emails);
            result.put("nextPageToken", response.getNextPageToken());
            return result;

        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Gmail messages", e);
        }
    }

    public List<com.emailfilter.model.EmailMessage> fetchAllEmails(String accessToken, int maxResultsPerPage) {
        return fetchAllEmails(accessToken, maxResultsPerPage, 200);
    }

    public List<com.emailfilter.model.EmailMessage> fetchAllEmails(String accessToken, int maxResultsPerPage, int maxTotal) {
        var inbox = fetchEmailsByQuery(accessToken, maxResultsPerPage, "in:inbox", maxTotal);
        var remaining = maxTotal - inbox.size();
        var spam = remaining > 0
                ? fetchEmailsByQuery(accessToken, maxResultsPerPage, "in:spam", remaining)
                : List.<com.emailfilter.model.EmailMessage>of();

        var all = new java.util.LinkedHashMap<String, com.emailfilter.model.EmailMessage>();
        for (var e : inbox) all.put(e.getProviderEmailId(), e);
        for (var e : spam) all.putIfAbsent(e.getProviderEmailId(), e);

        return new java.util.ArrayList<>(all.values());
    }

    /**
     * Incremental sync: fetch only message IDs newer than `since`, then fetch full
     * details only for messages whose IDs are NOT in `existingIds`.
     * Uses `after:` Gmail query (5 quota units) + metadata format for listing,
     * then full format only for genuinely new messages.
     */
    public List<com.emailfilter.model.EmailMessage> fetchIncrementalEmails(
            String accessToken, java.time.Instant since, java.util.Set<String> existingIds) {
        var gmail = buildGmailClient(accessToken);
        var newEmails = new java.util.ArrayList<com.emailfilter.model.EmailMessage>();

        // Format date for Gmail's after: query (YYYY/MM/DD)
        var dateFormatter = new java.text.SimpleDateFormat("yyyy/MM/dd");
        var sinceDate = dateFormatter.format(java.util.Date.from(since));
        var query = "in:inbox after:" + sinceDate;

        try {
            String pageToken = null;
            do {
                var request = gmail.users().messages().list("me")
                        .setQ(query)
                        .setMaxResults(100L);
                if (pageToken != null) {
                    request.setPageToken(pageToken);
                }

                var response = request.execute();
                var messages = response.getMessages();
                if (messages == null || messages.isEmpty()) break;

                for (var msg : messages) {
                    // Skip messages we already have in the DB
                    if (existingIds.contains(msg.getId())) continue;
                    // Fetch full details only for new messages
                    newEmails.add(fetchMessageDetail(gmail, msg));
                }

                pageToken = response.getNextPageToken();
            } while (pageToken != null && !pageToken.isBlank());

            log.info("Incremental fetch: {} new emails found (skipped {} existing)", newEmails.size(), existingIds.size());
            return newEmails;

        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch incremental Gmail messages", e);
        }
    }

    private List<com.emailfilter.model.EmailMessage> fetchEmailsByQuery(String accessToken, int maxResultsPerPage, String query, int maxTotal) {
        var gmail = buildGmailClient(accessToken);
        var allEmails = new java.util.ArrayList<com.emailfilter.model.EmailMessage>();
        String pageToken = null;
        int retryCount = 0;
        int maxRetries = 3;

        try {
            do {
                var request = gmail.users().messages().list("me")
                        .setQ(query)
                        .setMaxResults((long) Math.min(maxResultsPerPage, maxTotal - allEmails.size()));
                if (pageToken != null) {
                    request.setPageToken(pageToken);
                }

                com.google.api.services.gmail.model.ListMessagesResponse response;
                try {
                    response = request.execute();
                    retryCount = 0;
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                    if (e.getStatusCode() == 429 && retryCount < maxRetries) {
                        retryCount++;
                        var delay = (long) Math.pow(2, retryCount) * 1000;
                        Thread.sleep(delay);
                        continue;
                    }
                    throw e;
                }

                var messages = response.getMessages();

                if (messages == null || messages.isEmpty()) break;

                for (var msg : messages) {
                    if (allEmails.size() >= maxTotal) break;
                    allEmails.add(fetchMessageDetail(gmail, msg));
                }

                pageToken = response.getNextPageToken();
            } while (pageToken != null && !pageToken.isBlank() && allEmails.size() < maxTotal);

            return allEmails;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gmail fetch interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Gmail messages for query: " + query, e);
        }
    }

    private com.emailfilter.model.EmailMessage fetchMessageDetail(Gmail gmail, Message msg) {
        try {
            var full = gmail.users().messages().get("me", msg.getId()).setFormat("full").execute();
            var payload = full.getPayload();

            var headers = payload.getHeaders();
            var subject = getHeader(headers, "Subject");
            var from = getHeader(headers, "From");
            var to = getHeader(headers, "To");
            var snippet = full.getSnippet();

            var body = "";
            if (payload.getParts() != null) {
                body = getBodyFromParts(payload.getParts());
            } else if (payload.getBody() != null && payload.getBody().getData() != null) {
                body = new String(Base64.getUrlDecoder().decode(payload.getBody().getData()));
            }

            var labels = String.join(",", full.getLabelIds() != null ? full.getLabelIds() : List.of());
            var isRead = !full.getLabelIds().contains("UNREAD");
            var isStarred = full.getLabelIds().contains("STARRED");

            var receivedAt = parseReceivedAt(full.getInternalDate(), getHeader(headers, "Date"));

            var email = new com.emailfilter.model.EmailMessage();
            email.setProviderEmailId(full.getId());
            email.setThreadId(full.getThreadId());
            email.setSubject(subject);
            email.setFromAddress(from);
            email.setToAddresses(to);
            email.setBodyPreview(snippet);
            email.setBodyHtml(body);
            email.setLabels(labels);
            email.setRead(isRead);
            email.setStarred(isStarred);
            email.setReceivedAt(receivedAt);
            return email;

        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Gmail message detail", e);
        }
    }

    public void markAsRead(String accessToken, String messageId) {
        modifyMessage(accessToken, messageId, List.of("UNREAD"), List.of());
    }

    public void markAsUnread(String accessToken, String messageId) {
        modifyMessage(accessToken, messageId, List.of(), List.of("UNREAD"));
    }

    public void toggleStar(String accessToken, String messageId, boolean starred) {
        if (starred) {
            modifyMessage(accessToken, messageId, List.of("STARRED"), List.of());
        } else {
            modifyMessage(accessToken, messageId, List.of(), List.of("STARRED"));
        }
    }

    public void moveToTrash(String accessToken, String messageId) {
        modifyMessage(accessToken, messageId, List.of("TRASH"), List.of());
    }

    private void modifyMessage(String accessToken, String messageId,
                               List<String> addLabels, List<String> removeLabels) {
        var gmail = buildGmailClient(accessToken);
        try {
            var modRequest = new ModifyMessageRequest()
                    .setAddLabelIds(addLabels)
                    .setRemoveLabelIds(removeLabels);
            gmail.users().messages().modify("me", messageId, modRequest).execute();

        } catch (IOException e) {
            throw new RuntimeException("Failed to modify Gmail message", e);
        }
    }

    private Instant parseReceivedAt(Long internalDateMillis, String dateHeader) {
        if (internalDateMillis != null && internalDateMillis > 0) {
            return Instant.ofEpochMilli(internalDateMillis);
        }
        if (dateHeader != null && !dateHeader.isBlank()) {
            try {
                var parsed = java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.FULL, java.text.DateFormat.FULL)
                        .parse(dateHeader);
                if (parsed != null) return parsed.toInstant();
            } catch (java.text.ParseException e) {
                // try RFC 2822 fallback
                try {
                    var rfc2822 = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US);
                    var parsed = rfc2822.parse(dateHeader);
                    if (parsed != null) return parsed.toInstant();
                } catch (java.text.ParseException e2) {
                    // ignore, return current time as fallback
                }
            }
        }
        return Instant.now();
    }

    private String getHeader(List<MessagePartHeader> headers, String name) {
        return headers.stream()
                .filter(h -> h.getName().equalsIgnoreCase(name))
                .map(MessagePartHeader::getValue)
                .findFirst()
                .orElse("");
    }

    public String getBodyFromPartsPublic(List<MessagePart> parts) {
        return getBodyFromParts(parts);
    }

    private String getBodyFromParts(List<MessagePart> parts) {
        var plainText = new StringBuilder();
        var htmlText = new StringBuilder();
        for (var part : parts) {
            if (part.getMimeType().equals("text/plain") && part.getBody().getData() != null) {
                plainText.append(new String(Base64.getUrlDecoder().decode(part.getBody().getData())));
            }
            if (part.getMimeType().equals("text/html") && part.getBody().getData() != null) {
                htmlText.append(new String(Base64.getUrlDecoder().decode(part.getBody().getData())));
            }
            if (part.getParts() != null) {
                var nested = getBodyFromParts(part.getParts());
                if (!nested.isEmpty()) {
                    if (plainText.isEmpty()) plainText.append(nested);
                    else htmlText.append(nested);
                }
            }
        }
        return !plainText.isEmpty() ? plainText.toString() : htmlText.toString();
    }
}
