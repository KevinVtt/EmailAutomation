package com.emailfilter.controller;

import com.emailfilter.dto.ChatRequest;
import com.emailfilter.service.AIServiceClient;
import com.emailfilter.service.FilterService;
import com.emailfilter.service.WebSocketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        long t2 = System.currentTimeMillis();
        var found = processChat(userId, aiResponse);
        long t3 = System.currentTimeMillis();
        log.info("Filtros aplicados en {} ms. emails encontrados: {}", (t3-t2), found);

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

        var found = processChat(userId, aiResponse);
        log.info("emails encontrados: {}", found);

        log.info("=== CHAT WEBSOCKET FIN === userId={}", userId);
    }

    /**
     * Trabajo compartido por REST y WebSocket: extrae criteria, filtra, explica
     * resultados vacíos y empuja filtered_emails + chat_response. Devuelve el total
     * de emails encontrados (los handlers lo registran con su propio logging).
     */
    private long processChat(UUID userId, Map<String, Object> aiResponse) {
        var criteria = extractCriteria(aiResponse);
        log.info("Criteria extraídos: {}", criteria);
        if (criteria.isEmpty()) {
            log.info("Enviando chat_response por WebSocket a usuario {}", userId);
            webSocketService.sendChatResponse(userId.toString(), Map.of(
                    "type", "chat_response",
                    "data", aiResponse
            ));
            return 0;
        }

        var size = extractSize(criteria);
        var emails = filterService.applyAiFilters(userId, criteria, 0, size);
        aiResponse.put("emails", emails.getContent());

        // If no emails found, enhance response with explanation
        if (emails.getTotalElements() == 0) {
            var explanation = buildNoResultsExplanation(criteria);
            aiResponse.put("response", aiResponse.get("response") + "\n\n" + explanation);
        }

        log.info("Enviando filtered_emails por WebSocket a usuario {}", userId);
        webSocketService.sendEmailsToUser(userId.toString(), Map.of(
                "type", "filtered_emails",
                "emails", emails.getContent()
        ));

        log.info("Enviando chat_response por WebSocket a usuario {}", userId);
        webSocketService.sendChatResponse(userId.toString(), Map.of(
                "type", "chat_response",
                "data", aiResponse
        ));
        return emails.getTotalElements();
    }

    private Map<String, String> extractCriteria(Map<String, Object> aiResponse) {
        var criteria = aiResponse.get("criteria");
        if (!(criteria instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        // The AI service may return non-string scalars (e.g. "size": 20, booleans).
        // Coerce every value to String so the Map<String, String> contract holds and
        // downstream parsing (Boolean.parseBoolean / Integer.parseInt) works.
        var result = new HashMap<String, String>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return result;
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

    private String buildNoResultsExplanation(Map<String, String> criteria) {
        var reasons = new java.util.ArrayList<String>();

        if (criteria.containsKey("fromAddress")) {
            reasons.add("no hay emails del remitente \"" + criteria.get("fromAddress") + "\"");
        }
        if (criteria.containsKey("subjectContains")) {
            reasons.add("no hay emails con asunto que contenga \"" + criteria.get("subjectContains") + "\"");
        }
        if (criteria.containsKey("bodyContains")) {
            reasons.add("no hay emails con contenido que contenga \"" + criteria.get("bodyContains") + "\"");
        }
        if (criteria.containsKey("label")) {
            reasons.add("no hay emails con la etiqueta \"" + criteria.get("label") + "\"");
        }
        if (criteria.containsKey("isRead")) {
            var val = Boolean.parseBoolean(criteria.get("isRead"));
            reasons.add(val ? "no hay emails leídos con estos criterios" : "no hay emails sin leer con estos criterios");
        }
        if (criteria.containsKey("isStarred")) {
            reasons.add("no hay emails destacados con estos criterios");
        }
        if (criteria.containsKey("dateFrom") || criteria.containsKey("dateTo")) {
            reasons.add("no hay emails en el rango de fechas indicado");
        }
        if (criteria.containsKey("important")) {
            reasons.add("no hay emails marcados como importantes");
        }

        if (reasons.isEmpty()) {
            return "No se encontraron emails que coincidan con tu búsqueda. Intentá con otros criterios.";
        }
        return "No se encontraron emails porque " + String.join(", ", reasons) + ".";
    }
}
