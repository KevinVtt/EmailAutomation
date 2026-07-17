package com.emailfilter.service;

import com.emailfilter.dto.EmailDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AIServiceClientTest {

    private AIServiceClient aiServiceClient;

    @BeforeEach
    void setUp() {
        aiServiceClient = new AIServiceClient();
        ReflectionTestUtils.setField(aiServiceClient, "aiServiceUrl", "http://localhost:8000");
    }

    @Test
    void analyzeEmails_returnsMap() {
        var email = EmailDTO.builder()
                .id(UUID.randomUUID())
                .subject("Test")
                .fromAddress("test@example.com")
                .bodyPreview("Test body")
                .build();

        assertDoesNotThrow(() -> aiServiceClient.analyzeEmails(List.of(email)));
    }

    @Test
    void chat_returnsMap() {
        assertDoesNotThrow(() -> aiServiceClient.chat("Show important emails", "conv-123"));
    }

    @Test
    void summarizeEmails_returnsString() {
        var email = EmailDTO.builder()
                .id(UUID.randomUUID())
                .subject("Test")
                .fromAddress("test@example.com")
                .bodyPreview("Test body")
                .build();

        assertDoesNotThrow(() -> aiServiceClient.summarizeEmails(List.of(email)));
    }

    @Test
    void summarizeEmails_emptyList_returnsEmptyString() {
        var result = assertDoesNotThrow(() -> aiServiceClient.summarizeEmails(List.of()));
        assertEquals("", result);
    }
}
