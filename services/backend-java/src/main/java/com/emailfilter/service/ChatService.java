package com.emailfilter.service;

import com.emailfilter.model.ChatConversation;
import com.emailfilter.model.ChatMessage;
import com.emailfilter.repository.ChatConversationRepository;
import com.emailfilter.repository.ChatMessageRepository;
import com.emailfilter.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Historial del asistente en PostgreSQL. Solo "Nueva conversación" crea una conversación nueva. */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int SUMMARY_MAX_CHARS = 800;
    private static final int SUMMARY_ITEM_CHARS = 120;

    @Value("${chat.context-window:10}")
    private int contextWindow;

    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final UserRepository users;

    /** Conversación pedida si es del usuario; si no, la más reciente; si no hay ninguna, crea una. */
    @Transactional
    public ChatConversation resolve(UUID userId, String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            try {
                Optional<ChatConversation> requested =
                        conversations.findByIdAndUserId(UUID.fromString(conversationId), userId);
                if (requested.isPresent()) return requested.get();
            } catch (IllegalArgumentException ignored) {
                // id no válido: se usa la más reciente
            }
        }
        return latest(userId).orElseGet(() -> create(userId));
    }

    @Transactional
    public ChatConversation createNew(UUID userId) {
        return create(userId);
    }

    @Transactional(readOnly = true)
    public Optional<ChatConversation> latest(UUID userId) {
        return conversations.findFirstByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(UUID conversationId) {
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** Resumen de lo viejo (si lo hay) + los últimos {@code chat.context-window} mensajes, para la IA. */
    @Transactional(readOnly = true)
    public List<Map<String, String>> context(ChatConversation conversation) {
        var recent = new ArrayList<>(messages.findByConversationIdOrderByCreatedAtDesc(
                conversation.getId(), PageRequest.of(0, contextWindow)));
        Collections.reverse(recent);
        var context = new ArrayList<Map<String, String>>();
        if (conversation.getSummary() != null) {
            context.add(Map.of("role", "system",
                    "content", "Resumen de la conversación anterior: " + conversation.getSummary()));
        }
        for (var message : recent) {
            context.add(Map.of("role", message.getRole(), "content", message.getContent()));
        }
        return context;
    }

    /** Guarda el turno y, si hay más mensajes que la ventana, recalcula el resumen de los viejos. */
    @Transactional
    public void saveTurn(UUID conversationId, String userText, String assistantText) {
        var conversation = conversations.findById(conversationId).orElseThrow();
        messages.save(ChatMessage.builder().conversation(conversation).role("user")
                .content(userText != null ? userText : "").build());
        messages.save(ChatMessage.builder().conversation(conversation).role("assistant")
                .content(assistantText != null ? assistantText : "").build());

        var all = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int old = all.size() - contextWindow;
        if (old > 0) {
            conversation.setSummary(summarize(all.subList(0, old)));
        }
        conversation.setUpdatedAt(Instant.now());
        conversations.save(conversation);
    }

    private ChatConversation create(UUID userId) {
        var conversation = ChatConversation.builder().user(users.getReferenceById(userId)).build();
        return conversations.save(conversation);
    }

    /** Resumen sin IA: una línea por mensaje viejo, truncada, quedándose con lo más reciente. */
    private static String summarize(List<ChatMessage> old) {
        var sb = new StringBuilder();
        for (var message : old) {
            var content = message.getContent();
            if (content.length() > SUMMARY_ITEM_CHARS) content = content.substring(0, SUMMARY_ITEM_CHARS) + "…";
            sb.append(message.getRole()).append(": ").append(content).append(" | ");
        }
        return sb.length() > SUMMARY_MAX_CHARS ? sb.substring(sb.length() - SUMMARY_MAX_CHARS) : sb.toString();
    }
}
