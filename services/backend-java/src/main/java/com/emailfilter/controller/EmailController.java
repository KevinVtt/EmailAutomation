package com.emailfilter.controller;

import com.emailfilter.dto.EmailDTO;
import com.emailfilter.security.UserDetailsServiceImpl;
import com.emailfilter.service.EmailService;
import com.emailfilter.service.WebSocketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;
    private final WebSocketService webSocketService;
    private final UserDetailsServiceImpl userDetailsService;

    @GetMapping
    public ResponseEntity<Page<EmailDTO>> getEmails(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.getEmails(userId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmailDTO> getEmail(
            Authentication auth,
            @PathVariable UUID id) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.getEmailById(userId, id));
    }

    @GetMapping("/filter")
    public ResponseEntity<Page<EmailDTO>> filterEmails(
            Authentication auth,
            @RequestParam Map<String, String> filters,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var userId = getUserId(auth);
        return ResponseEntity.ok(emailService.filterEmails(userId, filters, page, size));
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

        var user = userDetailsService.loadUserEntityById(userId);
        switch (actionType) {
            case "read" -> emailService.markAsRead(user, provider, emailId);
            case "unread" -> emailService.markAsUnread(user, provider, emailId);
            case "star" -> emailService.toggleStar(user, provider, emailId, true);
            case "unstar" -> emailService.toggleStar(user, provider, emailId, false);
            case "trash" -> emailService.moveToTrash(user, provider, emailId);
            default -> throw new IllegalArgumentException("Unknown action: " + actionType);
        }

        webSocketService.sendNotification(userId.toString(), "Email " + actionType + " completed");
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
        emailService.syncEmails(user, provider);
        return ResponseEntity.accepted().body(Map.of("status", "syncing"));
    }

    @GetMapping("/sync/status")
    public ResponseEntity<Map<String, Object>> syncStatus(Authentication auth) {
        var userId = getUserId(auth);
        var syncing = emailService.isSyncing(userId);
        return ResponseEntity.ok(Map.of("syncing", syncing));
    }

    private UUID getUserId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
