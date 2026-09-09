package com.emailfilter.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class OutlookServiceTest {

    private static final String TOKEN_URI = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token";

    private OutlookService outlookService;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        outlookService = new OutlookService();
        ReflectionTestUtils.setField(outlookService, "tokenUri", TOKEN_URI);
        var restTemplate = (RestTemplate) ReflectionTestUtils.getField(outlookService, "restTemplate");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void refreshAccessToken_sendsFormUrlEncodedBodyAndReturnsAccessToken() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)))
                .andExpect(content().string(containsString("client_id=client-123")))
                .andExpect(content().string(containsString("client_secret=secret-456")))
                .andExpect(content().string(containsString("refresh_token=refresh-token-abc")))
                .andExpect(content().string(containsString("grant_type=refresh_token")))
                .andExpect(content().string(containsString("scope=Mail.Read+Mail.ReadWrite+offline_access")))
                .andRespond(withSuccess("""
                        {"access_token": "new-access-token", "expires_in": 3600}
                        """, MediaType.APPLICATION_JSON));

        var accessToken = outlookService.refreshAccessToken(
                "refresh-token-abc", "client-123", "secret-456", "tenant-id");

        assertEquals("new-access-token", accessToken);
        server.verify();
    }

    @Test
    void refreshAccessToken_invalidToken_propagatesError() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("""
                                {"error": "invalid_grant", "error_description": "The provided value for the input parameter 'refresh_token' is not valid."}
                                """)
                        .contentType(MediaType.APPLICATION_JSON));

        var exception = assertThrows(HttpClientErrorException.class, () ->
                outlookService.refreshAccessToken("bad-token", "client-123", "secret-456", "tenant-id"));

        assertTrue(exception.getMessage().contains("400"));
        server.verify();
    }
}