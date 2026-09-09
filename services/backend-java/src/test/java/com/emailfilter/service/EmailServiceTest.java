package com.emailfilter.service;

import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.OAuthToken;
import com.emailfilter.model.User;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.OAuthTokenRepository;
import com.emailfilter.repository.VistoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private EmailRepository emailRepository;
    @Mock
    private OAuthTokenRepository oauthTokenRepository;
    @Mock
    private VistoRepository vistoRepository;
    @Mock
    private GmailService gmailService;
    @Mock
    private OutlookService outlookService;
    @Mock
    private WebSocketService webSocketService;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(emailRepository, oauthTokenRepository, vistoRepository,
                gmailService, outlookService, webSocketService);
    }

    @Test
    void getEmails_50Emails_executesSingleVistoBatchQuery() {
        var userId = UUID.randomUUID();
        var emails = new ArrayList<EmailMessage>();
        for (int i = 0; i < 50; i++) {
            emails.add(EmailMessage.builder()
                    .id(UUID.randomUUID())
                    .provider("google")
                    .providerEmailId("email-" + i)
                    .subject("Subject " + i)
                    .user(User.builder().id(userId).build())
                    .build());
        }
        var page = new PageImpl<>(emails, PageRequest.of(0, 50), 50);

        when(emailRepository.findByUserIdOrderByReceivedAtDesc(eq(userId), any(Pageable.class))).thenReturn(page);
        when(vistoRepository.findVistoEmailIdsByUserIdAndProviderEmailIdIn(eq(userId), anyCollection()))
                .thenReturn(List.of("email-0", "email-25"));

        var result = emailService.getEmails(userId, 0, 50);

        assertEquals(50, result.getContent().size());
        assertTrue(result.getContent().get(0).isVisto());
        assertFalse(result.getContent().get(1).isVisto());
        assertTrue(result.getContent().get(25).isVisto());
        verify(vistoRepository, times(1)).findVistoEmailIdsByUserIdAndProviderEmailIdIn(eq(userId), anyCollection());
    }

    @Test
    void getEmails_emptyPage_executesZeroVistoQueries() {
        var userId = UUID.randomUUID();
        var page = new PageImpl<>(List.<EmailMessage>of(), PageRequest.of(0, 20), 0);

        when(emailRepository.findByUserIdOrderByReceivedAtDesc(eq(userId), any(Pageable.class))).thenReturn(page);

        var result = emailService.getEmails(userId, 0, 20);

        assertTrue(result.getContent().isEmpty());
        verify(vistoRepository, never()).findVistoEmailIdsByUserIdAndProviderEmailIdIn(any(), anyCollection());
    }

    @Test
    void syncEmailsAsync_500ExistingEmails_fetchesIdsInPagesNotAllAtOnce() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var lastEmail = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("last-email")
                .receivedAt(Instant.now())
                .user(user)
                .build();
        var existingIds = new ArrayList<String>();
        for (int i = 0; i < 500; i++) existingIds.add("existing-" + i);
        var page = new PageImpl<>(existingIds, PageRequest.of(0, 1000), 500);
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId)).thenReturn(Optional.of(lastEmail));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class))).thenReturn(page);
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.fetchIncrementalEmails(anyString(), any(Instant.class), anySet()))
                .thenReturn(List.of());

        emailService.syncEmailsAsync(user, "google").join();

        verify(emailRepository).findProviderEmailIdsByUserId(eq(userId), eq(PageRequest.of(0, 1000)));
        verify(gmailService).fetchIncrementalEmails(anyString(), any(Instant.class),
                argThat(ids -> ids.size() == 500 && ids.contains("existing-499")));
    }

    @Test
    void syncEmailsAsync_2500ExistingEmails_fetchesIdsInThreePages() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var lastEmail = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("last-email")
                .receivedAt(Instant.now())
                .user(user)
                .build();
        var page0 = new ArrayList<String>();
        var page1 = new ArrayList<String>();
        var page2 = new ArrayList<String>();
        for (int i = 0; i < 1000; i++) page0.add("existing-" + i);
        for (int i = 1000; i < 2000; i++) page1.add("existing-" + i);
        for (int i = 2000; i < 2500; i++) page2.add("existing-" + i);
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId)).thenReturn(Optional.of(lastEmail));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(page0, PageRequest.of(0, 1000), 2500),
                        new PageImpl<>(page1, PageRequest.of(1, 1000), 2500),
                        new PageImpl<>(page2, PageRequest.of(2, 1000), 2500));
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.fetchIncrementalEmails(anyString(), any(Instant.class), anySet()))
                .thenReturn(List.of());

        emailService.syncEmailsAsync(user, "google").join();

        verify(emailRepository, times(3)).findProviderEmailIdsByUserId(eq(userId), any(Pageable.class));
        verify(gmailService).fetchIncrementalEmails(anyString(), any(Instant.class),
                argThat(ids -> ids.size() == 2500 && ids.contains("existing-0") && ids.contains("existing-2499")));
    }

    @Test
    void syncEmailsAsync_firstSync_usesConfiguredMaxEmailsLimit() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        emailService.maxEmailsPerSync = 1000;
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId)).thenReturn(Optional.empty());
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.fetchAllEmails(anyString(), eq(100), eq(1000))).thenReturn(List.of());

        emailService.syncEmailsAsync(user, "google").join();

        verify(gmailService).fetchAllEmails(anyString(), eq(100), eq(1000));
    }
}