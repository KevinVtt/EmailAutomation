package com.emailfilter.controller;

import com.emailfilter.dto.ContactPatchDTO;
import com.emailfilter.dto.EmailContactDTO;
import com.emailfilter.service.ContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

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

    private UUID getUserId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
