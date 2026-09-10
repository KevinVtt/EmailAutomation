package com.emailfilter.service;

import com.emailfilter.dto.EmailDTO;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Contract test between the Java backend and the Python AI service.
 *
 * Proves the backend serializes EmailDTO as camelCase in the HTTP request body
 * (regression guard for the camelCase/snake_case bug: the Python EmailItem
 * accepts both formats now, but the Java side MUST keep sending camelCase).
 *
 * Uses a REAL WireMock HTTP stub server (not MockRestServiceServer) so the
 * captured request body is byte-for-byte what would hit the AI service.
 */
class AIServiceClientContractTest {

    private static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(0); // random free port
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void analyzeEnviaPayloadCamelCaseEsperadoPorLaIA() {
        stubFor(post(urlEqualTo("/analyze"))
                .willReturn(okJson("""
                        {"classifications":[{"id":"msg-1","urgency":"high","category":"factura"}]}
                        """)));

        AIServiceClient client = newClient();

        Map<String, Object> result = client.analyzeEmails(List.of(sampleEmailDto()));

        // The request body is {"emails":[{...}]} — assert the camelCase field
        // names survive Jackson serialization. Any snake_case rename breaks this.
        verify(postRequestedFor(urlEqualTo("/analyze"))
                .withRequestBody(matchingJsonPath("$.emails[0].fromAddress"))
                .withRequestBody(matchingJsonPath("$.emails[0].bodyPreview"))
                .withRequestBody(matchingJsonPath("$.emails[0].providerEmailId"))
                .withRequestBody(matchingJsonPath("$.emails[0].receivedAt")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classifications = (List<Map<String, Object>>) result.get("classifications");
        assertNotNull(classifications);
        assertEquals("high", classifications.get(0).get("urgency"));
        assertEquals("msg-1", classifications.get(0).get("id"));
    }

    @Test
    void summarizeEnviaPayloadCamelCaseEsperadoPorLaIA() {
        stubFor(post(urlEqualTo("/chat/summarize"))
                .willReturn(okJson("""
                        {"summary":"Resumen de contrato"}
                        """)));

        AIServiceClient client = newClient();

        String summary = client.summarizeEmails(List.of(sampleEmailDto()));

        verify(postRequestedFor(urlEqualTo("/chat/summarize"))
                .withRequestBody(matchingJsonPath("$.emails[0].fromAddress"))
                .withRequestBody(matchingJsonPath("$.emails[0].bodyPreview"))
                .withRequestBody(matchingJsonPath("$.emails[0].providerEmailId"))
                .withRequestBody(matchingJsonPath("$.emails[0].receivedAt")));

        assertEquals("Resumen de contrato", summary);
    }

    private AIServiceClient newClient() {
        AIServiceClient client = new AIServiceClient();
        ReflectionTestUtils.setField(client, "aiServiceUrl", "http://localhost:" + wireMockServer.port());
        return client;
    }

    private EmailDTO sampleEmailDto() {
        return EmailDTO.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("msg-1")
                .threadId("thread-1")
                .fromAddress("billing@corp.com")
                .fromName("Billing Corp")
                .toAddresses("user@example.com")
                .subject("Factura vencida")
                .bodyPreview("Tu factura venció ayer. Paga antes del viernes.")
                .bodyHtml("<p>Tu factura venció ayer.</p>")
                .isRead(false)
                .isStarred(false)
                .visto(false)
                .labels("CATEGORY_PROMOTIONS")
                .receivedAt(Instant.parse("2026-09-09T10:00:00Z"))
                .fetchedAt(Instant.parse("2026-09-09T10:05:00Z"))
                .build();
    }
}