package com.emailfilter.controller;

import com.emailfilter.dto.ChatRequest;
import com.emailfilter.dto.EmailDTO;
import com.emailfilter.security.UserDetailsServiceImpl;
import com.emailfilter.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AIServiceClient aiServiceClient;
    private final FilterService filterService;
    private final WebSocketService webSocketService;
    private final EmailService emailService;
    private final UserDetailsServiceImpl userDetailsService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(
            Authentication auth,
            @Valid @RequestBody ChatRequest request) {
        var userId = getUserId(auth);
        log.info("=== CHAT REST INICIO === userId={}, message={}, conversationId={}", userId, request.getMessage(), request.getConversationId());

        long t0 = System.currentTimeMillis();
        var aiResponse = aiServiceClient.chat(request.getMessage(), request.getConversationId());
        long t1 = System.currentTimeMillis();
        log.info("AI service respondió en {} ms. response={}", (t1-t0), aiResponse.get("response"));

        var criteria = extractCriteria(aiResponse);
        log.info("Criteria extraídos: {}", criteria);
        if (!criteria.isEmpty()) {
            long t2 = System.currentTimeMillis();
            var size = extractSize(criteria);
            var emails = filterService.applyAiFilters(userId, criteria, 0, size);
            long t3 = System.currentTimeMillis();
            log.info("Filtros aplicados en {} ms. emails encontrados: {}", (t3-t2), emails.getTotalElements());
            aiResponse.put("emails", emails);

            log.info("Enviando filtered_emails por WebSocket a usuario {}", userId);
            webSocketService.sendEmailsToUser(userId.toString(), Map.of(
                    "type", "filtered_emails",
                    "emails", emails.getContent()
            ));
        }

        log.info("Enviando chat_response por WebSocket a usuario {}", userId);
        webSocketService.sendChatResponse(userId.toString(), Map.of(
                "type", "chat_response",
                "data", aiResponse
        ));

        log.info("=== CHAT REST FIN === userId={}", userId);
        return ResponseEntity.ok(aiResponse);
    }

    @MessageMapping("/chat.send")
    public void handleWebSocketChat(
            Authentication auth,
            @Payload ChatRequest request) {
        var userId = getUserId(auth);
        log.info("=== CHAT WEBSOCKET INICIO === userId={}, message={}, conversationId={}", userId, request.getMessage(), request.getConversationId());

        var aiResponse = aiServiceClient.chat(request.getMessage(), request.getConversationId());
        log.info("AI service respondió (WebSocket): {}", aiResponse.get("response"));

        var criteria = extractCriteria(aiResponse);
        log.info("Criteria extraídos (WebSocket): {}", criteria);
        if (!criteria.isEmpty()) {
            var size = extractSize(criteria);
            var emails = filterService.applyAiFilters(userId, criteria, 0, size);
            log.info("emails encontrados: {}", emails.getTotalElements());
            aiResponse.put("emails", emails.getContent());

            webSocketService.sendEmailsToUser(userId.toString(), Map.of(
                    "type", "filtered_emails",
                    "emails", emails.getContent()
            ));
        }

        webSocketService.sendChatResponse(userId.toString(), Map.of(
                "type", "chat_response",
                "data", aiResponse
        ));
        log.info("=== CHAT WEBSOCKET FIN === userId={}", userId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractCriteria(Map<String, Object> aiResponse) {
        var criteria = aiResponse.get("criteria");
        if (criteria instanceof Map) {
            try {
                return (Map<String, String>) criteria;
            } catch (ClassCastException e) {
                log.warn("Failed to cast criteria to Map<String, String>: {}", e.getMessage());
                return Map.of();
            }
        }
        return Map.of();
    }

    private int extractSize(Map<String, String> criteria) {
        var sizeStr = criteria.get("size");
        if (sizeStr != null) {
            try {
                return Integer.parseInt(sizeStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid size in criteria: {}", sizeStr);
            }
        }
        return 20;
    }

    private UUID getUserId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
