package com.emailfilter.service;

import com.emailfilter.model.ChatConversation;
import com.emailfilter.model.ChatMessage;
import com.emailfilter.repository.ChatConversationRepository;
import com.emailfilter.repository.ChatMessageRepository;
import com.emailfilter.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatConversationRepository conversations;
    @Mock
    private ChatMessageRepository messages;
    @Mock
    private UserRepository users;

    private ChatService chatService;
    private ChatConversation conversation;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(conversations, messages, users);
        ReflectionTestUtils.setField(chatService, "contextWindow", 10);
        conversation = ChatConversation.builder().id(UUID.randomUUID()).build();
    }

    private ChatMessage msg(String role, String content) {
        return ChatMessage.builder().conversation(conversation).role(role).content(content).build();
    }

    @Test
    void context_sendsSummaryThenOnlyTheLastMessagesInOrder() {
        conversation.setSummary("resumen previo");
        var recentNewestFirst = new ArrayList<>(List.of(msg("assistant", "respuesta 2"), msg("user", "pregunta 2")));
        when(messages.findByConversationIdOrderByCreatedAtDesc(eq(conversation.getId()), any(PageRequest.class)))
                .thenReturn(recentNewestFirst);

        var context = chatService.context(conversation);

        assertEquals(List.of(
                Map.of("role", "system", "content", "Resumen de la conversación anterior: resumen previo"),
                Map.of("role", "user", "content", "pregunta 2"),
                Map.of("role", "assistant", "content", "respuesta 2")), context);
    }

    @Test
    void saveTurn_summarizesOnlyMessagesOlderThanTheWindow() {
        var old = new ArrayList<ChatMessage>();
        for (int i = 1; i <= 10; i++) old.add(msg(i % 2 == 1 ? "user" : "assistant", "viejo " + i));
        var all = new ArrayList<>(old);
        all.add(msg("user", "nuevo user"));
        all.add(msg("assistant", "nuevo assistant"));
        when(conversations.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId())).thenReturn(all);

        chatService.saveTurn(conversation.getId(), "nuevo user", "nuevo assistant");

        var summary = conversation.getSummary();
        assertTrue(summary.startsWith("user: viejo 1 |"), summary);
        assertTrue(summary.contains("assistant: viejo 2 |"), summary);
        assertTrue(!summary.contains("nuevo"), summary);
    }

    @Test
    void saveTurn_keepsNoSummaryWhileTheConversationFitsTheWindow() {
        when(conversations.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()))
                .thenReturn(List.of(msg("user", "hola"), msg("assistant", "chau")));

        chatService.saveTurn(conversation.getId(), "hola", "chau");

        assertEquals(null, conversation.getSummary());
    }
}
