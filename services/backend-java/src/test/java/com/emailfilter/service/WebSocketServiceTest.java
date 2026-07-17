package com.emailfilter.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSocketServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private WebSocketService webSocketService;

    @BeforeEach
    void setUp() {
        webSocketService = new WebSocketService(messagingTemplate);
    }

    @Test
    void sendEmailsToUser_sendsToCorrectQueue() {
        var payload = Map.of("type", "emails", "data", "test");
        webSocketService.sendEmailsToUser("user-1", payload);

        verify(messagingTemplate).convertAndSendToUser("user-1", "/queue/emails", payload);
    }

    @Test
    void sendChatResponse_sendsToCorrectQueue() {
        var payload = Map.of("type", "chat", "data", "response");
        webSocketService.sendChatResponse("user-1", payload);

        verify(messagingTemplate).convertAndSendToUser("user-1", "/queue/chat", payload);
    }

    @Test
    void broadcastToTopic_sendsToCorrectTopic() {
        var payload = Map.of("type", "broadcast");
        webSocketService.broadcastToTopic("alerts", payload);

        verify(messagingTemplate).convertAndSend("/topic/alerts", payload);
    }

    @Test
    void sendNotification_sendsNotificationFormat() {
        webSocketService.sendNotification("user-1", "Test notification");

        verify(messagingTemplate).convertAndSendToUser(eq("user-1"), eq("/queue/notifications"), any());
    }
}
