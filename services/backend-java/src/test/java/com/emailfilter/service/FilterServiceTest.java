package com.emailfilter.service;

import com.emailfilter.dto.EmailDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FilterServiceTest {

    @Mock
    private EmailService emailService;

    private FilterService filterService;

    @BeforeEach
    void setUp() {
        filterService = new FilterService(emailService);
    }

    @Test
    void applyAiFilters_delegatesToEmailService() {
        var userId = UUID.randomUUID();
        var criteria = Map.of("fromAddress", "boss@example.com");
        Page<EmailDTO> page = new PageImpl<>(List.<EmailDTO>of(), PageRequest.of(0, 20), 0);
        when(emailService.filterEmails(eq(userId), eq(criteria), eq(0), eq(20))).thenReturn(page);

        var result = filterService.applyAiFilters(userId, criteria, 0, 20);

        assertEquals(0, result.getTotalElements());
        verify(emailService).filterEmails(eq(userId), eq(criteria), eq(0), eq(20));
    }
}
