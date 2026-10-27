package com.emailfilter.service;

import com.emailfilter.dto.BulkActionRequest;
import com.emailfilter.dto.ComposeRequest;
import com.emailfilter.dto.EmailDTO;
import com.emailfilter.dto.GmailHistoryChanges;
import com.emailfilter.dto.PrioritySummaryDTO;
import com.emailfilter.exception.PermanentApiException;
import com.emailfilter.exception.ResourceNotFoundException;
import com.emailfilter.exception.TransientApiException;
import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.OAuthToken;
import com.emailfilter.model.SentMessage;
import com.emailfilter.model.SyncJob;
import com.emailfilter.model.User;
import com.emailfilter.model.Visto;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.OAuthTokenRepository;
import com.emailfilter.repository.SentMessageRepository;
import com.emailfilter.repository.UserRepository;
import com.emailfilter.repository.VistoRepository;
import com.emailfilter.security.TokenCryptoService;
import com.emailfilter.util.QuotedPrintable;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailRepository emailRepository;
    private final SentMessageRepository sentMessageRepository;
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
    private final TransactionTemplate transactionTemplate;
    private final RestTemplate restTemplate;
    private final EmailEmbeddingService emailEmbeddingService;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Value("${google.client-id:}")
    private String googleClientId;
    @Value("${google.client-secret:}")
    private String googleClientSecret;
    @Value("${outlook.client-id:}")
    private String outlookClientId;
    @Value("${outlook.client-secret:}")
    private String outlookClientSecret;
    @Value("${outlook.tenant:common}")
    private String outlookTenant;

    /** Ventana del primer barrido de enviados y tope de mails leídos (20 unidades cada uno). */
    @Value("${email.contacts.sent-window-days:365}")
    int contactsSentWindowDays;
    @Value("${email.contacts.sent-max:2000}")
    int contactsSentMax;

    private final Object tokenRefreshLock = new Object();

    private final ConcurrentHashMap<UUID, Boolean> syncingStatus = new ConcurrentHashMap<>();
    // El heartbeat solo se toca entre etapas: un full sync de un buzon grande
    // (17k mails a 400ms/req) tarda ~85 min, asi que 15 min dejaba entrar un
    // segundo sync en paralelo (lotes duplicados -> UNIQUE violation).
    // shortcut: si el proveedor tarda mas de 2 h sin reportar progreso, se
    // libera el flag; la barrera final sigue siendo sync_jobs.
    private static final long SYNC_STATUS_TTL_MS = 120 * 60 * 1000L;
    private static final long TOKEN_MARGIN_SECONDS = 120;
    // Reintentos de un lote cuando Gmail pide esperar (cuota por minuto).
    private static final int MAX_QUOTA_RETRIES = 8;
    private static final int EXISTING_IDS_PAGE_SIZE = 1000;
    private final ConcurrentHashMap<UUID, Long> syncingTimestamps = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Page<EmailDTO> getEmails(UUID userId, int page, int size) {
        // Bandeja de entrada = todo menos spam (el spam tiene su propia vista).
        var emailPage = emailRepository.findInboxByUserId(userId, PageRequest.of(page, size));
        var vistoEmailIds = fetchVistoEmailIds(userId, emailPage.getContent());
        return emailPage.map(email -> toDTO(email, vistoEmailIds));
    }

    @Transactional(readOnly = true)
    public EmailDTO getEmailById(UUID userId, UUID emailId) {
        var email = emailRepository.findById(emailId)
                .orElseThrow(() -> new ResourceNotFoundException("Email not found: " + emailId));
        if (!email.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Email does not belong to user");
        }
        return toDTO(email, fetchVistoEmailIds(userId, List.of(email)));
    }

    public record SemanticResult(List<EmailEmbeddingService.ChunkHit> hits, List<EmailDTO> emails) {}

    private static final Pattern FILENAME = Pattern.compile("\"filename\":\"([^\"]*)\"");

    /** Mails con al menos un adjunto de alguna de las extensiones (vacío = cualquier adjunto). */
    @Transactional(readOnly = true)
    public List<EmailDTO> findByAttachments(UUID userId, List<String> extensions, int limit) {
        var emails = emailRepository.findWithAttachments(userId).stream()
                .filter(email -> hasAttachmentOf(email.getAttachmentsJson(), extensions))
                .limit(limit)
                .toList();
        var vistoEmailIds = fetchVistoEmailIds(userId, emails);
        return emails.stream().map(email -> toDTO(email, vistoEmailIds)).toList();
    }

    private static boolean hasAttachmentOf(String json, List<String> extensions) {
        if (json == null) return false;
        var matcher = FILENAME.matcher(json);
        while (matcher.find()) {
            var name = matcher.group(1).toLowerCase(Locale.ROOT);
            if (extensions.isEmpty() || extensions.stream().anyMatch(name::endsWith)) return true;
        }
        return false;
    }

    /** Fragmentos y mails relevantes para la consulta por contenido. Vacío si no hay coincidencias. */
    @Transactional(readOnly = true)
    public Optional<SemanticResult> searchSemantic(UUID userId, String query, int limit) {
        var hits = emailEmbeddingService.searchChunks(userId, query, limit);
        if (hits.isEmpty()) return Optional.empty();
        var emails = hits.stream().map(EmailEmbeddingService.ChunkHit::emailId).distinct()
                .map(id -> emailRepository.findByIdAndUserId(id, userId))
                .flatMap(Optional::stream)
                .toList();
        var vistoEmailIds = fetchVistoEmailIds(userId, emails);
        return Optional.of(new SemanticResult(hits, emails.stream().map(email -> toDTO(email, vistoEmailIds)).toList()));
    }

    @Transactional(readOnly = true)
    public Page<EmailDTO> getSentEmails(UUID userId, int page, int size) {
        return sentMessageRepository
                .findByUserIdOrderBySentAtDesc(userId, PageRequest.of(page, size))
                .map(s -> EmailDTO.builder()
                        .id(s.getId())
                        .provider(s.getProvider())
                        .providerEmailId(s.getProviderEmailId())
                        .threadId(s.getThreadId())
                        .fromAddress(s.getUser().getEmail())
                        .toAddresses(s.getToAddresses())
                        .subject(s.getSubject())
                        .bodyPreview(s.getBodyPreview())
                        .bodyHtml(s.getBodyHtml())
                        .isRead(true)
                        .receivedAt(s.getSentAt())
                        .fetchedAt(s.getFetchedAt())
                        .build());
    }

    /** Guarda una copia local de lo que se acaba de enviar, para que aparezca en Enviados ya. */
    private void recordSent(User user, String provider, String messageId, String to, String subject, String body) {
        if (messageId == null || messageId.isBlank()) return;
        String flat = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        String preview = flat.length() > 200 ? flat.substring(0, 200) : flat;
        transactionTemplate.executeWithoutResult(status -> sentMessageRepository.save(SentMessage.builder()
                .user(user)
                .provider(provider)
                .providerEmailId(messageId)
                .toAddresses(to)
                .subject(subject)
                .bodyPreview(preview)
                .bodyHtml(body)
                .sentAt(Instant.now())
                .build()));
    }

    /** Copia los enviados del proveedor. Si falla, el sync de recibidos sigue igual. */
    private void syncSentMessages(User user, String accessToken) {
        try {
            var userId = user.getId();
            var existing = sentMessageRepository.findProviderEmailIdsByUserId(userId);
            // 100 por sync: bajar el detalle cuesta 20 unidades de cuota por mail, y esta corrida
            // comparte la cuota del usuario con el sync de mails. Antes se bajaba TODO in:sent en
            // cada sync (incluido el automático de cada 2 min), lo que agotaba la cuota con 403.
            var fresh = gmailService.fetchSentEmails(accessToken, existing, 100);
            if (fresh.isEmpty()) return;
            fresh.forEach(s -> s.setUser(user));
            transactionTemplate.executeWithoutResult(status -> sentMessageRepository.saveAll(fresh));
            log.info("Sent sync: {} new sent emails for user={}", fresh.size(), userId);
        } catch (RuntimeException e) {
            log.warn("Sent sync failed for user={}: {}", user.getId(), e.getMessage());
        }
    }

    public boolean ownsEmail(UUID userId, String providerEmailId) {
        return emailRepository.existsByUserIdAndProviderEmailId(userId, providerEmailId);
    }

    /**
     * Extrae el texto de los adjuntos pendientes para que el chat pueda buscarlos por contenido.
     * Corre al final del sync; si falla sólo se loguea, el sync de mails ya terminó bien.
     */
    private void extractAttachmentTexts(User user, String accessToken) {
        try {
            // 50 por sync: ~5 unidades de cuota de Gmail por adjunto, y así un primer full sync deja el backfill completo.
            var pending = emailRepository.findPendingAttachmentExtraction(user.getId(), "google", PageRequest.of(0, 50));
            for (var email : pending) {
                // Un adjunto roto no debe frenar el resto de la tanda.
                try {
                    var text = new StringBuilder();
                    for (var attachment : readAttachments(email.getAttachmentsJson())) {
                        var bytes = gmailService.fetchAttachment(accessToken, email.getProviderEmailId(),
                                (String) attachment.get("attachmentId"));
                        // 10 MB: más no aporta a la búsqueda y el servicio de IA rechaza el base64.
                        if (bytes.length == 0 || bytes.length > 10_000_000) continue;
                        var result = aiServiceClient.extractDocument((String) attachment.get("filename"),
                                (String) attachment.get("mimeType"), Base64.getEncoder().encodeToString(bytes));
                        if (result != null && result.get("text") instanceof String extracted && !extracted.isBlank()) {
                            text.append(extracted).append("\n\n");
                        }
                    }
                    // "" y no NULL: un archivo sin texto (escaneado, .zip) no se reintenta en cada sync.
                    var value = truncate(text.toString().trim(), 20_000);
                    transactionTemplate.executeWithoutResult(status -> {
                        email.setAttachmentText(value);
                        emailRepository.save(email);
                    });
                } catch (Exception e) {
                    log.warn("Attachment text extraction skipped email={}: {}", email.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Attachment text extraction failed for user={}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Rellena los adjuntos de mails que se guardaron antes de existir el soporte (has_attachment=false).
     * Gmail dice barato qué mails tienen adjuntos; sólo se bajan los detalles de los que faltan.
     */
    private void backfillAttachmentMetadata(User user, com.google.api.services.gmail.Gmail gmail,
                                            String accessToken, Set<String> existingIds) {
        try {
            var withAttachments = fetchAllMessageIds(gmail, "-in:sent -in:drafts -in:trash has:attachment").stream()
                    .filter(existingIds::contains)
                    .toList();
            var pending = new ArrayList<String>();
            for (int i = 0; i < withAttachments.size(); i += 200) {
                var chunk = withAttachments.subList(i, Math.min(i + 200, withAttachments.size()));
                emailRepository.findByUserIdAndProviderEmailIdIn(user.getId(), chunk).stream()
                        .filter(e -> !e.isHasAttachment())
                        .map(EmailMessage::getProviderEmailId)
                        .forEach(pending::add);
            }
            if (pending.isEmpty()) return;
            log.info("Backfilling attachments for {} emails of user={}", pending.size(), user.getId());

            for (int i = 0; i < pending.size(); i += 50) {
                var chunk = pending.subList(i, Math.min(i + 50, pending.size()));
                var fresh = gmailService.fetchMessagesByIds(gmail, chunk, accessToken);
                transactionTemplate.executeWithoutResult(status -> fresh.forEach(f ->
                        emailRepository.findByProviderEmailIdAndUserId(f.getProviderEmailId(), user.getId())
                                .ifPresent(db -> {
                                    db.setHasAttachment(f.isHasAttachment());
                                    db.setAttachmentsJson(f.getAttachmentsJson());
                                    emailRepository.save(db);
                                })));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Attachment backfill failed for user={}: {}", user.getId(), e.getMessage());
        }
    }

    /** Adjunto listo para descargar: {filename, mimeType, data}. */
    public Map<String, Object> downloadAttachment(UUID userId, UUID emailId, int index) {
        var email = emailRepository.findByIdAndUserId(emailId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Email not found"));
        // shortcut: adjuntos sólo en Gmail; Outlook necesitaría bajar el MIME completo del mensaje.
        if (!"google".equals(email.getProvider())) {
            throw new IllegalArgumentException("Attachments are only supported for Gmail");
        }
        var attachments = readAttachments(email.getAttachmentsJson());
        if (index < 0 || index >= attachments.size()) {
            throw new ResourceNotFoundException("Attachment not found");
        }
        var attachment = attachments.get(index);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        var data = gmailService.fetchAttachment(getToken(user, email.getProvider()),
                email.getProviderEmailId(), (String) attachment.get("attachmentId"));
        return Map.of(
                "filename", (String) attachment.get("filename"),
                "mimeType", (String) attachment.get("mimeType"),
                "data", data);
    }

    private List<Map<String, Object>> readAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("attachments_json inválido: {}", e.getMessage());
            return List.of();
        }
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
                    : cb.or(
                            buildContainsPredicate(cb, root.get("bodyPreview"), bodyRaw),
                            buildContainsPredicate(cb, root.get("bodyHtml"), bodyRaw));

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
                    case "isRead" -> {
                        // El sync pisa is_read con el flag del proveedor: "leído"
                        // incluye lo que el usuario abrió (visto) o el mail
                        // desaparece de los dos filtros a la vez.
                        var seen = query.subquery(Integer.class);
                        var visto = seen.from(Visto.class);
                        seen.select(cb.literal(1)).where(
                                cb.equal(visto.get("user").get("id"), userId),
                                cb.equal(visto.get("providerEmailId"), root.get("providerEmailId")),
                                cb.equal(visto.get("visto"), true));
                        if (Boolean.parseBoolean(value)) {
                            predicates.add(cb.or(cb.equal(root.get("isRead"), true), cb.exists(seen)));
                        } else {
                            // Igual que NOT_SEEN del dashboard: el proveedor dice no leído y el usuario no lo abrió.
                            predicates.add(cb.and(cb.equal(root.get("isRead"), false), cb.not(cb.exists(seen))));
                        }
                    }
                    case "isStarred" -> predicates.add(cb.equal(root.get("isStarred"), Boolean.parseBoolean(value)));
                    case "important" -> {
                        // Importante = label IMPORTANT de Gmail o clasificado por la IA (is_important).
                        if (Boolean.parseBoolean(value)) {
                            predicates.add(cb.or(
                                    cb.like(root.get("labels"), "%IMPORTANT%"),
                                    cb.isTrue(root.get("isImportant"))));
                        } else {
                            // notLike is NULL-unsafe: rows without labels need the OR.
                            predicates.add(cb.and(
                                    cb.or(cb.notLike(root.get("labels"), "%IMPORTANT%"),
                                            cb.isNull(root.get("labels"))),
                                    cb.isFalse(root.get("isImportant"))));
                        }
                    }
                    case "destacados" -> predicates.add(cb.equal(root.get("isStarred"), true));
                    case "label" ->
                        predicates.add(cb.like(root.get("labels"), "%" + escapeLike(value) + "%"));
                    case "hasAttachment" ->
                        predicates.add(cb.equal(root.get("hasAttachment"), Boolean.parseBoolean(value)));
                    case "attachmentContains" ->
                        // Nombre del archivo o texto extraído del adjunto.
                        predicates.add(cb.or(
                                buildContainsPredicate(cb, root.get("attachmentsJson"), value),
                                buildContainsPredicate(cb, root.get("attachmentText"), value)));
                    case "dateFrom" ->
                        predicates.add(cb.greaterThanOrEqualTo(root.get("receivedAt"), Instant.parse(value)));
                    case "dateTo" ->
                        // Exclusive upper bound: the AI sends 00:00 of the day after the
                        // requested range, so use < (not <=) to avoid leaking that day.
                        predicates.add(cb.lessThan(root.get("receivedAt"), Instant.parse(value)));
                }
            }

            // La papelera es una carpeta más: sólo la muestra quien pide label=TRASH.
            if (!"TRASH".equalsIgnoreCase(filters.get("label"))) {
                predicates.add(cb.or(
                        cb.isNull(root.get("labels")),
                        cb.notLike(root.get("labels"), "%TRASH%")));
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
     * Búsqueda de texto dentro de una carpeta, sin IA: ILIKE sobre asunto, remitente y snippet.
     * {@code folder}: inbox (sin spam ni papelera), starred, important, spam, trash.
     */
    @Transactional(readOnly = true)
    public Page<EmailDTO> searchEmails(UUID userId, String query, String folder, int page, int size) {
        var term = query == null ? "" : query.trim();
        if (term.length() > 200) term = term.substring(0, 200);
        var text = term;
        Specification<EmailMessage> spec = (root, q, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("user").get("id"), userId));
            predicates.add(folderPredicate(cb, root, folder));
            if (!text.isEmpty()) {
                predicates.add(cb.or(
                        buildContainsPredicate(cb, root.get("subject"), text),
                        buildContainsPredicate(cb, root.get("fromAddress"), text),
                        buildContainsPredicate(cb, root.get("bodyPreview"), text)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));
        var emailPage = emailRepository.findAll(spec, pageable);
        var vistoEmailIds = fetchVistoEmailIds(userId, emailPage.getContent());
        return emailPage.map(email -> toDTO(email, vistoEmailIds));
    }

    private static Predicate folderPredicate(CriteriaBuilder cb, Root<EmailMessage> root, String folder) {
        Path<String> labels = root.get("labels");
        var notTrash = cb.or(cb.isNull(labels), cb.notLike(labels, "%TRASH%"));
        return switch (folder == null ? "inbox" : folder) {
            case "trash" -> cb.like(labels, "%TRASH%");
            case "spam" -> cb.and(notTrash, cb.like(labels, "%SPAM%"));
            case "starred" -> cb.and(notTrash, cb.isTrue(root.get("isStarred")));
            case "important" -> cb.and(notTrash, cb.or(cb.like(labels, "%IMPORTANT%"),
                    cb.isTrue(root.get("isImportant"))));
            default -> cb.and(notTrash, cb.or(cb.isNull(labels), cb.notLike(labels, "%SPAM%")));
        };
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
        // El flag en memoria expira a las 2 h, pero el job sigue vivo durante el indexado de
        // embeddings: mirar también la DB evita que Layout lance otro sync en paralelo.
        return syncingStatus.getOrDefault(userId, false) || syncJobService.hasActive(userId);
    }

    @Transactional(readOnly = true)
    public long getEmailCountByUser(UUID userId) {
        return emailRepository.countActiveByUserId(userId);
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
        // Solo la lectura va en transacción: la llamada HTTP a la IA queda fuera (no pinea conexión).
        var emailPage = transactionTemplate.execute(status -> filterEmails(userId, filters, 0, 20));
        // DTOs livianos: el resumen no necesita el HTML de 100 correos (era el payload
        // más pesado hacia el servicio de IA).
        var dtos = emailPage.getContent().stream()
                .limit(20)
                .map(email -> EmailDTO.builder()
                        .providerEmailId(email.getProviderEmailId())
                        .subject(email.getSubject())
                        .fromAddress(email.getFromAddress())
                        .fromName(email.getFromName())
                        .bodyPreview(truncate(email.getBodyPreview(), 200))
                        .build())
                .toList();
        var totalElements = emailPage.getTotalElements();

        if (dtos.isEmpty()) {
            return Map.of(
                    "summary", "No hay correos para resumir.",
                    "emailCount", 0,
                    "date", targetDate.toString()
            );
        }

        // Un 429 de Groq no debe romper el dashboard con un 5xx: se degrada a un resumen local.
        String summary;
        try {
            summary = aiServiceClient.summarizeEmails(dtos);
        } catch (RuntimeException e) {
            log.warn("Resumen diario sin IA ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            summary = "No se pudo generar el resumen ahora. Tenés " + totalElements
                    + " correos el " + targetDate + ".";
        }
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
        result.put("importantUnread", emailRepository.countUnreadImportant(userId));
        result.put("spamUnread", emailRepository.countUnreadWithLabel(userId, "%SPAM%"));
        result.put("trashUnread", emailRepository.countUnreadWithLabel(userId, "%TRASH%"));
        result.put("perDay", perDayList);
        return result;
    }

    @Transactional(readOnly = true)
    public PrioritySummaryDTO buildPrioritySummary(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
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
            var importantIds = new ArrayList<String>();
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
                if (id != null) importantIds.add(id.toString());
            }
            if (!importantIds.isEmpty()) {
                emailRepository.markImportant(user.getId(), importantIds);
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

    /**
     * Borra los correos que ya no están en Gmail. La papelera local se conserva: el listado
     * excluye {@code in:trash} y esos mails deben seguir visibles en la vista Papelera.
     */
    private void pruneDeletedEmails(UUID userId, String provider, Set<String> allMessageIds) {
        if (allMessageIds.isEmpty()) {
            return; // listado vacío: no se poda a ciegas
        }
        // Solo las filas del proveedor sincronizado: un usuario puede tener Google y Outlook a la vez.
        var stale = new HashSet<>(emailRepository.findProviderEmailIdsByUserIdAndProvider(userId, provider));
        stale.removeAll(allMessageIds);
        if (stale.isEmpty()) {
            return;
        }
        stale.removeAll(new HashSet<>(emailRepository.findTrashedProviderEmailIds(userId)));
        var ids = new ArrayList<>(stale);
        for (int i = 0; i < ids.size(); i += 1000) {
            var chunk = ids.subList(i, Math.min(i + 1000, ids.size()));
            emailRepository.deleteByUserIdAndProviderEmailIdIn(userId, chunk);
            vistoRepository.deleteByUserIdAndProviderEmailIdIn(userId, chunk);
        }
        if (!ids.isEmpty()) {
            log.info("Full sync: removed {} emails no longer in Gmail for user={}", ids.size(), userId);
        }
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
        boolean contactsChanged = false;
        try {
            job = syncJobService.start(userId, provider, "incremental");
            webSocketService.sendNotification(userId.toString(), "sync_started:incremental");
            log.info("Fetching OAuth token for user={}, provider={}", userId, provider);
            var accessToken = getToken(user, provider);
            log.info("OAuth token obtained for user={}, provider={}", userId, provider);

            List<EmailMessage> emails;
            GoogleSyncResult google = null;
            if ("google".equals(provider)) {
                google = syncGoogle(user, accessToken);
                emails = google.emails();
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

            var newEmails = new ArrayList<EmailMessage>();
            int syncedCount = saveEmails(emails, user, provider, newEmails);
            // El cursor de Gmail sólo avanza cuando los mails ya están guardados.
            if (google != null) {
                persistHistoryId(google.oauthToken(), google.historyCursor());
            }
            syncingTimestamps.put(userId, System.currentTimeMillis());
            syncJobService.updateProgress(job.getId(), emails.size(), emails.size());
            if ("google".equals(provider)) {
                // Con historial, solo hace falta releer estrellas/spam si cambió alguna etiqueta.
                if (google == null || google.labelsChanged()) {
                    refreshLabelsAndStars(user, accessToken);
                }
                syncSentMessages(user, accessToken);
                extractAttachmentTexts(user, accessToken);
                emailEmbeddingService.indexPending(user);
                syncingTimestamps.put(userId, System.currentTimeMillis());
            }
            // Sólo los recién insertados: en Outlook el fetch devuelve siempre los mismos 100.
            notifyUrgentEmails(user, newEmails);
            syncJobService.complete(job.getId());
            log.info("Sync complete: {} new emails saved for user={}, provider={} ({} total fetched)", syncedCount, userId, provider, emails.size());
            // Sin altas ni bajas no hay nada que recalcular en contactos (antes corría cada 2 min).
            contactsChanged = syncedCount > 0 || (google != null && google.deleted());
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
            // Fuera del lock: el primer barrido de enviados puede tardar minutos.
            if (contactsChanged) {
                refreshContacts(user);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Descarga un lote. Si Gmail avisa que se superó la cuota (403/429) o falla de forma
     * transitoria, espera y reintenta el MISMO lote en vez de abortar todo el full sync.
     * Pide el token de nuevo en cada intento, así que también cubre el vencimiento.
     */
    private List<EmailMessage> fetchBatchWaitingForQuota(User user, String provider, List<String> batch) {
        int attempt = 0;
        while (true) {
            try {
                var token = getToken(user, provider);
                return gmailService.fetchMessagesByIds(buildGmailClient(token), batch, token);
            } catch (RuntimeException e) {
                // fetchMessagesByIds descarga en paralelo: el error real llega envuelto.
                var cause = (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
                attempt++;
                // Un 401 a mitad de un full sync (token vencido) no debe tirar 2 h de trabajo:
                // getToken se renueva en cada intento, así que dos reintentos cortos alcanzan.
                boolean unauthorized = cause instanceof PermanentApiException p && p.getStatusCode() == 401;
                boolean retryable = isQuotaOrTransient(cause) || (unauthorized && attempt <= 2);
                if (!retryable || attempt > MAX_QUOTA_RETRIES) {
                    throw e;
                }
                long waitSeconds = unauthorized ? 2L : Math.min(60L * attempt, 600L); // 1, 2, 3 ... hasta 10 min
                log.warn("Gmail pidió esperar (intento {}/{}): {}. Reintento en {} s",
                        attempt, MAX_QUOTA_RETRIES, cause.getMessage(), waitSeconds);
                try {
                    // En tramos de 30 s para mantener vivo el latido del sync (TTL de syncingStatus).
                    for (long waited = 0; waited < waitSeconds; waited += 30) {
                        Thread.sleep(Math.min(30, waitSeconds - waited) * 1000L);
                        syncingTimestamps.put(user.getId(), System.currentTimeMillis());
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Sync interrumpido mientras esperaba la cuota de Gmail", ie);
                }
            }
        }
    }

    /** Errores que se arreglan esperando: transitorios, o cuota agotada (Gmail usa 403 o 429). */
    private static boolean isQuotaOrTransient(Throwable t) {
        if (t instanceof TransientApiException) return true;
        if (t instanceof PermanentApiException p) {
            var msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            return p.getStatusCode() == 429
                    || (p.getStatusCode() == 403 && (msg.contains("limit") || msg.contains("quota")));
        }
        return false;
    }

    @Async("emailSyncExecutor")
    public CompletableFuture<Void> syncAllEmailsAsync(User user, String provider) {
        var userId = user.getId();
        log.info("Starting FULL email sync for user={}, provider={}", userId, provider);

        // Sólo Google tiene full sync paginado; el controller llama a syncEmailsAsync para el resto.
        if (!"google".equals(provider)) {
            log.warn("Full sync not supported for provider={}, user={}", provider, userId);
            return CompletableFuture.completedFuture(null);
        }

        if (syncingStatus.putIfAbsent(userId, true) != null) {
            log.info("Sync already in progress for user={}", userId);
            return CompletableFuture.completedFuture(null);
        }
        syncingTimestamps.put(userId, System.currentTimeMillis());
        SyncJob job = null;
        boolean contactsChanged = false;
        try {
            var accessToken = getToken(user, provider);

            job = syncJobService.start(userId, provider, "full");
            webSocketService.sendNotification(userId.toString(), "sync_started:full");

            // Step 1: Get ALL message IDs from Gmail (cheap: ~5 units per 100 IDs)
            log.info("Fetching all Gmail message IDs for user={}", userId);
            webSocketService.sendNotification(userId.toString(), "sync_progress:0:0");

            var gmail = buildGmailClient(accessToken);
            var allMessageIds = new LinkedHashSet<String>();
            try {
                // Todo el buzón, incluido lo archivado y las etiquetas (promociones, social, propias).
                allMessageIds.addAll(fetchAllMessageIds(gmail, GmailService.MAILBOX_QUERY));
            } catch (IOException e) {
                throw new RuntimeException("Failed to fetch Gmail message IDs", e);
            }

            var totalInGmail = allMessageIds.size();
            log.info("Found {} total emails in Gmail for user={}", totalInGmail, userId);

            // Step 2: Remove IDs we already have in the DB
            var existingIds = fetchAllProviderEmailIds(userId);
            // Los mails guardados antes del soporte de adjuntos tienen el flag en false: se rellena acá.
            backfillAttachmentMetadata(user, gmail, accessToken, existingIds);
            var newIds = allMessageIds.stream()
                    .filter(id -> !existingIds.contains(id))
                    .collect(Collectors.toList());

            log.info("Of {} total, {} already in DB, {} new to fetch for user={}",
                    totalInGmail, existingIds.size(), newIds.size(), userId);
            webSocketService.sendNotification(userId.toString(),
                    "sync_progress:0:" + newIds.size());

            pruneDeletedEmails(userId, provider, allMessageIds);

            if (newIds.isEmpty()) {
                log.info("No new emails to fetch for user={}", userId);
                // Still refresh starred/spam state for existing emails
                refreshLabelsAndStars(user, accessToken);
                syncSentMessages(user, accessToken);
                extractAttachmentTexts(user, accessToken);
                emailEmbeddingService.indexPending(user);
                syncJobService.complete(job.getId());
                contactsChanged = true;
                webSocketService.sendNotification(userId.toString(), "sync_complete");
                return CompletableFuture.completedFuture(null);
            }

            // Step 3: Fetch new emails in batches of 50, send progress
            int fetched = 0;
            int batchSize = 50;
            var allSavedEmails = new ArrayList<EmailMessage>();
            for (int i = 0; i < newIds.size(); i += batchSize) {
                if (Thread.currentThread().isInterrupted()) {
                    // Abortar el job: un break dejaba el sync parcial como COMPLETED y la UI en verde.
                    throw new IllegalStateException("Full sync interrumpido en " + fetched + "/" + newIds.size() + " emails");
                }
                
                // El full sync dura más de 1 hora: se pide el token en cada lote (renueva si falta poco)
                accessToken = getToken(user, provider);
                gmail = buildGmailClient(accessToken);
                var batch = newIds.subList(i, Math.min(i + batchSize, newIds.size()));
                var batchEmails = fetchBatchWaitingForQuota(user, provider, batch);

                if (!batchEmails.isEmpty()) {
                    var batchNew = new ArrayList<EmailMessage>();
                    saveEmails(batchEmails, user, provider, batchNew);
                    allSavedEmails.addAll(batchNew);
                    // Acotar memoria: solo conservamos los últimos 20 (los que se pasan
                    // a notifyUrgentEmails al final); en syncs largas el resto era basura.
                    if (allSavedEmails.size() > 20) {
                        allSavedEmails = new ArrayList<>(
                                allSavedEmails.subList(allSavedEmails.size() - 20, allSavedEmails.size()));
                    }
                }

                fetched += batch.size();
                syncingTimestamps.put(userId, System.currentTimeMillis());
                syncJobService.updateProgress(job.getId(), fetched, newIds.size());
                webSocketService.sendNotification(userId.toString(),
                        "sync_progress:" + fetched + ":" + newIds.size());

                if (fetched % 1000 == 0) {
                    // El dashboard y las listas se actualizan durante un full sync de horas,
                    // no recién al final.
                    webSocketService.sendNotification(userId.toString(), "emails_changed");
                }
                if (fetched % 500 == 0) {
                    log.info("Full sync progress: {}/{} for user={}", fetched, newIds.size(), userId);
                }
            }

            // Tras esperas largas por cuota el token pudo vencer: se renueva antes de las etapas finales.
            accessToken = getToken(user, provider);
            refreshLabelsAndStars(user, accessToken);
            syncSentMessages(user, accessToken);
            extractAttachmentTexts(user, accessToken);
            emailEmbeddingService.indexPending(user);
            syncingTimestamps.put(userId, System.currentTimeMillis());
            notifyUrgentEmails(user, allSavedEmails);
            syncJobService.complete(job.getId());

            log.info("Full sync complete: {} new emails saved for user={} ({} total in Gmail)",
                    fetched, userId, totalInGmail);
            contactsChanged = true;
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
            if (contactsChanged) {
                refreshContacts(user);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    public Map<String, Object> composeAndSend(User user, ComposeRequest request) {
        // El token y el envío están fuera de transacción: Gmail puede dormir en un retry
        // y no debe sostener una conexión del pool.
        var provider = user.getProvider();
        var cc = parseAddressList(request.getCc());
        var bcc = parseAddressList(request.getBcc());
        var token = getToken(user, provider);
        String messageId;
        if ("google".equals(provider)) {
            messageId = gmailService.sendEmail(token, request.getTo(), cc, bcc, request.getSubject(), request.getBody());
        } else if ("outlook".equals(provider)) {
            messageId = outlookService.sendEmail(token, request.getTo(), cc, bcc, request.getSubject(), request.getBody());
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        contactService.upsertSent(user.getId(), request.getTo());
        recordSent(user, provider, messageId, request.getTo(), request.getSubject(), request.getBody());
        webSocketService.sendNotification(user.getId().toString(), "emails_changed");
        return Map.of("status", "sent", "messageId", messageId != null ? messageId : "");
    }

    private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Lista "a@x.com, b@y.com" → direcciones validadas. Vacío o nulo → lista vacía. */
    static List<String> parseAddressList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var addresses = new java.util.ArrayList<String>();
        for (var part : raw.split(",")) {
            var address = part.trim();
            if (address.isEmpty()) continue;
            if (!ADDRESS.matcher(address).matches()) {
                throw new IllegalArgumentException("invalid address: " + address);
            }
            addresses.add(address);
        }
        return addresses;
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
                    case "trash" -> trashOnly(user, provider, emailId);
                    case "restore" -> restoreOnly(user, provider, emailId);
                    case "delete" -> deleteForever(user, provider, emailId);
                    default -> throw new IllegalArgumentException("Unknown action: " + request.getAction());
                }
                applied++;
            } catch (Exception e) {
                log.warn("Bulk action {} failed for {}: {}", request.getAction(), emailId, e.getMessage());
                failed.add(emailId);
            }
        }

        // Un solo aviso para todo el lote: el dashboard refresca una vez, no 100.
        // El rebuild de contactos recorre todo el buzón y el primer barrido de
        // enviados tarda minutos: lo hace el próximo sync, no este request.
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
    /** El cursor ({@code historyCursor}) se guarda en {@code syncEmailsAsync} recién después de persistir los mails. */
    private record GoogleSyncResult(List<EmailMessage> emails, boolean deleted, boolean labelsChanged,
                                    OAuthToken oauthToken, String historyCursor) {}

    private GoogleSyncResult syncGoogle(User user, String accessToken) {
        var userId = user.getId();
        var oauthToken = oauthTokenRepository
                .findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google")
                .orElseThrow(() -> new RuntimeException("No OAuth token found for provider: google"));

        if (oauthToken.getHistoryId() != null) {
            var changes = gmailService.listHistory(accessToken, oauthToken.getHistoryId());
            if (changes != null) {
                var emails = applyHistoryChanges(user, accessToken, changes);
                return new GoogleSyncResult(emails, !changes.deletedIds().isEmpty(), changes.labelsChanged(),
                        oauthToken, changes.latestHistoryId());
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
            // Sin tope: el pedido es cargar todo el buzón, no una ventana de N mails.
            emails = gmailService.fetchAllEmails(accessToken, 100, Integer.MAX_VALUE);
        }
        log.info("Incremental fetch: {} new emails for user={}", emails.size(), userId);
        // Sin cursor no hay forma de saber si cambiaron las estrellas/spam: se refresca.
        return new GoogleSyncResult(emails, false, true, oauthToken, cursor);
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
        changes.trashedIds().forEach(id -> setTrashLabel(userId, id, true));
        changes.untrashedIds().forEach(id -> setTrashLabel(userId, id, false));

        var existingIds = fetchAllProviderEmailIds(userId);
        var newIds = changes.addedIds().stream()
                .filter(id -> !deletedIds.contains(id))
                .filter(id -> !existingIds.contains(id))
                .toList();
        if (newIds.isEmpty()) {
            return List.of();
        }
        return gmailService.fetchMessagesByIds(buildGmailClient(accessToken), newIds, accessToken);
    }

    private void persistHistoryId(OAuthToken oauthToken, String historyId) {
        if (historyId == null) return;
        // Solo historyId: la entidad pudo quedar vieja si otro hilo refrescó el token.
        oauthTokenRepository.updateHistoryId(oauthToken.getId(), historyId);
    }

    protected com.google.api.services.gmail.Gmail buildGmailClient(String accessToken) {
        return gmailService.buildGmailClient(accessToken);
    }

    public int saveEmails(List<EmailMessage> emails, User user, String provider) {
        return saveEmails(emails, user, provider, new ArrayList<>());
    }

    /**
     * {@code newEmailsOut} recibe sólo las filas insertadas: urgencias y contactos
     * deciden con eso, no con el lote entero (Outlook repite los mismos 100 cada sync).
     * Todo el guardado va en una transacción: antes, un fallo a mitad de batch dejaba
     * filas a medias y los existentes se guardaban de a uno.
     */
    private int saveEmails(List<EmailMessage> emails, User user, String provider,
                           List<EmailMessage> newEmailsOut) {
        if (emails.isEmpty()) {
            return 0;
        }
        return transactionTemplate.execute(status -> {
            int syncedCount = 0;

            // Una sola query en lugar de una por mensaje: en syncs completas de miles de
            // correos, la consulta individual era el cuello de botella dominante.
            var incomingIds = emails.stream()
                    .map(EmailMessage::getProviderEmailId)
                    .collect(Collectors.toSet());
            var existingById = new HashMap<String, EmailMessage>();
            emailRepository.findByUserIdAndProviderEmailIdIn(user.getId(), incomingIds)
                    .forEach(e -> existingById.put(e.getProviderEmailId(), e));

            var existingToSave = new ArrayList<EmailMessage>();
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
                    existingToSave.add(existing);
                } else {
                    email.setUser(user);
                    email.setProvider(provider);
                    email.setBodyHtml(htmlSanitizerService.sanitize(email.getBodyHtml()));
                    newEmails.add(email);
                    syncedCount++;
                }
            }
            if (!existingToSave.isEmpty()) {
                emailRepository.saveAll(existingToSave);
            }
            if (!newEmails.isEmpty()) {
                emailRepository.saveAll(newEmails);
            }
            newEmailsOut.addAll(newEmails);
            return syncedCount;
        });
    }

    /**
     * Best-effort refresh of starred/spam state for existing emails.
     * Nunca lanza: un fallo se registra y el sync continúa.
     *
     * <p>Se calcula el diff contra la DB: antes se hacía {@code unstarAll} +
     * {@code removeSpamLabelByUserId} + re-marcar todo en CADA sync (cada 2 min),
     * reescribiendo la tabla completa aunque no hubiera cambios.
     */
    public void refreshLabelsAndStars(User user, String accessToken) {
        try {
            var gmail = buildGmailClient(accessToken);
            var userId = user.getId();

            var starredIds = fetchAllMessageIds(gmail, "is:starred");
            var spamIds = fetchAllMessageIds(gmail, "in:spam");

            // Un destacado más viejo que la sincronización inicial no existe localmente y el
            // UPDATE de estrellas no lo encontraría: se importa aquí.
            var localIds = emailRepository.findByUserIdAndProviderEmailIdIn(userId, starredIds).stream()
                    .map(EmailMessage::getProviderEmailId)
                    .collect(Collectors.toSet());
            var missingStarred = starredIds.stream().filter(id -> !localIds.contains(id)).toList();
            if (!missingStarred.isEmpty()) {
                saveEmails(gmailService.fetchMessagesByIds(gmail, missingStarred, accessToken), user, "google");
            }

            transactionTemplate.execute(status -> {
                var localStarred = new HashSet<>(emailRepository.findStarredProviderEmailIds(userId));
                var starToAdd = new HashSet<>(starredIds);
                starToAdd.removeAll(localStarred);
                var starToRemove = new HashSet<>(localStarred);
                starToRemove.removeAll(starredIds);
                if (!starToRemove.isEmpty()) {
                    emailRepository.unstarByProviderEmailIds(userId, starToRemove);
                }
                if (!starToAdd.isEmpty()) {
                    emailRepository.starByProviderEmailIds(userId, starToAdd);
                }

                var localSpam = new HashSet<>(emailRepository.findSpamProviderEmailIds(userId));
                var spamToAdd = new HashSet<>(spamIds);
                spamToAdd.removeAll(localSpam);
                var spamToRemove = new HashSet<>(localSpam);
                spamToRemove.removeAll(spamIds);
                if (!spamToRemove.isEmpty()) {
                    emailRepository.removeSpamLabelByProviderEmailIds(userId, spamToRemove);
                }
                if (!spamToAdd.isEmpty()) {
                    emailRepository.addSpamLabelByProviderEmailIds(userId, spamToAdd);
                }
                return null;
            });

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
        trashOnly(user, provider, emailId);
        // El conteo de recibidos se reconstruye en el próximo sync (2 min), no acá:
        // hacerlo dentro del request bloqueaba la respuesta varios minutos.
    }

    public void restoreEmail(User user, String provider, String emailId) {
        restoreOnly(user, provider, emailId);
    }

    /** Saca el mail de spam (solo Gmail): le quita la etiqueta SPAM y lo devuelve a la bandeja. */
    public void unspamEmail(User user, String provider, String emailId) {
        if (!"google".equals(provider)) {
            throw new IllegalArgumentException("Quitar de spam solo está disponible en Gmail");
        }
        gmailService.unspam(getToken(user, provider), emailId);
        setLabel(user.getId(), emailId, "SPAM", false);
        log.info("Email {} removed from spam for user={}", emailId, user.getId());
    }

    public void deleteForeverEmail(User user, String provider, String emailId) {
        // Definitivo solo desde papelera o spam: el resto de los mails se mueve a papelera primero.
        boolean allowed = emailRepository.findByProviderEmailIdAndUserId(emailId, user.getId())
                .map(e -> containsLabelToken(e.getLabels(), "TRASH") || containsLabelToken(e.getLabels(), "SPAM"))
                .orElse(true);
        if (!allowed) {
            throw new IllegalArgumentException("Solo se puede eliminar definitivamente desde la papelera o spam");
        }
        deleteForever(user, provider, emailId);
    }

    private void trashOnly(User user, String provider, String emailId) {
        var token = getToken(user, provider);
        try {
            if ("google".equals(provider)) gmailService.moveToTrash(token, emailId);
            else outlookService.moveToTrash(token, emailId);
        } catch (TransientApiException e) {
            // El proveedor no respondió: se conserva la fila local para poder reintentar.
            throw e;
        } catch (PermanentApiException e) {
            // Sólo 404/410 significan "ya no existe" y habilitan borrar la fila local.
            // Un 401/403/500 deja el mail vivo en el proveedor: no se puede perder la fila.
            if (e.getStatusCode() != 404 && e.getStatusCode() != 410) {
                throw e;
            }
            log.warn("Email {} already gone on {} (HTTP {})", emailId, provider, e.getStatusCode());
        }

        // La papelera es local (label TRASH): si borrara la fila, no habría nada que listar
        // ni restaurar. Los listados y contadores excluyen TRASH por su cuenta.
        setTrashLabel(user.getId(), emailId, true);
        log.info("Email {} moved to trash for user={}", emailId, user.getId());
    }

    private void restoreOnly(User user, String provider, String emailId) {
        var token = getToken(user, provider);
        if ("google".equals(provider)) gmailService.untrash(token, emailId);
        else outlookService.untrash(token, emailId);

        setTrashLabel(user.getId(), emailId, false);
        log.info("Email {} restored for user={}", emailId, user.getId());
    }

    private void deleteForever(User user, String provider, String emailId) {
        var token = getToken(user, provider);
        try {
            if ("google".equals(provider)) gmailService.deleteForever(token, emailId);
            else outlookService.deleteForever(token, emailId);
        } catch (PermanentApiException e) {
            if (e.getStatusCode() == 403) {
                // Sin el permiso mail.google.com Gmail no permite el borrado definitivo.
                throw new PermanentApiException(
                        "Gmail rechazó el borrado definitivo: falta el permiso mail.google.com. "
                                + "Cerrá sesión y volvé a entrar para autorizarlo.", e, 403);
            }
            if (e.getStatusCode() != 404 && e.getStatusCode() != 410) throw e;
        }
        emailRepository.deleteByUserIdAndProviderEmailIdIn(user.getId(), List.of(emailId));
        vistoRepository.deleteByUserIdAndProviderEmailIdIn(user.getId(), List.of(emailId));
        log.info("Email {} permanently deleted for user={}", emailId, user.getId());
    }

    private void setTrashLabel(UUID userId, String providerEmailId, boolean trashed) {
        setLabel(userId, providerEmailId, "TRASH", trashed);
    }

    private void setLabel(UUID userId, String providerEmailId, String token, boolean present) {
        emailRepository.findByProviderEmailIdAndUserId(providerEmailId, userId).ifPresent(email -> {
            email.setLabels(present
                    ? addLabelToken(email.getLabels(), token)
                    : removeLabelToken(email.getLabels(), token));
            emailRepository.save(email);
        });
    }

    private static String addLabelToken(String labels, String token) {
        if (labels == null || labels.isBlank()) return token;
        return containsLabelToken(labels, token) ? labels : labels + "," + token;
    }

    private static String removeLabelToken(String labels, String token) {
        if (labels == null) return null;
        return Arrays.stream(labels.split(","))
                .map(String::trim)
                .filter(label -> !label.isEmpty() && !label.equals(token))
                .collect(Collectors.joining(","));
    }

    private static boolean containsLabelToken(String labels, String token) {
        return Arrays.stream(labels.split(",")).map(String::trim).anyMatch(token::equals);
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
            messageId = gmailService.sendEmail(token, to, subject, body, original.getThreadId());
        } else if ("outlook".equals(provider)) {
            messageId = outlookService.sendEmail(token, to, subject, body);
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        log.info("Reply sent: userId={}, provider={}, messageId={}",
                user.getId(), provider, messageId);
        contactService.upsertSent(user.getId(), to);
        recordSent(user, provider, messageId, to, subject, body);
        return Map.of("status", "sent", "messageId", messageId);
    }

    private String getToken(User user, String provider) {
        var oauthToken = oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(user.getId(), provider)
                .orElseThrow(() -> new RuntimeException("No OAuth token found for provider: " + provider));

        if (oauthToken.getExpiresAt() != null
        && Instant.now().plusSeconds(TOKEN_MARGIN_SECONDS).isAfter(oauthToken.getExpiresAt())) {
            log.info("OAuth token expired for user={}, provider={}. Attempting refresh.", user.getId(), provider);
            // Serializado: dos hilos no pueden refrescar con el mismo refresh token (rotado por el proveedor).
            synchronized (tokenRefreshLock) {
                return refreshStoredToken(oauthTokenRepository.findById(oauthToken.getId()).orElse(oauthToken));
            }
        }

        log.debug("Using stored OAuth token for user={}, provider={}", user.getId(), provider);
        return tokenCryptoService.decrypt(oauthToken.getAccessToken());
    }

    private String refreshStoredToken(OAuthToken oauthToken) {
        // Otro hilo pudo refrescar mientras esperábamos el lock: usar la fila nueva.
        if (oauthToken.getExpiresAt() == null
        || Instant.now().plusSeconds(TOKEN_MARGIN_SECONDS).isBefore(oauthToken.getExpiresAt())) {
            return tokenCryptoService.decrypt(oauthToken.getAccessToken());
        }
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

    private Map<String, Object> refreshToken(OAuthToken oauthToken) {
        var refreshToken = tokenCryptoService.decrypt(oauthToken.getRefreshToken());
        try {
            if ("google".equals(oauthToken.getProvider())) {
                return refreshGoogleToken(refreshToken);
            } else if ("outlook".equals(oauthToken.getProvider())) {
                return outlookService.refreshAccessToken(
                        refreshToken,
                        outlookClientId,
                        outlookClientSecret,
                        outlookTenant
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
        body.add("client_id", googleClientId);
        body.add("client_secret", googleClientSecret);
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        var response = restTemplate.exchange(
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
                .hasAttachment(email.isHasAttachment())
                .attachmentsJson(email.getAttachmentsJson())
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

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
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

        Object flagObj = raw.get("flag");
        if (flagObj instanceof Map<?, ?> flag) {
            email.setStarred("flagged".equals(flag.get("flagStatus")));
        }

        Object categoriesObj = raw.get("categories");
        if (categoriesObj instanceof List<?> categories) {
            email.setLabels(categories.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.joining(",")));
        }

        // Graph no expone "spam" en las categorías: la carpeta sí lo dice.
        if (Boolean.TRUE.equals(raw.get("isJunk"))) {
            email.setLabels(email.getLabels() == null || email.getLabels().isBlank()
                    ? "SPAM" : email.getLabels() + ",SPAM");
        }

        email.setThreadId((String) raw.get("conversationId"));

        if (raw.get("toRecipients") instanceof List<?> recipients) {
            email.setToAddresses(recipients.stream()
                    .filter(Map.class::isInstance)
                    .map(r -> ((Map<?, ?>) r).get("emailAddress"))
                    .filter(Map.class::isInstance)
                    .map(a -> (String) ((Map<?, ?>) a).get("address"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(",")));
        }

        var receivedStr = (String) raw.get("receivedDateTime");
        if (receivedStr != null) {
            email.setReceivedAt(Instant.parse(receivedStr));
        }

        return email;
    }
}