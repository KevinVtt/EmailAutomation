package com.emailfilter.controller;

import com.emailfilter.dto.BulkActionRequest;
import com.emailfilter.dto.ComposeRequest;
import com.emailfilter.dto.EmailDTO;
import com.emailfilter.dto.PrioritySummaryDTO;
import com.emailfilter.dto.RewriteRequest;
import com.emailfilter.security.UserDetailsServiceImpl;
import com.emailfilter.service.AIServiceClient;
import com.emailfilter.service.EmailService;
import com.emailfilter.service.SyncJobService;
import com.emailfilter.service.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;
    private final WebSocketService webSocketService;
    private final UserDetailsServiceImpl userDetailsService;
    private final AIServiceClient aiServiceClient;
    private final SyncJobService syncJobService;

    @GetMapping("/sent")
    public ResponseEntity<Page<EmailDTO>> getSentEmails(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.getSentEmails(userId, clampPage(page), clampSize(size)));
    }

    @GetMapping
    public ResponseEntity<Page<EmailDTO>> getEmails(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.getEmails(userId, clampPage(page), clampSize(size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmailDTO> getEmail(
            Authentication auth,
            @PathVariable UUID id) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.getEmailById(userId, id));
    }

    @GetMapping("/{id}/attachments/{index}")
    public ResponseEntity<byte[]> downloadAttachment(
            Authentication auth,
            @PathVariable UUID id,
            @PathVariable int index) {
        var file = emailService.downloadAttachment(getUserId(auth), id, index);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename((String) file.get("filename"), java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.parseMediaType((String) file.get("mimeType")))
                .body((byte[]) file.get("data"));
    }

    @GetMapping("/filter")
    public ResponseEntity<Page<EmailDTO>> filterEmails(
            Authentication auth,
            @RequestParam Map<String, String> filters,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.filterEmails(userId, filters, clampPage(page), clampSize(size)));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<EmailDTO>> searchEmails(
            Authentication auth,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "inbox") String folder,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.searchEmails(userId, q, folder, clampPage(page), clampSize(size)));
    }

    @PostMapping("/action")
    public ResponseEntity<Void> performAction(
            Authentication auth,
            @RequestBody Map<String, Object> action) {
        var userId = getUserId(auth);

        var provider = (String) action.get("provider");
        var emailId = (String) action.get("emailId");
        var actionType = (String) action.get("action");

        if (provider == null || provider.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (emailId == null || emailId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (actionType == null || actionType.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // Sin esto, cualquier id de Gmail (de otra cuenta) se modificaba en el proveedor.
        if (!emailService.ownsEmail(userId, emailId)) {
            return ResponseEntity.notFound().build();
        }

        var user = userDetailsService.loadUserEntityById(userId);
        switch (actionType) {
            case "read" -> emailService.markAsRead(user, provider, emailId);
            case "unread" -> emailService.markAsUnread(user, provider, emailId);
            case "star" -> emailService.toggleStar(user, provider, emailId, true);
            case "unstar" -> emailService.toggleStar(user, provider, emailId, false);
            case "trash" -> emailService.moveToTrash(user, provider, emailId);
            case "restore" -> emailService.restoreEmail(user, provider, emailId);
            case "delete" -> emailService.deleteForeverEmail(user, provider, emailId);
            default -> throw new IllegalArgumentException("Unknown action: " + actionType);
        }

        // Aviso para que el dashboard refresque contadores (no leídos, total).
        webSocketService.sendNotification(userId.toString(), "emails_changed");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncEmails(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        var userId = getUserId(auth);
        var provider = body.get("provider");
        if (provider == null || provider.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        }
        var user = userDetailsService.loadUserEntityById(userId);
        // Si ya hay un sync corriendo, el pedido se descartaría en silencio: decirlo en la respuesta
        // en vez de devolver un 202 que miente (el usuario veía "Sincronizando..." y no pasaba nada).
        if (emailService.isSyncing(userId)) {
            return ResponseEntity.ok(Map.of("status", "already_syncing"));
        }
        // Llamada directa al método @Async: si se pasara por un wrapper propio, la
        // auto-invocación saltearía el proxy de Spring y la sync correría sincrónica
        // en el hilo del request (el 202 no volvía hasta terminar).
        emailService.syncEmailsAsync(user, provider);
        return ResponseEntity.accepted().body(Map.of("status", "syncing"));
    }

    @GetMapping("/sync/status")
    public ResponseEntity<Map<String, Object>> syncStatus(Authentication auth) {
        var userId = getUserId(auth);
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("syncing", emailService.isSyncing(userId));
        // Último job: sin esto el poll de la UI no distingue "terminó bien" de "falló".
        syncJobService.lastForUser(userId).ifPresent(job -> {
            var last = new java.util.LinkedHashMap<String, Object>();
            last.put("status", job.getStatus());
            last.put("mode", job.getMode());
            last.put("fetched", job.getFetched());
            last.put("total", job.getTotal());
            last.put("error", job.getErrorMessage());
            last.put("updatedAt", job.getUpdatedAt() == null ? null : job.getUpdatedAt().toString());
            body.put("lastSync", last);
        });
        return ResponseEntity.ok(body);
    }

    @PostMapping("/sync/full")
    public ResponseEntity<Map<String, Object>> syncAllEmails(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        var userId = getUserId(auth);
        var provider = body.get("provider");
        if (provider == null || provider.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        }
        var user = userDetailsService.loadUserEntityById(userId);
        if (emailService.isSyncing(userId)) {
            return ResponseEntity.ok(Map.of("status", "already_syncing"));
        }
        // Sólo Google tiene full sync; Outlook cae al incremental, llamado directo para que el @Async aplique.
        if ("google".equals(provider)) {
            emailService.syncAllEmailsAsync(user, provider);
            return ResponseEntity.accepted().body(Map.of("status", "syncing", "mode", "full"));
        }
        emailService.syncEmailsAsync(user, provider);
        // Decir "full" acá hacía creer que se había bajado todo el historial.
        return ResponseEntity.accepted().body(Map.of("status", "syncing", "mode", "incremental"));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getDailySummary(
            Authentication auth,
            @RequestParam(defaultValue = "today") String date) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.getDailySummary(userId, date));
    }

    @GetMapping("/priority-summary")
    public ResponseEntity<PrioritySummaryDTO> getPrioritySummary(Authentication auth) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.buildPrioritySummary(userId));
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> emailCount(Authentication auth) {
        var userId = getUserId(auth);
        var count = emailService.getEmailCountByUser(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(Authentication auth) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.getStats(userId));
    }

    @PostMapping("/rewrite")
    public ResponseEntity<Map<String, Object>> rewriteEmail(
            Authentication auth,
            @jakarta.validation.Valid @RequestBody RewriteRequest request) {
        var userId = getUserId(auth);
        log.info("=== REWRITE INICIO === userId={}, tone={}, language={}, draft_len={}",
                userId, request.getTone(), request.getLanguage(), request.getDraft().length());

        var result = aiServiceClient.rewrite(
                request.getDraft(),
                request.getOriginalSubject(),
                request.getOriginalFrom(),
                request.getOriginalBody(),
                request.getTone(),
                request.getLanguage(),
                request.getCustomRules(),
                // The name never comes from the request body: it is the authenticated user's.
                userDetailsService.loadUserEntityById(userId).getName()
        );

        log.info("=== REWRITE FIN === userId={}", userId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendEmail(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        var userId = getUserId(auth);
        var provider = body.get("provider");
        var emailId = body.get("emailId");
        var replyBody = body.get("body");

        if (provider == null || provider.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        }
        if (emailId == null || emailId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "emailId is required"));
        }
        if (replyBody == null || replyBody.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is required"));
        }
        // Mismo tope que ComposeRequest: sin esto se acepta y se guarda un body
        // de decenas de MB en sent_messages y en el proveedor.
        if (replyBody.length() > 50_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "body too long (max 50000)"));
        }

        var user = userDetailsService.loadUserEntityById(userId);
        log.info("Sending reply: userId={}, provider={}, emailId={}, body_len={}",
                userId, provider, emailId, replyBody.length());
        var result = emailService.sendReply(user, provider, emailId, replyBody);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/bulk-action")
    public ResponseEntity<Map<String, Object>> bulkAction(
            Authentication auth,
            @jakarta.validation.Valid @RequestBody BulkActionRequest request) {
        var userId = getUserId(auth);
        var user = userDetailsService.loadUserEntityById(userId);
        log.info("Bulk action: userId={}, action={}, ids={}",
                userId, request.getAction(), request.getEmailIds().size());
        return ResponseEntity.ok(emailService.bulkAction(user, request));
    }

    @PostMapping("/compose")
    public ResponseEntity<Map<String, Object>> compose(
            Authentication auth,
            @jakarta.validation.Valid @RequestBody ComposeRequest request) {
        var userId = getUserId(auth);
        var user = userDetailsService.loadUserEntityById(userId);
        log.info("Composing new mail: userId={}, to={}, subject_len={}",
                userId, request.getTo(), request.getSubject().length());
        return ResponseEntity.ok(emailService.composeAndSend(user, request));
    }

    private UUID getUserId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }

    private static int clampPage(int page) {
        return Math.max(0, page);
    }

    private static int clampSize(int size) {
        return Math.max(1, Math.min(size, 100));
    }
}
