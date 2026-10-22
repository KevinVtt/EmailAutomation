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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        log.info("=== CHAT REST INICIO === userId={}, message_len={}, conversationId={}",
                userId, request.getMessage() != null ? request.getMessage().length() : 0, request.getConversationId());

        long t0 = System.currentTimeMillis();
        var aiResponse = aiServiceClient.chat(request.getMessage(), request.getConversationId(), request.getScope());
        long t1 = System.currentTimeMillis();
        log.info("AI service respondió en {} ms (response_len={})", (t1-t0),
                aiResponse.get("response") instanceof String s ? s.length() : 0);

        long t2 = System.currentTimeMillis();
        var found = processChat(userId, aiResponse, request.getScope());
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
        log.info("=== CHAT WEBSOCKET INICIO === userId={}, message_len={}, conversationId={}",
                userId, request.getMessage() != null ? request.getMessage().length() : 0, request.getConversationId());

        try {
            var aiResponse = aiServiceClient.chat(request.getMessage(), request.getConversationId(), request.getScope());
            log.info("AI service respondió (WebSocket, response_len={})",
                    aiResponse.get("response") instanceof String s ? s.length() : 0);

            var found = processChat(userId, aiResponse, request.getScope());
            log.info("emails encontrados: {}", found);

            log.info("=== CHAT WEBSOCKET FIN === userId={}", userId);
        } catch (Exception e) {
            // Sin esta respuesta la UI se queda con el spinner para siempre.
            log.error("Chat WebSocket falló para userId={}", userId, e);
            webSocketService.sendChatResponse(userId.toString(), Map.of(
                    "type", "chat_response",
                    "data", Map.of(
                            "response", "No pude procesar tu mensaje. Probá de nuevo en unos segundos.",
                            "criteria", Map.of(),
                            "conversation_id", Objects.toString(request.getConversationId(), ""))));
        }
    }

    /**
     * Trabajo compartido por REST y WebSocket: extrae criteria, filtra, explica
     * resultados vacíos y empuja filtered_emails + chat_response. Devuelve el total
     * de emails encontrados (los handlers lo registran con su propio logging).
     */
    private long processChat(UUID userId, Map<String, Object> aiResponse, Map<String, Object> scope) {
        var criteria = extractCriteria(aiResponse);
        applyScope(criteria, scope);
        dropInvalidDates(criteria);
        log.info("Criteria extraídos: {}", criteria.keySet());
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
            return new HashMap<>();
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

    /**
     * La lista que el usuario está viendo se aplica al final: gana sobre el modelo.
     * Los valores se normalizan a String porque el filtro los parsea como texto
     * (un booleano JSON llega acá como Boolean).
     */
    private void applyScope(Map<String, String> criteria, Map<String, Object> scope) {
        if (scope == null) return;
        if (!(scope.get("criteria") instanceof Map<?, ?> scoped)) return;
        for (var entry : scoped.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            criteria.put(entry.getKey().toString(), String.valueOf(entry.getValue()));
        }
    }

    /** Una fecha que no es ISO no tira la búsqueda: se descarta ese criterio y quedan los demás. */
    private void dropInvalidDates(Map<String, String> criteria) {
        for (var key : List.of("dateFrom", "dateTo")) {
            var value = criteria.get(key);
            if (value == null) continue;
            try {
                Instant.parse(value);
            } catch (DateTimeParseException e) {
                log.warn("Criterio {} inválido, se descarta: {}", key, value);
                criteria.remove(key);
            }
        }
    }

    private int extractSize(Map<String, String> criteria) {
        var sizeStr = criteria.get("size");
        if (sizeStr != null) {
            try {
                int parsed = Integer.parseInt(sizeStr);
                return Math.max(1, Math.min(parsed, 100));
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
