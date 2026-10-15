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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * list. Es un espaciado GLOBAL (ver {@link #throttleRequests()}), así que la
     * tasa agregada no cambia aunque se descargue en paralelo.
     * Configurable via {@code EMAIL_SYNC_REQUEST_DELAY_MS}.
     */
    @Value("${email.sync.request-delay-ms:250}")
    private long requestDelayMs;

    /** Hilos para descargar detalles en paralelo. Configurable via {@code EMAIL_SYNC_FETCH_CONCURRENCY}. */
    @Value("${email.sync.fetch-concurrency:6}")
    private int fetchConcurrency;

    private final Object throttleLock = new Object();
    private long nextRequestAtMs = 0L;
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

    public Map<String, Object> fetchEmails(String accessToken, int maxResults, String pageToken) {
        var gmail = buildGmailClient(accessToken);

        // Request creation (List) also declares IOException, so build it inside the
        // retry lambda where IOException is already handled/classified.
        var response = retryTemplateExecute(() -> {
            var request = gmail.users().messages().list("me")
                    .setMaxResults((long) maxResults);
            if (pageToken != null && !pageToken.isBlank()) {
                request.setPageToken(pageToken);
            }
            return request.execute();
        });

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
        var gmail = buildGmailClient(accessToken);

        // Format date for Gmail's after: query (YYYY/MM/DD)
        var dateFormatter = new java.text.SimpleDateFormat("yyyy/MM/dd");
        var sinceDate = dateFormatter.format(java.util.Date.from(since));

        // Fetch BOTH inbox and spam (spam was previously never queried in incremental sync)
        var inbox = fetchIncrementalByQuery(gmail, "in:inbox after:" + sinceDate, existingIds);
        var spam = fetchIncrementalByQuery(gmail, "in:spam after:" + sinceDate, existingIds);

        // Merge with dedup by providerEmailId (a message can appear in both inbox and spam)
        var all = new java.util.LinkedHashMap<String, com.emailfilter.model.EmailMessage>();
        for (var e : inbox) all.put(e.getProviderEmailId(), e);
        for (var e : spam) all.putIfAbsent(e.getProviderEmailId(), e);

        log.info("Incremental fetch: {} new emails found (skipped {} existing)", all.size(), existingIds.size());
        return new java.util.ArrayList<>(all.values());
    }

    private List<com.emailfilter.model.EmailMessage> fetchIncrementalByQuery(
            Gmail gmail, String query, java.util.Set<String> existingIds) {
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
                // Skip messages we already have in the DB
                if (existingIds.contains(msg.getId())) continue;
                // Fetch full details only for new messages
                newEmails.add(fetchMessageDetail(gmail, msg));
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank());

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
                allEmails.add(fetchMessageDetail(gmail, msg));
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank() && allEmails.size() < maxTotal);

        return allEmails;
    }

    private com.emailfilter.model.EmailMessage fetchMessageDetail(Gmail gmail, Message msg) {
        var full = retryTemplateExecute(() -> {
            throttleRequests();
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
        if (messageIds == null || messageIds.isEmpty()) return List.of();
        var futures = messageIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                    try {
                        var full = retryTemplateExecute(() -> {
                            throttleRequests();
                            return gmail.users().messages().get("me", id).setFormat("full").execute();
                        });
                        return mapToEmail(full);
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
                            .setHistoryTypes(List.of("messageAdded", "messageDeleted", "labelAdded"))
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
                                if (item.getMessage() != null) added.add(item.getMessage().getId());
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

    public String sendEmail(String accessToken, String to, String subject, String body) {
        var gmail = buildGmailClient(accessToken);
        var raw = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(buildMimeMessage(to, subject, body).getBytes(StandardCharsets.UTF_8));
        var response = retryTemplateExecute(() ->
                gmail.users().messages().send("me", new Message().setRaw(raw)).execute());
        log.info("Gmail message sent: id={}, to={}, subject={}", response.getId(), to, subject);
        return response.getId();
    }

    String buildMimeMessage(String to, String subject, String body) {
        return "From: me\r\n"
                + "To: " + to + "\r\n"
                + "Subject: " + subject + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + body;
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
                return new PermanentApiException("Gmail API error permanente (HTTP " + status + ")", e);
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
     * Espaciado global (compartido entre todos los hilos) entre requests de
     * detalle. Reserva el próximo slot bajo lock y duerme lo justo: la tasa
     * agregada queda en ~1000/delay req/s aunque se descargue en paralelo.
     */
    private void throttleRequests() {
        if (requestDelayMs <= 0) return;
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            if (nextRequestAtMs > now) {
                sleepQuietly(nextRequestAtMs - now);
                now = System.currentTimeMillis();
            }
            nextRequestAtMs = Math.max(now, nextRequestAtMs) + requestDelayMs;
        }
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
        var transferEncoding = getHeader(headers, "Content-Transfer-Encoding");
        if ("quoted-printable".equalsIgnoreCase(transferEncoding.trim())) {
            return QuotedPrintable.decode(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
