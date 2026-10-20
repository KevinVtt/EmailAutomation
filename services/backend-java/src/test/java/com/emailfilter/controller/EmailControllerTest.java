package com.emailfilter.controller;

import com.emailfilter.config.CorsConfig;
import com.emailfilter.config.SecurityConfig;
import com.emailfilter.dto.EmailDTO;
import com.emailfilter.dto.PrioritySummaryDTO;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(emailService.ownsEmail(any(), eq("12345"))).thenReturn(true);
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

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void performAction_emailOfAnotherUser_returns404AndDoesNotTouchProvider() throws Exception {
        when(emailService.ownsEmail(any(), eq("ajeno"))).thenReturn(false);

        mockMvc.perform(post("/api/emails/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"google","emailId":"ajeno","action":"trash"}"""))
                .andExpect(status().isNotFound());

        verify(emailService, never()).moveToTrash(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void getEmails_clampsPageAndSize() throws Exception {
        when(emailService.getEmails(any(), eq(0), eq(100)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/emails").param("page", "-1").param("size", "9999"))
                .andExpect(status().isOk());

        verify(emailService).getEmails(any(), eq(0), eq(100));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void getEmail_missing_returns404() throws Exception {
        when(emailService.getEmailById(any(), any()))
                .thenThrow(new com.emailfilter.exception.ResourceNotFoundException("nope"));

        mockMvc.perform(get("/api/emails/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void getDailySummary_returnsSummary() throws Exception {
        var summary = Map.<String, Object>of(
                "summary", "Resumen del día: 3 correos importantes.",
                "emailCount", 3,
                "date", "2026-09-09"
        );
        when(emailService.getDailySummary(any(), eq("today"))).thenReturn(summary);

        mockMvc.perform(get("/api/emails/summary")
                        .param("date", "today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Resumen del día: 3 correos importantes."))
                .andExpect(jsonPath("$.emailCount").value(3))
                .andExpect(jsonPath("$.date").value("2026-09-09"));
    }

    @Test
    void errorEndpoint_unauthenticated_returns500Not403() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr("jakarta.servlet.error.status_code", 500)
                        .requestAttr("jakarta.servlet.error.message", "AI service failed"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void getDailySummary_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get("/api/emails/summary")
                        .param("date", "today"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void getPrioritySummary_returnsDto() throws Exception {
        var summary = new PrioritySummaryDTO(3, 12, 5);
        when(emailService.buildPrioritySummary(any())).thenReturn(summary);

        mockMvc.perform(get("/api/emails/priority-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urgentes").value(3))
                .andExpect(jsonPath("$.destacadosSinLeer").value(12))
                .andExpect(jsonPath("$.pendientesRespuesta").value(5));
    }

    @Test
    void getPrioritySummary_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get("/api/emails/priority-summary"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void sendEmail_returnsOk() throws Exception {
        when(emailService.sendReply(any(), eq("google"), eq("12345"), anyString()))
                .thenReturn(Map.of("status", "sent", "messageId", "msg-1"));

        mockMvc.perform(post("/api/emails/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider": "google", "emailId": "12345", "body": "Hola"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"))
                .andExpect(jsonPath("$.messageId").value("msg-1"));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void sendEmail_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/emails/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider": "google", "emailId": "12345"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void syncEndpoints_returnAcceptedAndDelegateToAsync() throws Exception {
        mockMvc.perform(post("/api/emails/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider": "google"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("syncing"));

        mockMvc.perform(post("/api/emails/sync/full")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider": "google"}
                                """))
                .andExpect(status().isAccepted());

        // Debe delegar en los métodos @Async: si el controller llamara a un wrapper
        // propio, la auto-invocación saltearía el proxy de Spring y la sync correría
        // sincrónica en el hilo del request (el 202 no volvía hasta terminar).
        verify(emailService).syncEmailsAsync(any(), eq("google"));
        verify(emailService).syncAllEmailsAsync(any(), eq("google"));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void rewriteEmail_injectsAuthenticatedUserName() throws Exception {
        when(userDetailsService.loadUserEntityById(any()))
                .thenReturn(com.emailfilter.model.User.builder().name("Juan Pérez").build());
        when(aiServiceClient.rewrite(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), eq("Juan Pérez")))
                .thenReturn(Map.of("rewritten", "ok", "original", "x"));

        mockMvc.perform(post("/api/emails/rewrite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft":"hola","tone":"formal","language":"auto"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewritten").value("ok"));

        verify(aiServiceClient).rewrite(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), eq("Juan Pérez"));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void rewriteEmail_rejectsBlankDraft() throws Exception {
        mockMvc.perform(post("/api/emails/rewrite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft":"   ","tone":"formal","language":"auto"}"""))
                .andExpect(status().isBadRequest());

        verify(aiServiceClient, never()).rewrite(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void compose_rejectsAnAddressWithHeaderInjection() throws Exception {
        mockMvc.perform(post("/api/emails/compose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":"a@x.com\\r\\nBcc: evil@x.com","subject":"Hola","body":"Cuerpo"}"""))
                .andExpect(status().isBadRequest());

        verify(emailService, never()).composeAndSend(any(), any());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void compose_rejectsSomethingThatIsNotAnAddress() throws Exception {
        mockMvc.perform(post("/api/emails/compose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":"no-es-un-mail","subject":"Hola","body":"Cuerpo"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void compose_rejectsAnEmptySubjectOrBody() throws Exception {
        mockMvc.perform(post("/api/emails/compose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":"a@x.com","subject":"","body":"Cuerpo"}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/emails/compose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":"a@x.com","subject":"Hola","body":""}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void compose_sendsWhatTheUserApproved() throws Exception {
        when(emailService.composeAndSend(any(), any()))
                .thenReturn(Map.of("status", "sent", "messageId", "m1"));

        mockMvc.perform(post("/api/emails/compose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":"a@x.com","subject":"Asunto","body":"Cuerpo"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void bulkAction_reportsHowManyItApplied() throws Exception {
        when(emailService.bulkAction(any(), any()))
                .thenReturn(Map.of("applied", 3, "failed", List.of()));

        mockMvc.perform(post("/api/emails/bulk-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"google","emailIds":["m1","m2","m3"],"action":"star"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(3));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void bulkAction_rejectsMoreThan100Ids() throws Exception {
        var ids = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            ids.append(i == 0 ? "" : ",").append("\"m").append(i).append("\"");
        }

        mockMvc.perform(post("/api/emails/bulk-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"google\",\"emailIds\":[" + ids + "],\"action\":\"star\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    void bulkAction_rejectsAnUnknownAction() throws Exception {
        mockMvc.perform(post("/api/emails/bulk-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"google","emailIds":["m1"],"action":"papelera"}"""))
                .andExpect(status().isBadRequest());
    }
}
