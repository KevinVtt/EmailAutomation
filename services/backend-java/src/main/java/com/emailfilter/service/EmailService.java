package com.emailfilter.service;

import com.emailfilter.dto.EmailDTO;
import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.OAuthToken;
import com.emailfilter.model.User;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.OAuthTokenRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailRepository emailRepository;
    private final OAuthTokenRepository oauthTokenRepository;
    private final GmailService gmailService;
    private final OutlookService outlookService;
    private final WebSocketService webSocketService;

    private final ConcurrentHashMap<UUID, Boolean> syncingStatus = new ConcurrentHashMap<>();
    private static final long SYNC_STATUS_TTL_MS = 5 * 60 * 1000L; // 5 minutes
    private final ConcurrentHashMap<UUID, Long> syncingTimestamps = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Page<EmailDTO> getEmails(UUID userId, int page, int size) {
        return emailRepository.findByUserIdOrderByReceivedAtDesc(userId, PageRequest.of(page, size))
                .map(this::toDTO);
    }

    @Transactional(readOnly = true)
    public EmailDTO getEmailById(UUID userId, UUID emailId) {
        var email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found: " + emailId));
        if (!email.getUser().getId().equals(userId)) {
            throw new RuntimeException("Email does not belong to user");
        }
        return toDTO(email);
    }

    @Transactional(readOnly = true)
    public Page<EmailDTO> filterEmails(UUID userId, Map<String, String> filters, int page, int size) {
        Specification<EmailMessage> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("user").get("id"), userId));

            filters.forEach((key, value) -> {
                if (value == null || value.isBlank()) return;
                switch (key) {
                    case "fromAddress" ->
                        predicates.add(cb.like(cb.lower(root.get("fromAddress")), "%" + escapeLike(value.toLowerCase()) + "%"));
                    case "subjectContains" ->
                        predicates.add(cb.like(cb.lower(root.get("subject")), "%" + escapeLike(value.toLowerCase()) + "%"));
                    case "bodyContains" ->
                        predicates.add(cb.like(cb.lower(root.get("bodyPreview")), "%" + escapeLike(value.toLowerCase()) + "%"));
                    case "isRead" -> predicates.add(cb.equal(root.get("isRead"), Boolean.parseBoolean(value)));
                    case "isStarred" -> predicates.add(cb.equal(root.get("isStarred"), Boolean.parseBoolean(value)));
                    case "important" ->
                        predicates.add(cb.like(root.get("labels"), "%IMPORTANT%"));
                    case "label" ->
                        predicates.add(cb.like(root.get("labels"), "%" + escapeLike(value) + "%"));
                    case "dateFrom" ->
                        predicates.add(cb.greaterThanOrEqualTo(root.get("receivedAt"), Instant.parse(value)));
                    case "dateTo" ->
                        predicates.add(cb.lessThanOrEqualTo(root.get("receivedAt"), Instant.parse(value)));
                }
            });

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));
        return emailRepository.findAll(spec, pageable).map(this::toDTO);
    }

    public boolean isSyncing(UUID userId) {
        cleanupExpiredSyncStatus();
        return syncingStatus.getOrDefault(userId, false);
    }

    @Transactional(readOnly = true)
    public long getEmailCount(UUID userId) {
        return emailRepository.count();
    }

    @Transactional(readOnly = true)
    public long getEmailCountByUser(UUID userId) {
        return emailRepository.findByUserIdOrderByReceivedAtDesc(userId, PageRequest.of(0, 1)).getTotalElements();
    }

    private void cleanupExpiredSyncStatus() {
        long now = System.currentTimeMillis();
        syncingTimestamps.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > SYNC_STATUS_TTL_MS) {
                syncingStatus.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    @Async("emailSyncExecutor")
    public CompletableFuture<Void> syncEmailsAsync(User user, String provider) {
        var userId = user.getId();
        log.info("Starting email sync for user={}, provider={}", userId, provider);
        if (syncingStatus.putIfAbsent(userId, true) != null) {
            log.info("Sync already in progress for user={}", userId);
            return CompletableFuture.completedFuture(null);
        }
        syncingTimestamps.put(userId, System.currentTimeMillis());
        try {
            log.info("Fetching OAuth token for user={}, provider={}", userId, provider);
            var accessToken = getToken(user, provider);
            log.info("OAuth token obtained for user={}, provider={}", userId, provider);

            List<EmailMessage> emails;
            if ("google".equals(provider)) {
                // Check if we already have emails for this user
                var lastEmailOpt = emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId);
                if (lastEmailOpt.isPresent()) {
                    // Incremental sync: fetch only new emails since last sync
                    var lastReceivedAt = lastEmailOpt.get().getReceivedAt();
                    log.info("Incremental sync for user={}, fetching emails after {}", userId, lastReceivedAt);

                    // Get existing provider email IDs to skip (lightweight query, no full entities)
                    var existingIds = new java.util.HashSet<>(emailRepository.findProviderEmailIdsByUserId(userId));

                    emails = gmailService.fetchIncrementalEmails(accessToken, lastReceivedAt, existingIds);
                    log.info("Incremental fetch: {} new emails for user={}", emails.size(), userId);
                } else {
                    // First sync: fetch all emails
                    log.info("First sync for user={}, fetching all emails", userId);
                    emails = gmailService.fetchAllEmails(accessToken, 100, 200);
                    log.info("Initial fetch: {} emails for user={}", emails.size(), userId);
                }
            } else if ("outlook".equals(provider)) {
                log.info("Fetching emails from Outlook for user={}", userId);
                var rawEmails = outlookService.fetchEmails(accessToken, 100);
                emails = rawEmails.stream()
                        .map(raw -> parseOutlookEmail(raw, user))
                        .collect(Collectors.toList());
                log.info("Fetched {} emails from Outlook for user={}", emails.size(), userId);
            } else {
                throw new IllegalArgumentException("Unsupported provider: " + provider);
            }

            int syncedCount = saveEmails(emails, user, provider);
            log.info("Sync complete: {} new emails saved for user={}, provider={} ({} total fetched)", syncedCount, userId, provider, emails.size());
            webSocketService.sendNotification(userId.toString(), "sync_complete");
        } catch (RuntimeException e) {
            log.error("Sync failed for user={}, provider={}: {}", userId, provider, e.getMessage(), e);
            webSocketService.sendNotification(userId.toString(), "sync_error: " + e.getMessage());
        } finally {
            syncingStatus.remove(userId);
            syncingTimestamps.remove(userId);
        }
        return CompletableFuture.completedFuture(null);
    }

    public int syncEmails(User user, String provider) {
        syncEmailsAsync(user, provider);
        return 0;
    }

    public void syncAllEmails(User user, String provider) {
        syncAllEmailsAsync(user, provider);
    }

    @Async("emailSyncExecutor")
    public CompletableFuture<Void> syncAllEmailsAsync(User user, String provider) {
        var userId = user.getId();
        log.info("Starting FULL email sync for user={}, provider={}", userId, provider);
        if (syncingStatus.putIfAbsent(userId, true) != null) {
            log.info("Sync already in progress for user={}", userId);
            return CompletableFuture.completedFuture(null);
        }
        syncingTimestamps.put(userId, System.currentTimeMillis());
        try {
            var accessToken = getToken(user, provider);

            if (!"google".equals(provider)) {
                // For non-Google, fall back to regular sync
                syncEmailsAsync(user, provider);
                return CompletableFuture.completedFuture(null);
            }

            // Step 1: Get ALL message IDs from Gmail (cheap: ~5 units per 100 IDs)
            log.info("Fetching all Gmail message IDs for user={}", userId);
            webSocketService.sendNotification(userId.toString(), "sync_progress:0:0");

            var gmail = buildGmailClient(accessToken);
            var allMessageIds = new ArrayList<String>();
            try {
                String pageToken = null;
                do {
                    var request = gmail.users().messages().list("me")
                            .setQ("in:inbox")
                            .setMaxResults(500L);
                    if (pageToken != null) request.setPageToken(pageToken);

                    var response = request.execute();
                    var messages = response.getMessages();
                    if (messages != null) {
                        for (var msg : messages) {
                            allMessageIds.add(msg.getId());
                        }
                    }
                    pageToken = response.getNextPageToken();
                } while (pageToken != null && !pageToken.isBlank());
            } catch (IOException e) {
                throw new RuntimeException("Failed to fetch Gmail message IDs", e);
            }

            var totalInGmail = allMessageIds.size();
            log.info("Found {} total emails in Gmail for user={}", totalInGmail, userId);

            // Step 2: Remove IDs we already have in the DB
            var existingIds = new java.util.HashSet<>(emailRepository.findProviderEmailIdsByUserId(userId));
            var newIds = allMessageIds.stream()
                    .filter(id -> !existingIds.contains(id))
                    .collect(Collectors.toList());

            log.info("Of {} total, {} already in DB, {} new to fetch for user={}",
                    totalInGmail, existingIds.size(), newIds.size(), userId);
            webSocketService.sendNotification(userId.toString(),
                    "sync_progress:0:" + newIds.size());

            if (newIds.isEmpty()) {
                log.info("No new emails to fetch for user={}", userId);
                webSocketService.sendNotification(userId.toString(), "sync_complete");
                return CompletableFuture.completedFuture(null);
            }

            // Step 3: Fetch new emails in batches of 50, send progress
            int fetched = 0;
            int batchSize = 50;
            for (int i = 0; i < newIds.size(); i += batchSize) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Full sync interrupted for user={}", userId);
                    break;
                }

                var batch = newIds.subList(i, Math.min(i + batchSize, newIds.size()));
                var batchEmails = new ArrayList<EmailMessage>();

                for (var msgId : batch) {
                    try {
                        var email = fetchMessageById(gmail, msgId);
                        if (email != null) batchEmails.add(email);
                    } catch (Exception e) {
                        log.warn("Failed to fetch message {}: {}", msgId, e.getMessage());
                    }
                }

                // Save batch
                if (!batchEmails.isEmpty()) {
                    saveEmails(batchEmails, user, provider);
                }

                fetched += batch.size();
                webSocketService.sendNotification(userId.toString(),
                        "sync_progress:" + fetched + ":" + newIds.size());

                if (fetched % 500 == 0) {
                    log.info("Full sync progress: {}/{} for user={}", fetched, newIds.size(), userId);
                }
            }

            log.info("Full sync complete: {} new emails saved for user={} ({} total in Gmail)",
                    fetched, userId, totalInGmail);
            webSocketService.sendNotification(userId.toString(), "sync_complete");

        } catch (RuntimeException e) {
            log.error("Full sync failed for user={}, provider={}: {}", userId, provider, e.getMessage(), e);
            webSocketService.sendNotification(userId.toString(), "sync_error:" + e.getMessage());
        } finally {
            syncingStatus.remove(userId);
            syncingTimestamps.remove(userId);
        }
        return CompletableFuture.completedFuture(null);
    }

    private com.google.api.services.gmail.Gmail buildGmailClient(String accessToken) {
        try {
            com.google.api.client.http.HttpRequestInitializer requestInitializer =
                    request -> request.getHeaders().setAuthorization("Bearer " + accessToken);
            return new com.google.api.services.gmail.Gmail.Builder(
                    com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                    com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                    requestInitializer)
                    .setApplicationName("Email Filter AI")
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gmail client", e);
        }
    }

    private EmailMessage fetchMessageById(com.google.api.services.gmail.Gmail gmail, String messageId) {
        try {
            var full = gmail.users().messages().get("me", messageId).setFormat("full").execute();
            var payload = full.getPayload();
            if (payload == null) return null;

            var headers = payload.getHeaders();
            var subject = getHeaderValue(headers, "Subject");
            var from = getHeaderValue(headers, "From");
            var to = getHeaderValue(headers, "To");
            var snippet = full.getSnippet();

            var body = "";
            if (payload.getParts() != null) {
                body = gmailService.getBodyFromPartsPublic(payload.getParts());
            } else if (payload.getBody() != null && payload.getBody().getData() != null) {
                body = new String(java.util.Base64.getUrlDecoder().decode(payload.getBody().getData()));
            }

            var labels = String.join(",", full.getLabelIds() != null ? full.getLabelIds() : List.of());
            var isRead = !full.getLabelIds().contains("UNREAD");
            var isStarred = full.getLabelIds().contains("STARRED");

            var receivedAt = Instant.ofEpochMilli(full.getInternalDate());

            var email = new EmailMessage();
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
            log.warn("Failed to fetch message {}: {}", messageId, e.getMessage());
            return null;
        }
    }

    private String getHeaderValue(List<com.google.api.services.gmail.model.MessagePartHeader> headers, String name) {
        return headers.stream()
                .filter(h -> h.getName().equalsIgnoreCase(name))
                .map(com.google.api.services.gmail.model.MessagePartHeader::getValue)
                .findFirst()
                .orElse("");
    }

    @Transactional
    public int saveEmails(List<EmailMessage> emails, User user, String provider) {
        int syncedCount = 0;
        var newEmails = new ArrayList<EmailMessage>();
        for (var email : emails) {
            if (!emailRepository.existsByUserIdAndProviderEmailId(user.getId(), email.getProviderEmailId())) {
                email.setUser(user);
                email.setProvider(provider);
                newEmails.add(email);
                syncedCount++;
            }
        }
        if (!newEmails.isEmpty()) {
            emailRepository.saveAll(newEmails);
        }
        return syncedCount;
    }

    @Transactional
    public void markAsRead(User user, String provider, String emailId) {
        var token = getToken(user, provider);
        if ("google".equals(provider)) gmailService.markAsRead(token, emailId);
        else outlookService.markAsRead(token, emailId);
    }

    @Transactional
    public void markAsUnread(User user, String provider, String emailId) {
        var token = getToken(user, provider);
        if ("google".equals(provider)) gmailService.markAsUnread(token, emailId);
        else outlookService.markAsUnread(token, emailId);
    }

    @Transactional
    public void toggleStar(User user, String provider, String emailId, boolean starred) {
        var token = getToken(user, provider);
        if ("google".equals(provider)) gmailService.toggleStar(token, emailId, starred);
        else outlookService.toggleStar(token, emailId, starred);
    }

    @Transactional
    public void moveToTrash(User user, String provider, String emailId) {
        var token = getToken(user, provider);
        if ("google".equals(provider)) gmailService.moveToTrash(token, emailId);
        else outlookService.moveToTrash(token, emailId);
    }

    private String getToken(User user, String provider) {
        var oauthToken = oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(user.getId(), provider)
                .orElseThrow(() -> new RuntimeException("No OAuth token found for provider: " + provider));

        if (oauthToken.getExpiresAt() != null && Instant.now().isAfter(oauthToken.getExpiresAt())) {
            log.info("OAuth token expired for user={}, provider={}. Attempting refresh.", user.getId(), provider);
            String newToken = refreshToken(oauthToken);
            oauthToken.setAccessToken(newToken);
            oauthToken.setExpiresAt(Instant.now().plusSeconds(3600));
            oauthTokenRepository.save(oauthToken);
            return newToken;
        }

        log.debug("Using stored OAuth token for user={}, provider={}", user.getId(), provider);
        return oauthToken.getAccessToken();
    }

    private String refreshToken(OAuthToken oauthToken) {
        try {
            if ("google".equals(oauthToken.getProvider())) {
                return refreshGoogleToken(oauthToken.getRefreshToken());
            } else if ("outlook".equals(oauthToken.getProvider())) {
                return outlookService.refreshAccessToken(
                        oauthToken.getRefreshToken(),
                        System.getenv("OUTLOOK_CLIENT_ID"),
                        System.getenv("OUTLOOK_CLIENT_SECRET"),
                        System.getenv().getOrDefault("OUTLOOK_TENANT", "common")
                );
            }
            throw new RuntimeException("Unsupported provider for token refresh: " + oauthToken.getProvider());
        } catch (Exception e) {
            log.error("Failed to refresh OAuth token for provider={}, userId={}", oauthToken.getProvider(),
                    oauthToken.getUser() != null ? oauthToken.getUser().getId() : "unknown", e);
            throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
        }
    }

    private String refreshGoogleToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new RuntimeException("Cannot refresh Google token: no refresh token available");
        }
        var body = new LinkedMultiValueMap<String, String>();
        body.add("client_id", System.getenv("GOOGLE_CLIENT_ID"));
        body.add("client_secret", System.getenv("GOOGLE_CLIENT_SECRET"));
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        var response = new RestTemplate().exchange(
                "https://oauth2.googleapis.com/token",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        ).getBody();

        if (response == null || !response.containsKey("access_token")) {
            throw new RuntimeException("Failed to refresh Google token");
        }
        return response.get("access_token").toString();
    }

    private EmailDTO toDTO(EmailMessage email) {
        return EmailDTO.builder()
                .id(email.getId())
                .provider(email.getProvider())
                .providerEmailId(email.getProviderEmailId())
                .threadId(email.getThreadId())
                .fromAddress(email.getFromAddress())
                .fromName(email.getFromName())
                .toAddresses(email.getToAddresses())
                .subject(email.getSubject())
                .bodyPreview(email.getBodyPreview())
                .bodyHtml(email.getBodyHtml())
                .isRead(email.isRead())
                .isStarred(email.isStarred())
                .labels(email.getLabels())
                .receivedAt(email.getReceivedAt())
                .fetchedAt(email.getFetchedAt())
                .build();
    }

    private String escapeLike(String value) {
        return value.replace("%", "\\%").replace("_", "\\_");
    }

    @SuppressWarnings("unchecked")
    private EmailMessage parseOutlookEmail(Map<String, Object> raw, User user) {
        var email = new EmailMessage();
        email.setProviderEmailId((String) raw.get("id"));

        Object fromObj = raw.get("from");
        if (fromObj instanceof Map<?, ?> from && from.get("emailAddress") instanceof Map<?, ?> emailAddr) {
            email.setFromAddress((String) emailAddr.get("address"));
            email.setFromName((String) emailAddr.get("name"));
        }

        email.setSubject((String) raw.get("subject"));
        email.setBodyPreview((String) raw.get("bodyPreview"));

        Object bodyObj = raw.get("body");
        if (bodyObj instanceof Map<?, ?> body && body.get("content") instanceof String content) {
            email.setBodyHtml(content);
        }

        email.setRead(Boolean.TRUE.equals(raw.get("isRead")));

        Object categoriesObj = raw.get("categories");
        if (categoriesObj instanceof List<?> categories) {
            email.setLabels(categories.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.joining(",")));
        }

        var receivedStr = (String) raw.get("receivedDateTime");
        if (receivedStr != null) {
            email.setReceivedAt(Instant.parse(receivedStr));
        }

        return email;
    }
}
