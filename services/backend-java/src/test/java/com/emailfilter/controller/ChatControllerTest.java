package com.emailfilter.controller;

import com.emailfilter.dto.ChatRequest;
import com.emailfilter.dto.EmailDTO;
import com.emailfilter.service.AIServiceClient;
import com.emailfilter.service.FilterService;
import com.emailfilter.service.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private AIServiceClient aiServiceClient;
    @Mock
    private FilterService filterService;
    @Mock
    private WebSocketService webSocketService;

    private ChatController chatController;

    @BeforeEach
    void setUp() {
        chatController = new ChatController(aiServiceClient, filterService, webSocketService);
    }

    private ChatRequest request(String message, String conversationId) {
        var request = new ChatRequest();
        request.setMessage(message);
        request.setConversationId(conversationId);
        return request;
    }

    private Map<String, Object> aiResponseWithCriteria() {
        var aiResponse = new HashMap<String, Object>();
        aiResponse.put("response", "ok");
        aiResponse.put("criteria", Map.of("isRead", "false"));
        aiResponse.put("conversation_id", "c1");
        return aiResponse;
    }

    /**
     * El path WebSocket comparte el trabajo con REST y debe exponer `emails`
     * como LISTA (emails.getContent()), no como Page.
     */
    @Test
    void handleWebSocketChat_putsEmailsAsListAndPushesBothMessages() {
        var userId = UUID.randomUUID();
        var auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        var aiResponse = aiResponseWithCriteria();
        when(aiServiceClient.chat(eq("hola"), eq("c1"), any())).thenReturn(aiResponse);
        when(filterService.applyAiFilters(eq(userId), anyMap(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(EmailDTO.builder().providerEmailId("e1").build()),
                        PageRequest.of(0, 20), 1));

        chatController.handleWebSocketChat(auth, request("hola", "c1"));

        assertInstanceOf(List.class, aiResponse.get("emails"));
        assertEquals(1, ((List<?>) aiResponse.get("emails")).size());
        verify(webSocketService).sendEmailsToUser(eq(userId.toString()), any());
        verify(webSocketService).sendChatResponse(eq(userId.toString()), any());
        // El contrato del AI service no se muta.
        assertEquals("c1", aiResponse.get("conversation_id"));
        assertEquals("ok", aiResponse.get("response"));
    }

    @Test
    void handleWebSocketChat_aiFailure_sendsErrorSoTheSpinnerStops() {
        var userId = UUID.randomUUID();
        var auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        when(aiServiceClient.chat(eq("hola"), eq("c1"), any())).thenThrow(new RuntimeException("AI caída"));

        chatController.handleWebSocketChat(auth, request("hola", "c1"));

        verify(webSocketService).sendChatResponse(eq(userId.toString()), any());
    }

    @Test
    void chat_invalidDate_dropsOnlyThatCriterion() {
        var userId = UUID.randomUUID();
        var auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        var aiResponse = new HashMap<String, Object>();
        aiResponse.put("response", "ok");
        aiResponse.put("criteria", Map.of("dateFrom", "ayer", "label", "IMPORTANT"));
        when(aiServiceClient.chat(eq("hola"), eq("c1"), any())).thenReturn(aiResponse);
        when(filterService.applyAiFilters(eq(userId), anyMap(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        chatController.chat(auth, request("hola", "c1"));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(filterService).applyAiFilters(eq(userId), captor.capture(), eq(0), eq(20));
        assertEquals(Map.of("label", "IMPORTANT"), captor.getValue());
    }

    @Test
    void chat_putsEmailsAsListAndPushesBothMessages() {
        var userId = UUID.randomUUID();
        var auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        var aiResponse = aiResponseWithCriteria();
        when(aiServiceClient.chat(eq("hola"), eq("c1"), any())).thenReturn(aiResponse);
        when(filterService.applyAiFilters(eq(userId), anyMap(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(EmailDTO.builder().providerEmailId("e1").build()),
                        PageRequest.of(0, 20), 1));

        var response = chatController.chat(auth, request("hola", "c1"));

        assertEquals(200, response.getStatusCode().value());
        assertInstanceOf(List.class, response.getBody().get("emails"));
        verify(webSocketService).sendEmailsToUser(eq(userId.toString()), any());
        verify(webSocketService).sendChatResponse(eq(userId.toString()), any());
    }

    @Test
    void chat_withScope_addsTheScreenFilterOnTopOfTheModel() {
        var userId = UUID.randomUUID();
        var auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        var aiResponse = new HashMap<String, Object>();
        aiResponse.put("response", "ok");
        aiResponse.put("criteria", Map.of("bodyContains", "steam"));
        aiResponse.put("conversation_id", "c1");
        when(aiServiceClient.chat(eq("hola"), eq("c1"), any())).thenReturn(aiResponse);
        when(filterService.applyAiFilters(eq(userId), anyMap(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        var request = request("hola", "c1");
        request.setScope(Map.of(
                "folder", "spam",
                "criteria", Map.of("label", "SPAM", "destacados", true),
                "description", "Spam"));

        chatController.chat(auth, request);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(filterService).applyAiFilters(eq(userId), captor.capture(), eq(0), eq(20));
        var criteria = (Map<String, String>) captor.getValue();
        assertEquals("steam", criteria.get("bodyContains"));
        assertEquals("SPAM", criteria.get("label"));
        assertEquals("true", criteria.get("destacados"));
    }

    @Test
    void chat_withoutScope_leavesTheCriteriaAsTheyWere() {
        var userId = UUID.randomUUID();
        var auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        var aiResponse = aiResponseWithCriteria();
        when(aiServiceClient.chat(eq("hola"), eq("c1"), any())).thenReturn(aiResponse);
        when(filterService.applyAiFilters(eq(userId), anyMap(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        chatController.chat(auth, request("hola", "c1"));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(filterService).applyAiFilters(eq(userId), captor.capture(), eq(0), eq(20));
        assertEquals(Map.of("isRead", "false"), captor.getValue());
    }
}
