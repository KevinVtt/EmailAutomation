package com.emailfilter.controller;

import com.emailfilter.config.CorsConfig;
import com.emailfilter.config.SecurityConfig;
import com.emailfilter.dto.EmailDTO;
import com.emailfilter.security.JwtTokenProvider;
import com.emailfilter.security.UserDetailsServiceImpl;
import com.emailfilter.service.AIServiceClient;
import com.emailfilter.service.EmailService;
import com.emailfilter.service.WebSocketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmailController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class EmailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmailService emailService;
    @MockBean
    private WebSocketService webSocketService;
    @MockBean
    private UserDetailsServiceImpl userDetailsService;
    @MockBean
    private AIServiceClient aiServiceClient;
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void getEmails_returnsPage() throws Exception {
        var email = EmailDTO.builder()
                .id(UUID.randomUUID())
                .subject("Test Email")
                .fromAddress("test@example.com")
                .build();
        var page = new PageImpl<>(List.of(email));

        when(emailService.getEmails(any(), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void filterEmails_returnsFilteredPage() throws Exception {
        var email = EmailDTO.builder()
                .id(UUID.randomUUID())
                .subject("Important")
                .fromAddress("boss@example.com")
                .isRead(false)
                .build();
        var page = new PageImpl<>(List.of(email));

        when(emailService.filterEmails(any(), any(), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/emails/filter")
                        .param("isRead", "false")
                        .param("fromAddress", "boss@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void performAction_returnsOk() throws Exception {
        var body = """
                {
                    "provider": "google",
                    "emailId": "12345",
                    "action": "read"
                }
                """;

        mockMvc.perform(post("/api/emails/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
