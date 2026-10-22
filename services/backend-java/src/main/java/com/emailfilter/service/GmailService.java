package com.emailfilter.service;

import com.emailfilter.dto.GmailHistoryChanges;
import com.emailfilter.exception.PermanentApiException;
import com.emailfilter.exception.TransientApiException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.emailfilter.util.QuotedPrintable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailService {

    private static final String APPLICATION_NAME = "Email Filter AI";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final RetryTemplate externalApiRetryTemplate;

    /**
     * Espaciado entre {@code messages.get} para respetar la cuota por usuario de
     * Gmail (6.000 unidades/min). Un {@code messages.get} cuesta 20 unidades, así
     * que 250ms equivale a ~240 req/min (~4.800 unidades), dejando margen para los
     * list. Es un espaciado POR USUARIO (ver {@link #throttleRequests(String)}), así
     * que la tasa de cada token no cambia aunque se descargue en paralelo.
     * Configurable via {@code EMAIL_SYNC_REQUEST_DELAY_MS}.
     */
    @Value("${email.sync.request-delay-ms:250}")
    private long requestDelayMs;

    /** Hilos para descargar detalles en paralelo. Configurable via {@code EMAIL_SYNC_FETCH_CONCURRENCY}. */
    @Value("${email.sync.fetch-concurrency:6}")
    private int fetchConcurrency;

    private final java.util.concurrent.ConcurrentHashMap<String, Long> nextRequestAtByKey =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile ExecutorService detailExecutor;

    protected Gmail buildGmailClient(String accessToken) {
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

    public List<com.emailfilter.model.EmailMessage> fetchAllEmails(String accessToken, int maxResultsPerPage, int maxTotal) {
        // Spam gets its own budget (previously it only got the inbox leftover, so it was never fetched)
        var inbox = fetchEmailsByQuery(accessToken, maxResultsPerPage, "in:inbox", maxTotal);
        var spam = fetchEmailsByQuery(accessToken, maxResultsPerPage, "in:spam", maxTotal);

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
        return fetchIncrementalEmails(accessToken, since, existingIds, Integer.MAX_VALUE);
    }

    public List<com.emailfilter.model.EmailMessage> fetchIncrementalEmails(
            String accessToken, java.time.Instant since, java.util.Set<String> existingIds, int maxTotal) {
        var gmail = buildGmailClient(accessToken);
        if (maxTotal <= 0) maxTotal = Integer.MAX_VALUE;

        // Format date for Gmail's after: query (YYYY/MM/DD)
        var dateFormatter = new java.text.SimpleDateFormat("yyyy/MM/dd");
        var sinceDate = dateFormatter.format(java.util.Date.from(since));

        // Fetch BOTH inbox and spam (spam was previously never queried in incremental sync)
        var inbox = fetchIncrementalByQuery(gmail, "in:inbox after:" + sinceDate, existingIds, accessToken, maxTotal);
        var spam = inbox.size() >= maxTotal
                ? List.<com.emailfilter.model.EmailMessage>of()
                : fetchIncrementalByQuery(gmail, "in:spam after:" + sinceDate, existingIds,
                        accessToken, maxTotal - inbox.size());

        // Merge with dedup by providerEmailId (a message can appear in both inbox and spam)
        var all = new java.util.LinkedHashMap<String, com.emailfilter.model.EmailMessage>();
        for (var e : inbox) all.put(e.getProviderEmailId(), e);
        for (var e : spam) all.putIfAbsent(e.getProviderEmailId(), e);

        log.info("Incremental fetch: {} new emails found (skipped {} existing)", all.size(), existingIds.size());
        return new java.util.ArrayList<>(all.values());
    }

    private List<com.emailfilter.model.EmailMessage> fetchIncrementalByQuery(
            Gmail gmail, String query, java.util.Set<String> existingIds, String throttleKey, int maxNew) {
        var newEmails = new java.util.ArrayList<com.emailfilter.model.EmailMessage>();

        String pageToken = null;
        do {
            final String currentToken = pageToken;
            var response = retryTemplateExecute(() -> {
                var request = gmail.users().messages().list("me")
                        .setQ(query)
                        .setMaxResults(100L);
                if (currentToken != null && !currentToken.isBlank()) {
                    request.setPageToken(currentToken);
                }
                return request.execute();
            });
            var messages = response.getMessages();
            if (messages == null || messages.isEmpty()) break;

            for (var msg : messages) {
                if (newEmails.size() >= maxNew) break;
                // Skip messages we already have in the DB
                if (existingIds.contains(msg.getId())) continue;
                // Fetch full details only for new messages
                newEmails.add(fetchMessageDetail(gmail, msg, throttleKey));
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank() && newEmails.size() < maxNew);

        return newEmails;
    }

    private List<com.emailfilter.model.EmailMessage> fetchEmailsByQuery(String accessToken, int maxResultsPerPage, String query, int maxTotal) {
        var gmail = buildGmailClient(accessToken);
        var allEmails = new java.util.ArrayList<com.emailfilter.model.EmailMessage>();
        String pageToken = null;

        do {
            final String currentToken = pageToken;
            // Request creation declares IOException too: build it inside the retry
            // lambda so RetryTemplate classifies/retries network errors uniformly.
            var response = retryTemplateExecute(() -> {
                var request = gmail.users().messages().list("me")
                        .setQ(query)
                        .setMaxResults((long) Math.min(maxResultsPerPage, maxTotal - allEmails.size()));
                if (currentToken != null && !currentToken.isBlank()) {
                    request.setPageToken(currentToken);
                }
                return request.execute();
            });

            var messages = response.getMessages();

            if (messages == null || messages.isEmpty()) break;

            for (var msg : messages) {
                if (allEmails.size() >= maxTotal) break;
                allEmails.add(fetchMessageDetail(gmail, msg, accessToken));
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank() && allEmails.size() < maxTotal);

        return allEmails;
    }

    private com.emailfilter.model.EmailMessage fetchMessageDetail(Gmail gmail, Message msg, String throttleKey) {
        var full = retryTemplateExecute(() -> {
            throttleRequests(throttleKey);
            return gmail.users().messages().get("me", msg.getId()).setFormat("full").execute();
        });
        return mapToEmail(full);
    }

    /**
     * Descarga en paralelo el detalle de una tanda de IDs del full sync, para que
     * TODA descarga de detalle comparta el mismo retry/clasificación y el throttle
     * que el camino incremental. El espaciado global de {@link #throttleRequests()}
     * mantiene la tasa bajo la cuota por usuario, así que la concurrencia solo
     * solapa la latencia de red (no sube la tasa). Devuelve solo los mensajes que
     * se pudieron bajar, en el mismo orden de entrada.
     */
    public List<com.emailfilter.model.EmailMessage> fetchMessagesByIds(Gmail gmail, List<String> messageIds) {
        return fetchMessagesByIds(gmail, messageIds, "global");
    }

    public List<com.emailfilter.model.EmailMessage> fetchMessagesByIds(Gmail gmail, List<String> messageIds,
                                                                       String throttleKey) {
        if (messageIds == null || messageIds.isEmpty()) return List.of();
        var futures = messageIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                    try {
                        var full = retryTemplateExecute(() -> {
                            throttleRequests(throttleKey);
                            return gmail.users().messages().get("me", id).setFormat("full").execute();
                        });
                        return mapToEmail(full);
                    } catch (PermanentApiException e) {
                        // 404/410: el mail ya no existe; cualquier otro 4xx (token, permisos) corta el sync.
                        if (e.getStatusCode() == 404 || e.getStatusCode() == 410) {
                            log.warn("Message {} is gone (HTTP {}), skipped", id, e.getStatusCode());
                            return null;
                        }
                        throw e;
                    } catch (TransientApiException e) {
                        // Un fallo de red no puede dejar el mail afuera en silencio: el cursor no debe avanzar.
                        throw e;
                    } catch (Exception e) {
                        log.warn("Failed to fetch message {}: {}", id, e.getMessage());
                        return null;
                    }
                }, detailExecutor()))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ExecutorService detailExecutor() {
        if (detailExecutor == null) {
            synchronized (this) {
                if (detailExecutor == null) {
                    detailExecutor = Executors.newFixedThreadPool(Math.max(1, fetchConcurrency), runnable -> {
                        var thread = new Thread(runnable, "gmail-detail-fetch");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return detailExecutor;
    }

    private com.emailfilter.model.EmailMessage mapToEmail(Message full) {
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
            body = decodePart(payload);
        }

        var labelIds = full.getLabelIds() != null ? full.getLabelIds() : List.<String>of();
        var labels = String.join(",", labelIds);
        var isRead = !labelIds.contains("UNREAD");
        var isStarred = labelIds.contains("STARRED");

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
    }

    /**
     * Direcciones que recibieron los envíos de la ventana, con cuántos mails les
     * mandaste. Sólo lee metadatos: nada de esto entra en {@code email_messages}.
     * Un fallo no se propaga, la lista de contactos simplemente queda sin enviados.
     */
    public Map<String, Integer> fetchSentRecipients(String accessToken, int windowDays, int maxTotal) {
        var counts = new HashMap<String, Integer>();
        try {
            var gmail = buildGmailClient(accessToken);
            var since = new java.text.SimpleDateFormat("yyyy/MM/dd")
                    .format(java.util.Date.from(Instant.now().minus(windowDays, java.time.temporal.ChronoUnit.DAYS)));
            var query = "in:sent after:" + since;
            String pageToken = null;
            int seen = 0;
            do {
                final String currentToken = pageToken;
                var response = retryTemplateExecute(() -> {
                    var request = gmail.users().messages().list("me")
                            .setQ(query)
                            .setMaxResults(100L);
                    if (currentToken != null && !currentToken.isBlank()) {
                        request.setPageToken(currentToken);
                    }
                    return request.execute();
                });
                var messages = response.getMessages();
                if (messages == null || messages.isEmpty()) break;

                for (var msg : messages) {
                    if (seen >= maxTotal) break;
                    seen++;
                    for (var address : recipientsOf(gmail, msg.getId(), accessToken)) {
                        counts.merge(address, 1, Integer::sum);
                    }
                }
                pageToken = response.getNextPageToken();
            } while (pageToken != null && !pageToken.isBlank() && seen < maxTotal);
        } catch (Exception e) {
            log.warn("Sent-recipient scan failed: {}", e.getMessage());
            return Map.of();
        }
        return counts;
    }

    private Set<String> recipientsOf(Gmail gmail, String messageId, String throttleKey) {
        var full = retryTemplateExecute(() -> {
            throttleRequests(throttleKey);
            return gmail.users().messages().get("me", messageId)
                    .setFormat("metadata")
                    .setMetadataHeaders(List.of("To", "Cc"))
                    .execute();
        });
        var headers = full.getPayload() != null ? full.getPayload().getHeaders() : null;
        var recipients = new java.util.LinkedHashSet<String>();
        if (headers == null) return recipients;
        for (var header : headers) {
            if (!"To".equalsIgnoreCase(header.getName()) && !"Cc".equalsIgnoreCase(header.getName())) continue;
            recipients.addAll(splitAddresses(header.getValue()));
        }
        return recipients;
    }

    /** Separa por comas fuera de comillas y de {@code <...>}: "Doe, Juan" &lt;j@x.com&gt;. */
    static Set<String> splitAddresses(String raw) {
        var result = new java.util.LinkedHashSet<String>();
        if (raw == null) return result;
        var current = new StringBuilder();
        boolean quoted = false;
        boolean angled = false;
        for (char c : raw.toCharArray()) {
            if (c == '"') quoted = !quoted;
            else if (c == '<') angled = true;
            else if (c == '>') angled = false;
            else if (c == ',' && !quoted && !angled) {
                addAddress(result, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addAddress(result, current.toString());
        return result;
    }

    private static void addAddress(Set<String> target, String raw) {
        var value = raw.trim();
        int lt = value.lastIndexOf('<');
        int gt = value.lastIndexOf('>');
        if (lt >= 0 && gt > lt) value = value.substring(lt + 1, gt).trim();
        value = value.replace("\"", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (value.contains("@")) target.add(value);
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

    /**
     * Cursor actual del buzón ({@code users.getProfile}). Se guarda para usarlo
     * como {@code startHistoryId} en el próximo sync incremental.
     */
    public String getHistoryId(String accessToken) {
        var gmail = buildGmailClient(accessToken);
        var profile = retryTemplateExecute(() -> gmail.users().getProfile("me").execute());
        return profile.getHistoryId() != null ? profile.getHistoryId().toString() : null;
    }

    /**
     * Cambios del buzón desde {@code startHistoryId} ({@code users.history.list},
     * 2 unidades de cuota). Un cursor vencido devuelve 404: se devuelve
     * {@code null} para que el llamador haga un resync por fecha.
     *
     * <p>"Eliminar" en Gmail mueve el mail a la papelera (labelAdded TRASH), no
     * emite {@code messageDeleted}, así que ambos cuentan como borrado local.
     */
    public GmailHistoryChanges listHistory(String accessToken, String startHistoryId) {
        var gmail = buildGmailClient(accessToken);
        var added = new LinkedHashSet<String>();
        var deleted = new LinkedHashSet<String>();
        String latestHistoryId = startHistoryId;
        String pageToken = null;
        try {
            do {
                final String currentToken = pageToken;
                var response = retryTemplateExecute(() -> {
                    var request = gmail.users().history().list("me")
                            .setStartHistoryId(new BigInteger(startHistoryId))
                            .setHistoryTypes(List.of("messageAdded", "messageDeleted", "labelAdded", "labelRemoved"))
                            .setMaxResults(500L);
                    if (currentToken != null && !currentToken.isBlank()) {
                        request.setPageToken(currentToken);
                    }
                    return request.execute();
                });

                if (response.getHistory() != null) {
                    for (var history : response.getHistory()) {
                        if (history.getMessagesAdded() != null) {
                            for (var item : history.getMessagesAdded()) {
                                if (item.getMessage() != null && isInboxOrSpam(item.getMessage())) {
                                    added.add(item.getMessage().getId());
                                }
                            }
                        }
                        // Restaurado desde la papelera: vuelve a entrar si falta localmente.
                        if (history.getLabelsRemoved() != null) {
                            for (var item : history.getLabelsRemoved()) {
                                if (item.getMessage() != null && item.getLabelIds() != null
                                        && item.getLabelIds().contains("TRASH") && isInboxOrSpam(item.getMessage())) {
                                    deleted.remove(item.getMessage().getId());
                                    added.add(item.getMessage().getId());
                                }
                            }
                        }
                        if (history.getMessagesDeleted() != null) {
                            for (var item : history.getMessagesDeleted()) {
                                if (item.getMessage() != null) deleted.add(item.getMessage().getId());
                            }
                        }
                        // "Eliminar" en Gmail = mover a papelera: llega como labelAdded TRASH.
                        if (history.getLabelsAdded() != null) {
                            for (var item : history.getLabelsAdded()) {
                                if (item.getMessage() != null && item.getLabelIds() != null
                                        && item.getLabelIds().contains("TRASH")) {
                                    deleted.add(item.getMessage().getId());
                                }
                            }
                        }
                    }
                }
                if (response.getHistoryId() != null) {
                    latestHistoryId = response.getHistoryId().toString();
                }
                pageToken = response.getNextPageToken();
            } while (pageToken != null && !pageToken.isBlank());
        } catch (PermanentApiException e) {
            log.warn("history.list startHistoryId={} is stale or invalid, a date-based resync is needed: {}",
                    startHistoryId, e.getMessage());
            return null;
        }
        log.info("history.list since {}: {} added, {} removed, new cursor {}",
                startHistoryId, added.size(), deleted.size(), latestHistoryId);
        return new GmailHistoryChanges(added, deleted, latestHistoryId);
    }

    /** Enviados y borradores no van en email_messages: sólo bandeja y spam. */
    private static boolean isInboxOrSpam(Message message) {
        var labels = message.getLabelIds();
        return labels == null || labels.contains("INBOX") || labels.contains("SPAM");
    }

    public String sendEmail(String accessToken, String to, String subject, String body) {
        return sendEmail(accessToken, to, subject, body, null);
    }

    public String sendEmail(String accessToken, String to, String subject, String body, String threadId) {
        var gmail = buildGmailClient(accessToken);
        var raw = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(buildMimeMessage(to, subject, body).getBytes(StandardCharsets.UTF_8));
        var message = new Message().setRaw(raw);
        if (threadId != null && !threadId.isBlank()) {
            // Sin threadId Gmail abre un hilo nuevo y la respuesta queda suelta.
            message.setThreadId(threadId);
        }
        var response = retryTemplateExecute(() ->
                gmail.users().messages().send("me", message).execute());
        log.info("Gmail message sent: id={}", response.getId());
        return response.getId();
    }

    String buildMimeMessage(String to, String subject, String body) {
        return "From: me\r\n"
                + "To: " + to + "\r\n"
                + "Subject: " + encodeHeader(subject) + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + body;
    }

    /** RFC 2047: los headers MIME no son UTF-8; un asunto con acentos viaja en base64. */
    static String encodeHeader(String value) {
        if (value == null || value.chars().allMatch(c -> c < 0x80)) {
            return value;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private void modifyMessage(String accessToken, String messageId,
                               List<String> addLabels, List<String> removeLabels) {
        var gmail = buildGmailClient(accessToken);
        var modRequest = new ModifyMessageRequest()
                .setAddLabelIds(addLabels)
                .setRemoveLabelIds(removeLabels);
        retryTemplateExecute(() -> {
            gmail.users().messages().modify("me", messageId, modRequest).execute();
            return null;
        });
    }

    private <T> T retryTemplateExecute(RetryCall<T> call) {
        return externalApiRetryTemplate.execute(context -> {
            try {
                return call.execute();
            } catch (IOException e) {
                throw handleGmailApiException(e);
            }
        });
    }

    private RuntimeException handleGmailApiException(Exception e) {
        if (e instanceof GoogleJsonResponseException gjre) {
            int status = gjre.getStatusCode();
            if (status == 429) {
                respectRetryAfter(gjre);
                return new TransientApiException("Rate limit alcanzado (429)", e);
            }
            // Gmail devuelve 403 PERMISSION_DENIED con reason "rateLimitExceeded" cuando se
            // supera la cuota por usuario (p. ej. totalQueryCostPerMinutePerUser). Es
            // TRANSIENTE: con backoff + throttle se resuelve. Antes se trataba como
            // permanente y abortaba el sync completo, dejando 0 emails.
            if (status == 403 && isRateLimitReason(gjre)) {
                respectRetryAfter(gjre);
                return new TransientApiException("Rate limit alcanzado (403 rateLimitExceeded)", e);
            }
            if (status >= 400 && status < 500) {
                // 401/403 (token vencido, permisos revocados) y otros 4xx no se arreglan reintentando
                return new PermanentApiException("Gmail API error permanente (HTTP " + status + ")", e, status);
            }
            return new TransientApiException("Gmail API error transitorio (HTTP " + status + ")", e);
        }
        if (e instanceof IOException) {
            return new TransientApiException("Error de red al llamar a Gmail API", e);
        }
        return new RuntimeException(e);
    }

    /**
     * Distingue un 403 por cuota (transitorio) de uno por permisos (permanente).
     * Google expone la causa en {@code details.errors[].reason}.
     */
    private boolean isRateLimitReason(GoogleJsonResponseException gjre) {
        var details = gjre.getDetails();
        if (details == null || details.getErrors() == null) {
            return false;
        }
        for (var error : details.getErrors()) {
            var reason = error.getReason();
            if (reason == null) continue;
            var normalized = reason.toLowerCase();
            if (normalized.contains("ratelimit") || normalized.contains("quota")) {
                return true;
            }
        }
        return false;
    }

    private void respectRetryAfter(GoogleJsonResponseException gjre) {
        try {
            var headers = gjre.getHeaders();
            var retryAfter = headers != null ? headers.getRetryAfter() : null;
            if (retryAfter != null) {
                sleepQuietly(Long.parseLong(retryAfter) * 1000);
            }
        } catch (NumberFormatException ignored) {
            // header no numérico: se usa el backoff exponencial por defecto
        }
    }

    /**
     * Espaciado por usuario (clave = access token) entre requests de detalle. Cada
     * usuario tiene su propia cuota de Gmail: un sync pesado no debe frenar a otro.
     * Reserva el próximo slot bajo lock y duerme lo justo; la concurrencia sólo
     * solapa latencia de red, no sube la tasa.
     */
    private void throttleRequests(String key) {
        if (requestDelayMs <= 0) return;
        var lockKey = key != null ? key : "global";
        long wait;
        synchronized (nextRequestAtByKey) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextRequestAtByKey.getOrDefault(lockKey, 0L));
            nextRequestAtByKey.put(lockKey, slot + requestDelayMs);
            // Los slots vencidos no aportan espaciado: se descartan para que el mapa no crezca.
            nextRequestAtByKey.values().removeIf(v -> v < now);
            wait = slot - now;
        }
        if (wait > 0) sleepQuietly(wait);
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
        T execute() throws IOException;
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

    /** Preferimos el HTML (se sanitiza y se pinta en un iframe); si no hay, el texto plano. */
    private String getBodyFromParts(List<MessagePart> parts) {
        var plainText = new StringBuilder();
        var htmlText = new StringBuilder();
        collectBodyParts(parts, plainText, htmlText);
        return !htmlText.isEmpty() ? htmlText.toString() : plainText.toString();
    }

    private void collectBodyParts(List<MessagePart> parts, StringBuilder plainText, StringBuilder htmlText) {
        for (var part : parts) {
            if (part.getParts() != null && !part.getParts().isEmpty()) {
                collectBodyParts(part.getParts(), plainText, htmlText);
                continue;
            }
            if (part.getBody() == null || part.getBody().getData() == null) continue;
            if ("text/plain".equals(part.getMimeType())) plainText.append(decodePart(part));
            else if ("text/html".equals(part.getMimeType())) htmlText.append(decodePart(part));
        }
    }

    private String decodePart(MessagePart part) {
        var bytes = Base64.getUrlDecoder().decode(part.getBody().getData());
        var headers = part.getHeaders() != null ? part.getHeaders() : List.<MessagePartHeader>of();
        var charset = charsetOf(getHeader(headers, "Content-Type"));
        var transferEncoding = getHeader(headers, "Content-Transfer-Encoding");
        if ("quoted-printable".equalsIgnoreCase(transferEncoding.trim())) {
            return QuotedPrintable.decode(bytes, charset);
        }
        return new String(bytes, charset);
    }

    private static final java.util.regex.Pattern CHARSET = java.util.regex.Pattern.compile(
            "charset=\"?([^\";\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Charset declarado en el Content-Type de la parte; UTF-8 si falta o no se reconoce. */
    private static java.nio.charset.Charset charsetOf(String contentType) {
        var m = CHARSET.matcher(contentType);
        if (!m.find()) return StandardCharsets.UTF_8;
        try {
            return java.nio.charset.Charset.forName(m.group(1));
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
