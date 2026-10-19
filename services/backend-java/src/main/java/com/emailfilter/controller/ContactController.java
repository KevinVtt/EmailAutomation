package com.emailfilter.controller;

import com.emailfilter.dto.ContactDraftRequest;
import com.emailfilter.dto.ContactPatchDTO;
import com.emailfilter.dto.EmailContactDTO;
import com.emailfilter.security.UserDetailsServiceImpl;
import com.emailfilter.service.AIServiceClient;
import com.emailfilter.service.ContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;
    private final AIServiceClient aiServiceClient;
    private final UserDetailsServiceImpl userDetailsService;

    @GetMapping
    public ResponseEntity<List<EmailContactDTO>> list(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(contactService.list(getUserId(auth), limit));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<EmailContactDTO> update(
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody ContactPatchDTO patch) {
        return ResponseEntity.ok(contactService.update(getUserId(auth), id, patch));
    }

    @PostMapping("/{id}/draft")
    public ResponseEntity<Map<String, Object>> draft(
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody ContactDraftRequest request) {
        var userId = getUserId(auth);
        var user = userDetailsService.loadUserEntityById(userId);
        var contact = contactService.findOwned(userId, id);

        var draft = aiServiceClient.compose(
                request.getInstruction(),
                contact.getDisplayName(),
                contact.getCompany(),
                contact.getTopic(),
                contact.getNotes(),
                contactService.recentSubjects(userId, contact.getAddress()),
                "",
                request.getTone(),
                request.getLanguage(),
                user.getName());

        return ResponseEntity.ok(Map.of(
                "subject", draft != null ? String.valueOf(draft.getOrDefault("subject", "")) : "",
                "body", draft != null ? String.valueOf(draft.getOrDefault("body", "")) : ""));
    }

    @PostMapping("/{id}/enrich")
    public ResponseEntity<EmailContactDTO> enrich(
            Authentication auth,
            @PathVariable UUID id) {
        var userId = getUserId(auth);
        var contact = contactService.findOwned(userId, id);

        var profile = aiServiceClient.contactProfile(
                contact.getAddress(),
                contact.getDisplayName(),
                contactService.recentSubjects(userId, contact.getAddress()));

        return ResponseEntity.ok(contactService.applyProfile(contact, profile != null ? profile : Map.of()));
    }

    private UUID getUserId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
