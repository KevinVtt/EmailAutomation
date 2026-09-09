package com.emailfilter.service;

import com.emailfilter.dto.EmailDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class AIServiceClientTest {

    private AIServiceClient aiServiceClient;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        aiServiceClient = new AIServiceClient();
        ReflectionTestUtils.setField(aiServiceClient, "aiServiceUrl", "http://localhost:8000");
        var restTemplate = (RestTemplate) ReflectionTestUtils.getField(aiServiceClient, "restTemplate");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void analyzeEmails_returnsMap() {
        var email = EmailDTO.builder()
                .id(UUID.randomUUID())
                .subject("Test")
                .fromAddress("test@example.com")
                .bodyPreview("Test body")
                .build();

        server.expect(once(), requestTo("http://localhost:8000/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"category": "work", "priority": 5}
                        """, MediaType.APPLICATION_JSON));

        var result = aiServiceClient.analyzeEmails(List.of(email));

        assertEquals("work", result.get("category"));
        assertEquals(5, result.get("priority"));
        server.verify();
    }

    @Test
    void chat_returnsMap() {
        server.expect(once(), requestTo("http://localhost:8000/chat"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"response": "ok", "criteria": {"isRead": false}}
                        """, MediaType.APPLICATION_JSON));

        var result = aiServiceClient.chat("Show important emails", "conv-123");

        assertEquals("ok", result.get("response"));
        assertNotNull(result.get("criteria"));
        server.verify();
    }

    @Test
    void summarizeEmails_returnsString() {
        var email = EmailDTO.builder()
                .id(UUID.randomUUID())
                .subject("Test")
                .fromAddress("test@example.com")
                .bodyPreview("Test body")
                .build();

        server.expect(once(), requestTo("http://localhost:8000/chat/summarize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"summary": "Resumen de test"}
                        """, MediaType.APPLICATION_JSON));

        var result = aiServiceClient.summarizeEmails(List.of(email));

        assertEquals("Resumen de test", result);
        server.verify();
    }

    @Test
    void summarizeEmails_emptyList_returnsEmptyString() {
        var result = aiServiceClient.summarizeEmails(List.of());

        assertEquals("No hay correos para resumir.", result);
        server.verify();
    }
}