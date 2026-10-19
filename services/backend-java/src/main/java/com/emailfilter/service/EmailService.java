package com.emailfilter.service;

import com.emailfilter.dto.BulkActionRequest;
import com.emailfilter.dto.ComposeRequest;
import com.emailfilter.dto.EmailDTO;
import com.emailfilter.dto.GmailHistoryChanges;
import com.emailfilter.dto.PrioritySummaryDTO;
import com.emailfilter.exception.TransientApiException;
import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.OAuthToken;
import com.emailfilter.model.SyncJob;
import com.emailfilter.model.User;
import com.emailfilter.model.Visto;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.OAuthTokenRepository;
import com.emailfilter.repository.UserRepository;
import com.emailfilter.repository.VistoRepository;
import com.emailfilter.security.TokenCryptoService;
import com.emailfilter.util.QuotedPrintable;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final VistoRepository vistoRepository;
    private final GmailService gmailService;
    private final OutlookService outlookService;
    private final WebSocketService webSocketService;
    private final AIServiceClient aiServiceClient;
    private final HtmlSanitizerService htmlSanitizerService;
    private final SyncJobService syncJobService;
    private final UserRepository userRepository;
    private final TokenCryptoService tokenCryptoService;
    private final ContactService contactService;

    @Value("${email.sync.max-emails:1000}")
    int maxEmailsPerSync;

    /** Ventana del primer barrido de enviados y tope de mails leídos (20 unidades cada uno). */
    @Value("${email.contacts.sent-window-days:365}")
    int contactsSentWindowDays;
    @Value("${email.contacts.sent-max:2000}")
    int contactsSentMax;

    private final ConcurrentHashMap<UUID, Boolean> syncingStatus = new ConcurrentHashMap<>();
    private static final long SYNC_STATUS_TTL_MS = 5 * 60 * 1000L; // 5 minutes
    private static final int EXISTING_IDS_PAGE_SIZE = 1000;
    private final ConcurrentHashMap<UUID, Long> syncingTimestamps = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Page<EmailDTO> getEmails(UUID userId, int page, int size) {
        var emailPage = emailRepository.findByUserIdOrderByReceivedAtDesc(userId, PageRequest.of(page, size));
        var vistoEmailIds = fetchVistoEmailIds(userId, emailPage.getContent());
        return emailPage.map(email -> toDTO(email, vistoEmailIds));
    }

    @Transactional(readOnly = true)
    public EmailDTO getEmailById(UUID userId, UUID emailId) {
        var email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found: " + emailId));
        if (!email.getUser().getId().equals(userId)) {
            throw new RuntimeException("Email does not belong to user");
        }
        return toDTO(email, fetchVistoEmailIds(userId, List.of(email)));
    }

    @Transactional(readOnly = true)
    public Page<EmailDTO> filterEmails(UUID userId, Map<String, String> filters, int page, int size) {
        Specification<EmailMessage> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("user").get("id"), userId));

            // subjectContains and bodyContains are evaluated together with OR semantics:
            // a match in either the subject OR the body is enough. Combining them with
            // AND (as before) almost always returned zero results, because the AI sends
            // the same search terms in both fields.
            var subjectRaw = filters.get("subjectContains");
            var bodyRaw = filters.get("bodyContains");
            Predicate subjectPredicate = (subjectRaw == null || subjectRaw.isBlank())
                    ? null
                    : buildContainsPredicate(cb, root.get("subject"), subjectRaw);
            Predicate bodyPredicate = (bodyRaw == null || bodyRaw.isBlank())
                    ? null
                    : buildContainsPredicate(cb, root.get("bodyPreview"), bodyRaw);

            for (var entry : filters.entrySet()) {
                var key = entry.getKey();
                var value = entry.getValue();
                if (value == null || value.isBlank()) continue;
                switch (key) {
                    case "fromAddress" ->
                        predicates.add(cb.like(cb.lower(root.get("fromAddress")), "%" + escapeLike(value.toLowerCase()) + "%"));
                    case "subjectContains", "bodyContains" -> {
                        // Handled together below to apply OR semantics.
                    }
                    case "isRead" -> predicates.add(cb.equal(root.get("isRead"), Boolean.parseBoolean(value)));
                    case "isStarred" -> predicates.add(cb.equal(root.get("isStarred"), Boolean.parseBoolean(value)));
                    case "important" ->
                        predicates.add(cb.like(root.get("labels"), "%IMPORTANT%"));
                    case "destacados" ->
                        predicates.add(cb.or(
                                cb.equal(root.get("isStarred"), true),
                                cb.like(root.get("labels"), "%IMPORTANT%")));
                    case "label" ->
                        predicates.add(cb.like(root.get("labels"), "%" + escapeLike(value) + "%"));
                    case "dateFrom" ->
                        predicates.add(cb.greaterThanOrEqualTo(root.get("receivedAt"), Instant.parse(value)));
                    case "dateTo" ->
                        // Exclusive upper bound: the AI sends 00:00 of the day after the
                        // requested range, so use < (not <=) to avoid leaking that day.
                        predicates.add(cb.lessThan(root.get("receivedAt"), Instant.parse(value)));
                }
            }

            if (subjectPredicate != null || bodyPredicate != null) {
                if (subjectPredicate != null && bodyPredicate != null) {
                    predicates.add(cb.or(subjectPredicate, bodyPredicate));
                } else {
                    predicates.add(subjectPredicate != null ? subjectPredicate : bodyPredicate);
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));
        var emailPage = emailRepository.findAll(spec, pageable);
        var vistoEmailIds = fetchVistoEmailIds(userId, emailPage.getContent());
        return emailPage.map(email -> toDTO(email, vistoEmailIds));
    }

    /**
     * Builds a "contains" predicate for a text column. Comma-separated terms are
     * combined with OR, so "term1,term2" matches rows containing either term.
     */
    private Predicate buildContainsPredicate(CriteriaBuilder cb, Path<String> path, String value) {
        var orPredicates = new ArrayList<Predicate>();
        for (var term : value.split(",")) {
            var trimmed = term.trim();
            if (!trimmed.isEmpty()) {
                orPredicates.add(cb.like(cb.lower(path), "%" + escapeLike(trimmed.toLowerCase()) + "%"));
            }
        }
        if (orPredicates.isEmpty()) {
            return cb.conjunction();
        }
        if (orPredicates.size() == 1) {
            return orPredicates.get(0);
        }
        return cb.or(orPredicates.toArray(new Predicate[0]));
    }

    public boolean isSyncing(UUID userId) {
        cleanupExpiredSyncStatus();
        return syncingStatus.getOrDefault(userId, false);
    }

    @Transactional(readOnly = true)
    public long getEmailCountByUser(UUID userId) {
        return emailRepository.findByUserIdOrderByReceivedAtDesc(userId, PageRequest.of(0, 1)).getTotalElements();
    }

    /**
     * Zona usada para calcular "hoy"/"ayer" en resúmenes y stats: Buenos Aires,
     * no la del servidor (dentro de Docker la hora del sistema es UTC). Si no hay
     * base IANA de zonas (p. ej. Windows), cae a UTC-3 fijo, la de BA desde 2009.
     */
    static ZoneId getSummaryZone() {
        try {
            return ZoneId.of("America/Argentina/Buenos_Aires");
        } catch (Exception e) {
            return ZoneOffset.ofHours(-3);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDailySummary(UUID userId, String date) {
        var zone = getSummaryZone();
        LocalDate targetDate;

        if ("today".equalsIgnoreCase(date)) {
            targetDate = LocalDate.now(zone);
        } else if ("yesterday".equalsIgnoreCase(date)) {
            targetDate = LocalDate.now(zone).minusDays(1);
        } else {
            targetDate = LocalDate.parse(date);
        }

        var dayStart = targetDate.atStartOfDay(zone).toInstant();
        var dayEnd = targetDate.plusDays(1).atStartOfDay(zone).toInstant();

        var filters = Map.of("dateFrom", dayStart.toString(), "dateTo", dayEnd.minusNanos(1).toString());
        var emailPage = filterEmails(userId, filters, 0, 100);
        var dtos = emailPage.getContent();
        var totalElements = emailPage.getTotalElements();

        if (dtos.isEmpty()) {
            return Map.of(
                    "summary", "No hay correos para resumir.",
                    "emailCount", 0,
                    "date", targetDate.toString()
            );
        }

        var summary = aiServiceClient.summarizeEmails(dtos);
        return Map.of(
                "summary", summary,
                "emailCount", totalElements,
                "date", targetDate.toString()
        );
    }

    /**
     * Stats del buzón (`GET /api/emails/stats`): totales + últimos 7 días agrupados
     * por fecha en la zona de resumen. {@code perDay} tiene siempre 7 entradas
     * (la más vieja primero) y los días sin correos cuentan 0.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStats(UUID userId) {
        var zone = getSummaryZone();
        var today = LocalDate.now(zone);
        var firstDay = today.minusDays(6);
        var since = firstDay.atStartOfDay(zone).toInstant();

        var perDay = new LinkedHashMap<LocalDate, Long>();
        for (int i = 0; i < 7; i++) {
            perDay.put(firstDay.plusDays(i), 0L);
        }
        for (var receivedAt : emailRepository.findReceivedAtByUserIdSince(userId, since)) {
            if (receivedAt == null) continue;
            var day = receivedAt.atZone(zone).toLocalDate();
            if (perDay.containsKey(day)) {
                perDay.merge(day, 1L, Long::sum);
            }
        }
        var perDayList = perDay.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey().toString(), "count", e.getValue()))
                .toList();

        var result = new LinkedHashMap<String, Object>();
        result.put("total", getEmailCountByUser(userId));
        result.put("unread", emailRepository.countUnreadByUserId(userId));
        result.put("starred", emailRepository.countStarredByUserId(userId));
        result.put("spam", emailRepository.countSpamByUserId(userId));
        result.put("perDay", perDayList);
        return result;
    }

    @Transactional(readOnly = true)
    public PrioritySummaryDTO buildPrioritySummary(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        long urgentes = emailRepository.countUrgentesByUserId(userId);
        long destacadosSinLeer = emailRepository.countDestacadosSinLeerByUserId(userId);
        long pendientesRespuesta = emailRepository.countPendientesRespuestaByUserId(userId, user.getEmail());
        return new PrioritySummaryDTO(urgentes, destacadosSinLeer, pendientesRespuesta);
    }

    private void notifyUrgentEmails(User user, List<EmailMessage> emails) {
        try {
            var capped = emails.size() > 20 ? emails.subList(0, 20) : emails;
            var dtos = capped.stream()
                    .map(e -> toDTO(e, Set.of()))
                    .toList();

            if (dtos.isEmpty()) return;

            var result = aiServiceClient.analyzeEmails(dtos);
            if (result == null) return;

            var classifications = result.get("classifications");
            if (!(classifications instanceof List<?> classList)) return;

            var urgentSubjects = new ArrayList<String>();
            for (var entry : classList) {
                if (!(entry instanceof Map<?, ?> cls)) continue;
                var urgency = cls.get("urgency");
                if (!"high".equals(urgency)) continue;
                var id = cls.get("id");
                for (var dto : dtos) {
                    if (id != null && id.toString().equals(dto.getProviderEmailId())) {
                        var subject = dto.getSubject();
                        if (subject != null && subject.length() > 60) {
                            subject = subject.substring(0, 60);
                        }
                        urgentSubjects.add(subject != null ? subject : "(sin asunto)");
                        break;
                    }
                }
            }

            if (!urgentSubjects.isEmpty()) {
                var count = urgentSubjects.size();
                var joined = urgentSubjects.stream()
                        .limit(3)
                        .reduce((a, b) -> a + " | " + b)
                        .orElse("");
                var notification = "urgent_emails:" + count + ":" + joined;
                webSocketService.sendNotification(user.getId().toString(), notification);
                log.info("Urgent email notification sent to user={}: {} urgent emails", user.getId(), count);
            }
        } catch (Exception e) {
            log.warn("Urgent email analysis failed (best-effort): {}", e.getMessage(), e);
        }
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

    private Set<String> fetchAllProviderEmailIds(UUID userId) {
        var existingIds = new HashSet<String>();
        int pageNumber = 0;
        Page<String> chunk;
        do {
            chunk = emailRepository.findProviderEmailIdsByUserId(userId, PageRequest.of(pageNumber, EXISTING_IDS_PAGE_SIZE));
            existingIds.addAll(chunk.getContent());
            pageNumber++;
        } while (chunk.hasNext());
        return existingIds;
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
        SyncJob job = null;
        try {
            job = syncJobService.start(userId, provider, "incremental");
            log.info("Fetching OAuth token for user={}, provider={}", userId, provider);
            var accessToken = getToken(user, provider);
            log.info("OAuth token obtained for user={}, provider={}", userId, provider);

            List<EmailMessage> emails;
            if ("google".equals(provider)) {
                emails = syncGoogle(user, accessToken);
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
            syncJobService.updateProgress(job.getId(), emails.size(), emails.size());
            if ("google".equals(provider)) {
                refreshLabelsAndStars(user, accessToken);
            }
            notifyUrgentEmails(user, emails);
            syncJobService.complete(job.getId());
            log.info("Sync complete: {} new emails saved for user={}, provider={} ({} total fetched)", syncedCount, userId, provider, emails.size());
            refreshContacts(user);
            webSocketService.sendNotification(userId.toString(), "sync_complete");
        } catch (RuntimeException e) {
            if (job != null) {
                syncJobService.fail(job.getId(), e.getMessage());
            }
            log.error("Sync failed for user={}, provider={}: {}", userId, provider, e.getMessage(), e);
            webSocketService.sendNotification(userId.toString(), "sync_error: " + e.getMessage());
        } finally {
            syncingStatus.remove(userId);
            syncingTimestamps.remove(userId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Async("emailSyncExecutor")
    public CompletableFuture<Void> syncAllEmailsAsync(User user, String provider) {
        var userId = user.getId();
        log.info("Starting FULL email sync for user={}, provider={}", userId, provider);

        // Delegar ANTES de tomar el lock: syncEmailsAsync toma ese mismo lock y, si ya
        // estuviera tomado, volvería "already in progress" sin sincronizar nada.
        if (!"google".equals(provider)) {
            // Providers sin full sync paginado: caen al sync incremental.
            syncEmailsAsync(user, provider);
            return CompletableFuture.completedFuture(null);
        }

        if (syncingStatus.putIfAbsent(userId, true) != null) {
            log.info("Sync already in progress for user={}", userId);
            return CompletableFuture.completedFuture(null);
        }
        syncingTimestamps.put(userId, System.currentTimeMillis());
        SyncJob job = null;
        try {
            var accessToken = getToken(user, provider);

            job = syncJobService.start(userId, provider, "full");

            // Step 1: Get ALL message IDs from Gmail (cheap: ~5 units per 100 IDs)
            log.info("Fetching all Gmail message IDs for user={}", userId);
            webSocketService.sendNotification(userId.toString(), "sync_progress:0:0");

            var gmail = buildGmailClient(accessToken);
            var allMessageIds = new LinkedHashSet<String>();
            try {
                // List IDs from BOTH inbox and spam (spam was previously never listed in full sync)
                allMessageIds.addAll(fetchAllMessageIds(gmail, "in:inbox"));
                allMessageIds.addAll(fetchAllMessageIds(gmail, "in:spam"));
            } catch (IOException e) {
                throw new RuntimeException("Failed to fetch Gmail message IDs", e);
            }

            var totalInGmail = allMessageIds.size();
            log.info("Found {} total emails in Gmail for user={}", totalInGmail, userId);

            // Step 2: Remove IDs we already have in the DB
            var existingIds = fetchAllProviderEmailIds(userId);
            var newIds = allMessageIds.stream()
                    .filter(id -> !existingIds.contains(id))
                    .collect(Collectors.toList());

            log.info("Of {} total, {} already in DB, {} new to fetch for user={}",
                    totalInGmail, existingIds.size(), newIds.size(), userId);
            webSocketService.sendNotification(userId.toString(),
                    "sync_progress:0:" + newIds.size());

            if (newIds.isEmpty()) {
                log.info("No new emails to fetch for user={}", userId);
                // Still refresh starred/spam state for existing emails
                refreshLabelsAndStars(user, accessToken);
                syncJobService.complete(job.getId());
                refreshContacts(user);
            webSocketService.sendNotification(userId.toString(), "sync_complete");
                return CompletableFuture.completedFuture(null);
            }

            // Step 3: Fetch new emails in batches of 50, send progress
            int fetched = 0;
            int batchSize = 50;
            var allSavedEmails = new ArrayList<EmailMessage>();
            for (int i = 0; i < newIds.size(); i += batchSize) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Full sync interrupted for user={}", userId);
                    break;
                }

                var batch = newIds.subList(i, Math.min(i + batchSize, newIds.size()));
                var batchEmails = gmailService.fetchMessagesByIds(gmail, batch);

                if (!batchEmails.isEmpty()) {
                    saveEmails(batchEmails, user, provider);
                    allSavedEmails.addAll(batchEmails);
                    // Acotar memoria: solo conservamos los últimos 20 (los que se pasan
                    // a notifyUrgentEmails al final); en syncs largas el resto era basura.
                    if (allSavedEmails.size() > 20) {
                        allSavedEmails = new ArrayList<>(
                                allSavedEmails.subList(allSavedEmails.size() - 20, allSavedEmails.size()));
                    }
                }

                fetched += batch.size();
                syncJobService.updateProgress(job.getId(), fetched, newIds.size());
                webSocketService.sendNotification(userId.toString(),
                        "sync_progress:" + fetched + ":" + newIds.size());

                if (fetched % 500 == 0) {
                    log.info("Full sync progress: {}/{} for user={}", fetched, newIds.size(), userId);
                }
            }

            refreshLabelsAndStars(user, accessToken);
            notifyUrgentEmails(user, allSavedEmails);
            syncJobService.complete(job.getId());

            log.info("Full sync complete: {} new emails saved for user={} ({} total in Gmail)",
                    fetched, userId, totalInGmail);
            refreshContacts(user);
            webSocketService.sendNotification(userId.toString(), "sync_complete");

        } catch (RuntimeException e) {
            if (job != null) {
                syncJobService.fail(job.getId(), e.getMessage());
            }
            log.error("Full sync failed for user={}, provider={}: {}", userId, provider, e.getMessage(), e);
            webSocketService.sendNotification(userId.toString(), "sync_error:" + e.getMessage());
        } finally {
            syncingStatus.remove(userId);
            syncingTimestamps.remove(userId);
        }
        return CompletableFuture.completedFuture(null);
    }

    public Map<String, Object> composeAndSend(User user, ComposeRequest request) {
        // El token y el envío están fuera de transacción: Gmail puede dormir en un retry
        // y no debe sostener una conexión del pool.
        var provider = user.getProvider();
        var token = getToken(user, provider);
        String messageId;
        if ("google".equals(provider)) {
            messageId = gmailService.sendEmail(token, request.getTo(), request.getSubject(), request.getBody());
        } else if ("outlook".equals(provider)) {
            messageId = outlookService.sendEmail(token, request.getTo(), request.getSubject(), request.getBody());
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        contactService.upsertSent(user.getId(), request.getTo());
        webSocketService.sendNotification(user.getId().toString(), "emails_changed");
        return Map.of("status", "sent", "messageId", messageId != null ? messageId : "");
    }

    public Map<String, Object> bulkAction(User user, BulkActionRequest request) {
        var userId = user.getId();
        var provider = request.getProvider();

        // Sólo los ids del usuario, en una consulta: la acción no puede tocar mails ajenos.
        var owned = emailRepository.findByUserIdAndProviderEmailIdIn(userId, request.getEmailIds()).stream()
                .map(EmailMessage::getProviderEmailId)
                .collect(Collectors.toSet());

        int applied = 0;
        var failed = new ArrayList<String>();
        for (var emailId : request.getEmailIds()) {
            if (!owned.contains(emailId)) {
                failed.add(emailId);
                continue;
            }
            try {
                switch (request.getAction()) {
                    case "star" -> toggleStar(user, provider, emailId, true);
                    case "unstar" -> toggleStar(user, provider, emailId, false);
                    case "read" -> markAsRead(user, provider, emailId);
                    case "unread" -> markAsUnread(user, provider, emailId);
                    case "trash" -> moveToTrash(user, provider, emailId);
                    default -> throw new IllegalArgumentException("Unknown action: " + request.getAction());
                }
                applied++;
            } catch (Exception e) {
                log.warn("Bulk action {} failed for {}: {}", request.getAction(), emailId, e.getMessage());
                failed.add(emailId);
            }
        }

        // Un solo aviso para todo el lote: el dashboard refresca una vez, no 100.
        webSocketService.sendNotification(userId.toString(), "emails_changed");
        return Map.of("applied", applied, "failed", failed);
    }

    /** Un fallo acá no puede romper un sync ya terminado: se registra y sigue. */
    private void refreshContacts(User user) {
        try {
            var userId = user.getId();
            boolean firstScan = contactService.needsSentScan(userId);
            contactService.rebuildInbound(userId);
            // El barrido de enviados cuesta ~20 unidades por mail: una vez por usuario.
            if (firstScan && "google".equals(user.getProvider())) {
                contactService.applySentCounts(userId, gmailService.fetchSentRecipients(
                        getToken(user, "google"), contactsSentWindowDays, contactsSentMax));
            }
        } catch (Exception e) {
            log.warn("Failed to refresh contacts for user={}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Sync incremental de Gmail. Con un cursor {@code historyId} guardado usa
     * {@code history.list} (2 unidades): detecta altas Y bajas, así que un mail
     * borrado en Gmail deja de aparecer. Sin cursor (o vencido) cae al fetch por
     * fecha y guarda el cursor para la próxima.
     */
    private List<EmailMessage> syncGoogle(User user, String accessToken) {
        var userId = user.getId();
        var oauthToken = oauthTokenRepository
                .findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google")
                .orElseThrow(() -> new RuntimeException("No OAuth token found for provider: google"));

        if (oauthToken.getHistoryId() != null) {
            var changes = gmailService.listHistory(accessToken, oauthToken.getHistoryId());
            if (changes != null) {
                var emails = applyHistoryChanges(user, accessToken, changes);
                persistHistoryId(oauthToken, changes.latestHistoryId());
                return emails;
            }
            log.warn("Stale historyId for user={}, falling back to date-based sync", userId);
        }

        // Cursor ANTES de listar: así un cambio ocurrido durante el fetch no se pierde.
        var cursor = gmailService.getHistoryId(accessToken);
        var lastEmailOpt = emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId);
        List<EmailMessage> emails;
        if (lastEmailOpt.isPresent()) {
            var lastReceivedAt = lastEmailOpt.get().getReceivedAt();
            log.info("Incremental sync for user={}, fetching emails after {}", userId, lastReceivedAt);
            emails = gmailService.fetchIncrementalEmails(accessToken, lastReceivedAt, fetchAllProviderEmailIds(userId));
        } else {
            log.info("First sync for user={}, fetching all emails", userId);
            emails = gmailService.fetchAllEmails(accessToken, 100, maxEmailsPerSync);
        }
        log.info("Incremental fetch: {} new emails for user={}", emails.size(), userId);
        persistHistoryId(oauthToken, cursor);
        return emails;
    }

    /** Aplica altas/bajas del history de Gmail sobre la base local y devuelve los mails nuevos. */
    private List<EmailMessage> applyHistoryChanges(User user, String accessToken, GmailHistoryChanges changes) {
        var userId = user.getId();

        var deletedIds = changes.deletedIds();
        if (!deletedIds.isEmpty()) {
            emailRepository.deleteByUserIdAndProviderEmailIdIn(userId, deletedIds);
            vistoRepository.deleteByUserIdAndProviderEmailIdIn(userId, deletedIds);
            log.info("History sync: removed {} emails for user={}", deletedIds.size(), userId);
        }

        var existingIds = fetchAllProviderEmailIds(userId);
        var newIds = changes.addedIds().stream()
                .filter(id -> !deletedIds.contains(id))
                .filter(id -> !existingIds.contains(id))
                .toList();
        if (newIds.isEmpty()) {
            return List.of();
        }
        return gmailService.fetchMessagesByIds(buildGmailClient(accessToken), newIds);
    }

    private void persistHistoryId(OAuthToken oauthToken, String historyId) {
        if (historyId == null) return;
        oauthToken.setHistoryId(historyId);
        oauthTokenRepository.save(oauthToken);
    }

    protected com.google.api.services.gmail.Gmail buildGmailClient(String accessToken) {
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

    @Transactional
    public int saveEmails(List<EmailMessage> emails, User user, String provider) {
        if (emails.isEmpty()) {
            return 0;
        }
        int syncedCount = 0;

        // Una sola query en lugar de una por mensaje: en syncs completas de miles de
        // correos, la consulta individual era el cuello de botella dominante.
        var incomingIds = emails.stream()
                .map(EmailMessage::getProviderEmailId)
                .collect(Collectors.toSet());
        var existingById = new HashMap<String, EmailMessage>();
        emailRepository.findByUserIdAndProviderEmailIdIn(user.getId(), incomingIds)
                .forEach(e -> existingById.put(e.getProviderEmailId(), e));

        var newEmails = new ArrayList<EmailMessage>();
        for (var email : emails) {
            var existing = existingById.get(email.getProviderEmailId());
            if (existing != null) {
                // Refresh existing email with the latest state from the provider
                existing.setStarred(email.isStarred());
                existing.setRead(email.isRead());
                existing.setLabels(email.getLabels());
                existing.setSubject(email.getSubject());
                existing.setFromAddress(email.getFromAddress());
                existing.setFromName(email.getFromName());
                existing.setToAddresses(email.getToAddresses());
                existing.setBodyPreview(email.getBodyPreview());
                existing.setBodyHtml(htmlSanitizerService.sanitize(email.getBodyHtml()));
                existing.setReceivedAt(email.getReceivedAt());
                existing.setThreadId(email.getThreadId());
                emailRepository.save(existing);
            } else {
                email.setUser(user);
                email.setProvider(provider);
                email.setBodyHtml(htmlSanitizerService.sanitize(email.getBodyHtml()));
                newEmails.add(email);
                syncedCount++;
            }
        }
        if (!newEmails.isEmpty()) {
            emailRepository.saveAll(newEmails);
        }
        return syncedCount;
    }

    /**
     * Best-effort refresh of starred/spam state for existing emails.
     * Never throws — failures are logged and the sync continues.
     */
    @Transactional
    public void refreshLabelsAndStars(User user, String accessToken) {
        try {
            var gmail = buildGmailClient(accessToken);
            var userId = user.getId();

            var starredIds = fetchAllMessageIds(gmail, "is:starred");
            var spamIds = fetchAllMessageIds(gmail, "in:spam");

            // Reset all stars, then re-star the ones currently starred in Gmail
            emailRepository.unstarAllByUserId(userId);
            if (!starredIds.isEmpty()) {
                emailRepository.starByProviderEmailIds(userId, starredIds);
            }

            // Quitar SPAM de lo que YA NO está en spam (si no, el filtro "Mostrar SPAM"
            // muestra correo limpio) y luego marcar los que hoy están en spam.
            emailRepository.removeSpamLabelByUserId(userId);
            if (!spamIds.isEmpty()) {
                emailRepository.addSpamLabelByProviderEmailIds(userId, spamIds);
            }

            log.info("Label refresh complete for user={}: {} starred, {} spam", userId, starredIds.size(), spamIds.size());
        } catch (Exception e) {
            log.warn("Label refresh failed for user={}: {}", user.getId(), e.getMessage(), e);
        }
    }

    private java.util.Set<String> fetchAllMessageIds(com.google.api.services.gmail.Gmail gmail, String query) throws IOException {
        var ids = new LinkedHashSet<String>();
        String pageToken = null;
        do {
            var request = gmail.users().messages().list("me")
                    .setQ(query)
                    .setMaxResults(500L);
            if (pageToken != null) request.setPageToken(pageToken);

            var response = request.execute();
            var messages = response.getMessages();
            if (messages != null) {
                for (var msg : messages) {
                    ids.add(msg.getId());
                }
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return ids;
    }

    public void markAsRead(User user, String provider, String emailId) {
        // Write to visto table — this is the source of truth for "opened"
        var existing = vistoRepository.findByProviderEmailIdAndUserId(emailId, user.getId());
        if (existing.isPresent()) {
            existing.get().setVisto(true);
            vistoRepository.save(existing.get());
        } else {
            var v = Visto.builder()
                    .user(user)
                    .providerEmailId(emailId)
                    .visto(true)
                    .build();
            vistoRepository.save(v);
        }
        // is_read es lo que cuentan los contadores del dashboard: abrir un email
        // tiene que apagarlo también en la fila local, no sólo en la tabla visto.
        emailRepository.findByProviderEmailIdAndUserId(emailId, user.getId())
                .ifPresent(email -> {
                    email.setRead(true);
                    emailRepository.save(email);
                });
        log.info("Visto marked: email={} user={}", emailId, user.getId());

        // Sync with Gmail/Outlook — best effort
        try {
            var token = getToken(user, provider);
            if ("google".equals(provider)) gmailService.markAsRead(token, emailId);
            else outlookService.markAsRead(token, emailId);
        } catch (Exception e) {
            log.warn("Failed to mark as read on {}: {}", provider, e.getMessage());
        }
    }

    public void markAsUnread(User user, String provider, String emailId) {
        // Delete or set visto=false
        var existing = vistoRepository.findByProviderEmailIdAndUserId(emailId, user.getId());
        if (existing.isPresent()) {
            existing.get().setVisto(false);
            vistoRepository.save(existing.get());
        }
        emailRepository.findByProviderEmailIdAndUserId(emailId, user.getId())
                .ifPresent(email -> {
                    email.setRead(false);
                    emailRepository.save(email);
                });
        log.info("Visto unset: email={} user={}", emailId, user.getId());

        // Sync with provider — best effort
        try {
            var token = getToken(user, provider);
            if ("google".equals(provider)) gmailService.markAsUnread(token, emailId);
            else outlookService.markAsUnread(token, emailId);
        } catch (Exception e) {
            log.warn("Failed to mark as unread on {}: {}", provider, e.getMessage());
        }
    }

    public void toggleStar(User user, String provider, String emailId, boolean starred) {
        // Gmail manda: si el proveedor rechaza, la base no cambia y el error sube.
        var token = getToken(user, provider);
        if ("google".equals(provider)) gmailService.toggleStar(token, emailId, starred);
        else outlookService.toggleStar(token, emailId, starred);

        emailRepository.findByProviderEmailIdAndUserId(emailId, user.getId())
                .ifPresent(email -> {
                    email.setStarred(starred);
                    emailRepository.save(email);
                });
    }

    public void moveToTrash(User user, String provider, String emailId) {
        var token = getToken(user, provider);
        try {
            if ("google".equals(provider)) gmailService.moveToTrash(token, emailId);
            else outlookService.moveToTrash(token, emailId);
        } catch (TransientApiException e) {
            // El proveedor no respondió: se conserva la fila local para poder reintentar.
            throw e;
        } catch (Exception e) {
            // 4xx (p. ej. el mail ya no existe) o proveedor no-Google: se borra igual, es idempotente.
            log.warn("Failed to move to trash on {}: {}", provider, e.getMessage());
        }

        // El listado muestra TODAS las filas locales, así que sin este borrado el
        // mail seguiría apareciendo aunque ya no exista en Gmail.
        emailRepository.deleteByUserIdAndProviderEmailIdIn(user.getId(), List.of(emailId));
        vistoRepository.deleteByUserIdAndProviderEmailIdIn(user.getId(), List.of(emailId));
        log.info("Email {} removed locally for user={}", emailId, user.getId());
    }

    public Map<String, Object> sendReply(User user, String provider, String providerEmailId, String body) {
        var original = emailRepository.findByProviderEmailIdAndUserId(providerEmailId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Email not found"));

        var to = original.getFromAddress();
        var originalSubject = original.getSubject();
        var subject = originalSubject != null && originalSubject.matches("(?i)^re:\\s*.*")
                ? originalSubject
                : "Re: " + (originalSubject != null ? originalSubject : "");

        var token = getToken(user, provider);

        String messageId;
        if ("google".equals(provider)) {
            messageId = gmailService.sendEmail(token, to, subject, body);
        } else if ("outlook".equals(provider)) {
            messageId = outlookService.sendEmail(token, to, subject, body);
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        log.info("Reply sent: userId={}, provider={}, to={}, subject={}, messageId={}",
                user.getId(), provider, to, subject, messageId);
        return Map.of("status", "sent", "messageId", messageId);
    }

    private String getToken(User user, String provider) {
        var oauthToken = oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(user.getId(), provider)
                .orElseThrow(() -> new RuntimeException("No OAuth token found for provider: " + provider));

        if (oauthToken.getExpiresAt() != null && Instant.now().isAfter(oauthToken.getExpiresAt())) {
            log.info("OAuth token expired for user={}, provider={}. Attempting refresh.", user.getId(), provider);
            var refreshed = refreshToken(oauthToken);
            var newAccessToken = (String) refreshed.get("access_token");

            // Azure AD (Outlook) rota el refresh_token en cada refresh e invalida el
            // anterior; Google también puede. Si viene uno nuevo hay que persistirlo,
            // si no el siguiente refresh falla con 401 para siempre.
            var newRefreshToken = refreshed.get("refresh_token");
            if (newRefreshToken instanceof String s && !s.isBlank()) {
                oauthToken.setRefreshToken(tokenCryptoService.encrypt(s));
            }

            oauthToken.setAccessToken(tokenCryptoService.encrypt(newAccessToken));
            var expiresIn = refreshed.get("expires_in");
            if (expiresIn instanceof Number n) {
                oauthToken.setExpiresAt(Instant.now().plusSeconds(n.longValue()));
            } else {
                oauthToken.setExpiresAt(Instant.now().plusSeconds(3600));
            }
            oauthTokenRepository.save(oauthToken);
            return newAccessToken;
        }

        log.debug("Using stored OAuth token for user={}, provider={}", user.getId(), provider);
        return tokenCryptoService.decrypt(oauthToken.getAccessToken());
    }

    private Map<String, Object> refreshToken(OAuthToken oauthToken) {
        var refreshToken = tokenCryptoService.decrypt(oauthToken.getRefreshToken());
        try {
            if ("google".equals(oauthToken.getProvider())) {
                return refreshGoogleToken(refreshToken);
            } else if ("outlook".equals(oauthToken.getProvider())) {
                return outlookService.refreshAccessToken(
                        refreshToken,
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

    private Map<String, Object> refreshGoogleToken(String refreshToken) {
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
        return response;
    }

    private EmailDTO toDTO(EmailMessage email, Set<String> vistoEmailIds) {
        boolean visto = vistoEmailIds.contains(email.getProviderEmailId());
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
                .bodyHtml(QuotedPrintable.decodeIfEncoded(email.getBodyHtml()))
                .isRead(email.isRead())
                .isStarred(email.isStarred())
                .visto(visto)
                .labels(email.getLabels())
                .receivedAt(email.getReceivedAt())
                .fetchedAt(email.getFetchedAt())
                .build();
    }

    private Set<String> fetchVistoEmailIds(UUID userId, List<EmailMessage> emails) {
        if (emails.isEmpty()) {
            return Set.of();
        }
        var providerEmailIds = emails.stream()
                .map(EmailMessage::getProviderEmailId)
                .collect(Collectors.toSet());
        return new HashSet<>(vistoRepository.findVistoEmailIdsByUserIdAndProviderEmailIdIn(userId, providerEmailIds));
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
