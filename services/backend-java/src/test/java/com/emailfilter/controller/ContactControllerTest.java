package com.emailfilter.controller;

import com.emailfilter.config.CorsConfig;
import com.emailfilter.config.SecurityConfig;
import com.emailfilter.dto.ContactPatchDTO;
import com.emailfilter.dto.EmailContactDTO;
import com.emailfilter.security.JwtTokenProvider;
import com.emailfilter.security.UserDetailsServiceImpl;
import com.emailfilter.service.AIServiceClient;
import com.emailfilter.service.ContactService;
import com.emailfilter.model.EmailContact;
import com.emailfilter.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class ContactControllerTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContactService contactService;
    @MockBean
    private AIServiceClient aiServiceClient;
    @MockBean
    private UserDetailsServiceImpl userDetailsService;
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @WithMockUser(username = USER_ID)
    void list_returnsTheContactsInTheOrderTheServiceGives() throws Exception {
        var contact = EmailContactDTO.builder()
                .id(UUID.randomUUID())
                .address("juan@x.com")
                .displayName("Juan")
                .sentCount(10)
                .receivedCount(5)
                .build();
        when(contactService.list(UUID.fromString(USER_ID), 20)).thenReturn(List.of(contact));

        mockMvc.perform(get("/api/contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].address").value("juan@x.com"))
                .andExpect(jsonPath("$[0].sentCount").value(10));
    }

    @Test
    @WithMockUser(username = USER_ID)
    void patch_ofAnotherUsersContact_returns404() throws Exception {
        var id = UUID.randomUUID();
        when(contactService.update(eq(UUID.fromString(USER_ID)), eq(id), any(ContactPatchDTO.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(patch("/api/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"Acme\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void patch_withACompanyLongerThanTheColumn_returns400() throws Exception {
        var body = "{\"company\":\"" + "x".repeat(300) + "\"}";

        mockMvc.perform(patch("/api/contacts/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void draft_usesTheContactDataAndTheAuthenticatedName() throws Exception {
        var userId = UUID.fromString(USER_ID);
        var id = UUID.randomUUID();
        var contact = EmailContact.builder()
                .id(id)
                .address("juan@x.com")
                .displayName("Juan")
                .company("Acme")
                .build();
        when(contactService.findOwned(userId, id)).thenReturn(contact);
        when(contactService.recentSubjects(userId, "juan@x.com")).thenReturn(List.of("Factura 1"));
        when(userDetailsService.loadUserEntityById(userId))
                .thenReturn(User.builder().id(userId).name("Lucía Fernández").build());
        when(aiServiceClient.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(java.util.Map.of("subject", "Factura", "body", "Hola"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/contacts/{id}/draft", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"mandale la factura\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Factura"));

        org.mockito.Mockito.verify(aiServiceClient).compose(
                eq("mandale la factura"), eq("Juan"), eq("Acme"), any(), any(), any(),
                any(), any(), any(), eq("Lucía Fernández"));
    }
}
