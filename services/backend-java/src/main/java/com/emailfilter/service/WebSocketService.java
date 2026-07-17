package com.emailfilter.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    public void sendEmailsToUser(String userId, Object payload) {
        messagingTemplate.convertAndSendToUser(userId, "/queue/emails", payload);
    }

    public void sendChatResponse(String userId, Object payload) {
        messagingTemplate.convertAndSendToUser(userId, "/queue/chat", payload);
    }

    public void broadcastToTopic(String topic, Object payload) {
        messagingTemplate.convertAndSend("/topic/" + topic, payload);
    }

    public void sendNotification(String userId, String message) {
        messagingTemplate.convertAndSendToUser(userId, "/queue/notifications",
                Map.of("type", "notification", "message", message));
    }
}
