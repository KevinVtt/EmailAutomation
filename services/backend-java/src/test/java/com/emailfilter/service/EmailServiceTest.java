package com.emailfilter.service;

import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.OAuthToken;
import com.emailfilter.model.User;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.OAuthTokenRepository;
import com.emailfilter.repository.VistoRepository;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
    @Mock
    private Gmail gmail;

    private EmailService emailService;
    private Gmail.Users.Messages.List listRequest;

    @BeforeEach
    void setUp() throws Exception {
        emailService = new TestableEmailService(emailRepository, oauthTokenRepository, vistoRepository,
                gmailService, outlookService, webSocketService, gmail);
        listRequest = mock(Gmail.Users.Messages.List.class);
        var users = mock(Gmail.Users.class);
        var messages = mock(Gmail.Users.Messages.class);
        lenient().when(gmail.users()).thenReturn(users);
        lenient().when(users.messages()).thenReturn(messages);
        lenient().when(messages.list("me")).thenReturn(listRequest);
        lenient().when(listRequest.setQ(anyString())).thenReturn(listRequest);
        lenient().when(listRequest.setMaxResults(anyLong())).thenReturn(listRequest);
        lenient().when(listRequest.setPageToken(anyString())).thenReturn(listRequest);
        lenient().when(listRequest.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of()).setNextPageToken(null));
    }

    private static class TestableEmailService extends EmailService {
        private final Gmail gmail;

        TestableEmailService(EmailRepository emailRepository, OAuthTokenRepository oauthTokenRepository,
                             VistoRepository vistoRepository, GmailService gmailService,
                             OutlookService outlookService, WebSocketService webSocketService, Gmail gmail) {
            super(emailRepository, oauthTokenRepository, vistoRepository, gmailService, outlookService, webSocketService);
            this.gmail = gmail;
        }

        @Override
        protected Gmail buildGmailClient(String accessToken) {
            return gmail;
        }
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

    @Test
    void saveEmails_existingEmail_updatesStarredAndLabelsInsteadOfSkipping() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var existing = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("email-1")
                .isStarred(false)
                .labels("INBOX")
                .subject("Old subject")
                .user(user)
                .build();
        var incoming = EmailMessage.builder()
                .providerEmailId("email-1")
                .isStarred(true)
                .labels("INBOX,STARRED")
                .subject("New subject")
                .isRead(true)
                .build();

        when(emailRepository.findByProviderEmailIdAndUserId("email-1", userId)).thenReturn(Optional.of(existing));

        var count = emailService.saveEmails(List.of(incoming), user, "google");

        assertEquals(0, count);
        assertTrue(existing.isStarred());
        assertTrue(existing.isRead());
        assertEquals("INBOX,STARRED", existing.getLabels());
        assertEquals("New subject", existing.getSubject());
        verify(emailRepository).save(existing);
        verify(emailRepository, never()).saveAll(anyCollection());
    }

    @Test
    void saveEmails_newEmail_inserts() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var incoming = EmailMessage.builder()
                .providerEmailId("new-1")
                .subject("New")
                .build();

        when(emailRepository.findByProviderEmailIdAndUserId("new-1", userId)).thenReturn(Optional.empty());

        var count = emailService.saveEmails(List.of(incoming), user, "google");

        assertEquals(1, count);
        assertEquals(user, incoming.getUser());
        assertEquals("google", incoming.getProvider());
        verify(emailRepository).saveAll(anyCollection());
    }

    @Test
    void syncEmailsAsync_labelRefresh_starsAndSpamExistingEmails() throws Exception {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var lastEmail = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("last-email")
                .receivedAt(Instant.now())
                .user(user)
                .build();
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        var starredResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("star-1"), new Message().setId("star-2")))
                .setNextPageToken(null);
        var spamResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("spam-1")))
                .setNextPageToken(null);
        when(listRequest.execute()).thenReturn(starredResp).thenReturn(spamResp);

        when(emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId)).thenReturn(Optional.of(lastEmail));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1000), 0));
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.fetchIncrementalEmails(anyString(), any(Instant.class), anySet()))
                .thenReturn(List.of());

        emailService.syncEmailsAsync(user, "google").join();

        verify(emailRepository).unstarAllByUserId(userId);
        verify(emailRepository).starByProviderEmailIds(eq(userId),
                argThat(ids -> ids.contains("star-1") && ids.contains("star-2") && ids.size() == 2));
        verify(emailRepository).addSpamLabelByProviderEmailIds(eq(userId),
                argThat(ids -> ids.contains("spam-1") && ids.size() == 1));
    }

    @Test
    void syncAllEmailsAsync_noNewEmails_stillRefreshesLabels() throws Exception {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        var inboxResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("existing-1")))
                .setNextPageToken(null);
        var spamResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("existing-2")))
                .setNextPageToken(null);
        var starredResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("existing-1")))
                .setNextPageToken(null);
        var spamLabelResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("existing-2")))
                .setNextPageToken(null);
        when(listRequest.execute())
                .thenReturn(inboxResp)
                .thenReturn(spamResp)
                .thenReturn(starredResp)
                .thenReturn(spamLabelResp);

        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of("existing-1", "existing-2"), PageRequest.of(0, 1000), 2));

        emailService.syncAllEmailsAsync(user, "google").join();

        verify(emailRepository).unstarAllByUserId(userId);
        verify(emailRepository).starByProviderEmailIds(eq(userId),
                argThat(ids -> ids.contains("existing-1") && ids.size() == 1));
        verify(emailRepository).addSpamLabelByProviderEmailIds(eq(userId),
                argThat(ids -> ids.contains("existing-2") && ids.size() == 1));
        verify(webSocketService).sendNotification(userId.toString(), "sync_complete");
    }
}