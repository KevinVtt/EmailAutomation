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
