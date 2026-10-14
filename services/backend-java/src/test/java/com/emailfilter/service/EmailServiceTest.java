package com.emailfilter.service;

import com.emailfilter.dto.GmailHistoryChanges;
import com.emailfilter.exception.TransientApiException;
import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.OAuthToken;
import com.emailfilter.model.SyncJob;
import com.emailfilter.model.User;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.OAuthTokenRepository;
import com.emailfilter.repository.UserRepository;
import com.emailfilter.repository.VistoRepository;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private AIServiceClient aiServiceClient;
    @Mock
    private HtmlSanitizerService htmlSanitizerService;
    @Mock
    private SyncJobService syncJobService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private Gmail gmail;

    private EmailService emailService;
    private Gmail.Users.Messages.List listRequest;

    @BeforeEach
    void setUp() throws Exception {
        emailService = new TestableEmailService(emailRepository, oauthTokenRepository, vistoRepository,
                gmailService, outlookService, webSocketService, aiServiceClient, htmlSanitizerService, syncJobService, userRepository, gmail);
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
        lenient().when(syncJobService.start(any(), anyString(), anyString()))
                .thenReturn(SyncJob.builder().id(UUID.randomUUID()).build());
    }

    private static class TestableEmailService extends EmailService {
        private final Gmail gmail;

        TestableEmailService(EmailRepository emailRepository, OAuthTokenRepository oauthTokenRepository,
                             VistoRepository vistoRepository, GmailService gmailService,
                             OutlookService outlookService, WebSocketService webSocketService,
                             AIServiceClient aiServiceClient, HtmlSanitizerService htmlSanitizerService,
                             SyncJobService syncJobService, UserRepository userRepository, Gmail gmail) {
            super(emailRepository, oauthTokenRepository, vistoRepository, gmailService, outlookService,
                    webSocketService, aiServiceClient, htmlSanitizerService, syncJobService, userRepository);
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

    @Test
    void syncAllEmailsAsync_newEmails_fetchesEachAndSaves() throws Exception {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(listRequest.execute())
                .thenReturn(new ListMessagesResponse()
                        .setMessages(List.of(new Message().setId("new-1"), new Message().setId("new-2")))
                        .setNextPageToken(null))
                .thenReturn(new ListMessagesResponse().setNextPageToken(null))
                .thenReturn(new ListMessagesResponse().setNextPageToken(null))
                .thenReturn(new ListMessagesResponse().setNextPageToken(null));

        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<String>of(), PageRequest.of(0, 1000), 0));
        when(gmailService.fetchMessagesByIds(eq(gmail),
                argThat(ids -> ids != null && ids.containsAll(List.of("new-1", "new-2")))))
                .thenReturn(List.of(
                        EmailMessage.builder().providerEmailId("new-1").build(),
                        EmailMessage.builder().providerEmailId("new-2").build()));

        emailService.syncAllEmailsAsync(user, "google").join();

        verify(gmailService).fetchMessagesByIds(eq(gmail), argThat(ids -> ids.size() == 2));
        verify(emailRepository).saveAll(argThat(list ->
                ((java.util.List<?>) list).size() == 2));
        verify(webSocketService).sendNotification(userId.toString(), "sync_complete");
    }

    @Test
    void getDailySummary_today_returnsSummary() {
        var userId = UUID.randomUUID();
        var email = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("email-1")
                .subject("Report")
                .user(User.builder().id(userId).build())
                .build();
        var page = new PageImpl<>(List.of(email), PageRequest.of(0, 100), 1);

        when(emailRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(vistoRepository.findVistoEmailIdsByUserIdAndProviderEmailIdIn(eq(userId), anyCollection()))
                .thenReturn(List.of());
        when(aiServiceClient.summarizeEmails(any())).thenReturn("Resumen del día");

        var result = emailService.getDailySummary(userId, "today");

        assertEquals("Resumen del día", result.get("summary"));
        assertEquals(1L, result.get("emailCount"));
        assertEquals(LocalDate.now().toString(), result.get("date"));
        verify(aiServiceClient).summarizeEmails(any());
    }

    @Test
    void getDailySummary_noEmails_returnsEmptySummary() {
        var userId = UUID.randomUUID();
        var page = new PageImpl<>(List.<EmailMessage>of(), PageRequest.of(0, 100), 0);

        when(emailRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        var result = emailService.getDailySummary(userId, "yesterday");

        assertEquals("No hay correos para resumir.", result.get("summary"));
        assertEquals(0, result.get("emailCount"));
        assertEquals(LocalDate.now().minusDays(1).toString(), result.get("date"));
        verify(aiServiceClient, never()).summarizeEmails(any());
    }

    @Test
    void buildPrioritySummary_returnsCountsFromRepository() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).email("me@example.com").build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(emailRepository.countUrgentesByUserId(userId)).thenReturn(3L);
        when(emailRepository.countDestacadosSinLeerByUserId(userId)).thenReturn(12L);
        when(emailRepository.countPendientesRespuestaByUserId(userId, "me@example.com")).thenReturn(5L);

        var result = emailService.buildPrioritySummary(userId);

        assertEquals(3L, result.getUrgentes());
        assertEquals(12L, result.getDestacadosSinLeer());
        assertEquals(5L, result.getPendientesRespuesta());
        verify(emailRepository).countPendientesRespuestaByUserId(userId, "me@example.com");
    }

    @Test
    void filterEmails_destacados_buildsStarredOrImportantPredicate() {
        var userId = UUID.randomUUID();
        var page = new PageImpl<>(List.<EmailMessage>of(), PageRequest.of(0, 20), 0);
        when(emailRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        emailService.filterEmails(userId, Map.of("destacados", "true"), 0, 20);

        var cb = mock(CriteriaBuilder.class);
        var root = mock(Root.class);
        var userPath = mock(Path.class);
        var starredPred = mock(Predicate.class);
        var importantPred = mock(Predicate.class);
        when(root.get("user")).thenReturn(userPath);
        when(userPath.get("id")).thenReturn(mock(Path.class));
        when(cb.equal(any(Expression.class), eq(userId))).thenReturn(mock(Predicate.class));
        when(cb.equal(root.get("isStarred"), true)).thenReturn(starredPred);
        when(cb.like(root.get("labels"), "%IMPORTANT%")).thenReturn(importantPred);
        when(cb.or(starredPred, importantPred)).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        var captor = ArgumentCaptor.forClass(Specification.class);
        verify(emailRepository).findAll(captor.capture(), any(Pageable.class));
        captor.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).or(starredPred, importantPred);
    }

    @Test
    void filterEmails_destacadosBlank_doesNotAddOrPredicate() {
        var userId = UUID.randomUUID();
        var page = new PageImpl<>(List.<EmailMessage>of(), PageRequest.of(0, 20), 0);
        when(emailRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        emailService.filterEmails(userId, Map.of("destacados", ""), 0, 20);

        var cb = mock(CriteriaBuilder.class);
        var root = mock(Root.class);
        var userPath = mock(Path.class);
        when(root.get("user")).thenReturn(userPath);
        when(userPath.get("id")).thenReturn(mock(Path.class));
        when(cb.equal(any(Expression.class), eq(userId))).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        var captor = ArgumentCaptor.forClass(Specification.class);
        verify(emailRepository).findAll(captor.capture(), any(Pageable.class));
        captor.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb, never()).or(any(Predicate[].class));
    }

    @Test
    void syncEmailsAsync_urgentEmail_sendsUrgentNotification() throws Exception {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var lastEmail = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("last-email")
                .receivedAt(Instant.now())
                .user(user)
                .build();
        var newEmail = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("new-1")
                .subject("URGENT: Server down")
                .user(user)
                .build();
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        var starredResp = new ListMessagesResponse().setMessages(List.of()).setNextPageToken(null);
        var spamResp = new ListMessagesResponse().setMessages(List.of()).setNextPageToken(null);
        when(listRequest.execute()).thenReturn(starredResp).thenReturn(spamResp);

        when(emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId)).thenReturn(Optional.of(lastEmail));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1000), 0));
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.fetchIncrementalEmails(anyString(), any(Instant.class), anySet()))
                .thenReturn(List.of(newEmail));
        when(emailRepository.findByProviderEmailIdAndUserId("new-1", userId)).thenReturn(Optional.empty());
        when(aiServiceClient.analyzeEmails(any()))
                .thenReturn(Map.of("classifications", List.of(Map.of("id", "new-1", "urgency", "high"))));

        emailService.syncEmailsAsync(user, "google").join();

        verify(webSocketService).sendNotification(userId.toString(), "urgent_emails:1:URGENT: Server down");
    }

    @Test
    void syncEmailsAsync_lowUrgency_noUrgentNotification() throws Exception {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var lastEmail = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("last-email")
                .receivedAt(Instant.now())
                .user(user)
                .build();
        var newEmail = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("new-1")
                .subject("Newsletter")
                .user(user)
                .build();
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        var starredResp = new ListMessagesResponse().setMessages(List.of()).setNextPageToken(null);
        var spamResp = new ListMessagesResponse().setMessages(List.of()).setNextPageToken(null);
        when(listRequest.execute()).thenReturn(starredResp).thenReturn(spamResp);

        when(emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId)).thenReturn(Optional.of(lastEmail));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1000), 0));
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.fetchIncrementalEmails(anyString(), any(Instant.class), anySet()))
                .thenReturn(List.of(newEmail));
        when(emailRepository.findByProviderEmailIdAndUserId("new-1", userId)).thenReturn(Optional.empty());
        when(aiServiceClient.analyzeEmails(any()))
                .thenReturn(Map.of("classifications", List.of(Map.of("id", "new-1", "urgency", "low"))));

        emailService.syncEmailsAsync(user, "google").join();

        verify(webSocketService, never()).sendNotification(anyString(),
                argThat(msg -> msg.startsWith("urgent_emails:")));
    }

    @Test
    void sendReply_buildsReplyAndCallsGmail() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var original = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("orig-1")
                .fromAddress("sender@example.com")
                .subject("Hola")
                .user(user)
                .build();
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(emailRepository.findByProviderEmailIdAndUserId("orig-1", userId)).thenReturn(Optional.of(original));
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.sendEmail("test-access-token", "sender@example.com", "Re: Hola", "Respuesta"))
                .thenReturn("msg-1");

        var result = emailService.sendReply(user, "google", "orig-1", "Respuesta");

        assertEquals("sent", result.get("status"));
        assertEquals("msg-1", result.get("messageId"));
        verify(gmailService).sendEmail("test-access-token", "sender@example.com", "Re: Hola", "Respuesta");
    }

    @Test
    void sendReply_emailNotFound_throws() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();

        when(emailRepository.findByProviderEmailIdAndUserId("missing", userId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> emailService.sendReply(user, "google", "missing", "Hola"));
    }

    @Test
    void sendReply_unsupportedProvider_throws() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var original = EmailMessage.builder()
                .id(UUID.randomUUID())
                .provider("google")
                .providerEmailId("orig-1")
                .fromAddress("sender@example.com")
                .subject("Hola")
                .user(user)
                .build();
        var token = OAuthToken.builder()
                .accessToken("test-access-token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(emailRepository.findByProviderEmailIdAndUserId("orig-1", userId)).thenReturn(Optional.of(original));
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "yahoo"))
                .thenReturn(Optional.of(token));

        assertThrows(IllegalArgumentException.class,
                () -> emailService.sendReply(user, "yahoo", "orig-1", "Hola"));
    }

    // --- moveToTrash: el borrado debe reflejarse en la base local ---

    @Test
    void moveToTrash_trashesOnGmailAndRemovesLocalRow() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var token = OAuthToken.builder()
                .accessToken("t")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));

        emailService.moveToTrash(user, "google", "msg-1");

        verify(gmailService).moveToTrash("t", "msg-1");
        verify(emailRepository).deleteByUserIdAndProviderEmailIdIn(eq(userId), argThat(ids -> ids.contains("msg-1")));
        verify(vistoRepository).deleteByUserIdAndProviderEmailIdIn(eq(userId), argThat(ids -> ids.contains("msg-1")));
    }

    /**
     * Si Gmail falla por cuota/red (transitorio), la fila local NO se borra: el
     * mail sigue existiendo en Gmail y el usuario puede reintentar.
     */
    @Test
    void moveToTrash_transientProviderError_keepsLocalRow() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var token = OAuthToken.builder()
                .accessToken("t")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        doThrow(new TransientApiException("429")).when(gmailService).moveToTrash("t", "msg-1");

        assertThrows(TransientApiException.class, () -> emailService.moveToTrash(user, "google", "msg-1"));

        verify(emailRepository, never()).deleteByUserIdAndProviderEmailIdIn(any(), anyCollection());
        verify(vistoRepository, never()).deleteByUserIdAndProviderEmailIdIn(any(), anyCollection());
    }

    // --- history sync: baja de mails borrados en Gmail + alta de nuevos ---

    @Test
    void syncEmailsAsync_historySync_deletesRemovedAndFetchesAdded() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var token = OAuthToken.builder()
                .accessToken("t")
                .historyId("1000")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.listHistory("t", "1000")).thenReturn(
                new GmailHistoryChanges(Set.of("new-1", "old-1"), Set.of("gone-1"), "2000"));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of("old-1"), PageRequest.of(0, 1000), 1));
        var fetched = EmailMessage.builder()
                .provider("google")
                .providerEmailId("new-1")
                .user(user)
                .build();
        when(gmailService.fetchMessagesByIds(any(), argThat(ids ->
                ids.contains("new-1") && !ids.contains("old-1") && !ids.contains("gone-1"))))
                .thenReturn(List.of(fetched));

        emailService.syncEmailsAsync(user, "google").join();

        verify(emailRepository).deleteByUserIdAndProviderEmailIdIn(eq(userId), argThat(ids -> ids.contains("gone-1")));
        verify(vistoRepository).deleteByUserIdAndProviderEmailIdIn(eq(userId), argThat(ids -> ids.contains("gone-1")));
        verify(emailRepository).saveAll(anyList());
        verify(oauthTokenRepository).save(argThat(t -> "2000".equals(t.getHistoryId())));
    }

    @Test
    void syncEmailsAsync_staleHistoryId_fallsBackToDateSyncAndReseedsCursor() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var token = OAuthToken.builder()
                .accessToken("t")
                .historyId("9999")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        var lastEmail = EmailMessage.builder()
                .provider("google")
                .providerEmailId("last")
                .receivedAt(Instant.now())
                .user(user)
                .build();

        when(oauthTokenRepository.findFirstByUserIdAndProviderOrderByCreatedAtDesc(userId, "google"))
                .thenReturn(Optional.of(token));
        when(gmailService.listHistory("t", "9999")).thenReturn(null);
        when(emailRepository.findTopByUserIdOrderByReceivedAtDesc(userId)).thenReturn(Optional.of(lastEmail));
        when(emailRepository.findProviderEmailIdsByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1000), 0));
        when(gmailService.fetchIncrementalEmails(anyString(), any(Instant.class), anySet())).thenReturn(List.of());
        when(gmailService.getHistoryId("t")).thenReturn("5555");

        emailService.syncEmailsAsync(user, "google").join();

        verify(gmailService).fetchIncrementalEmails(anyString(), any(Instant.class), anySet());
        verify(oauthTokenRepository).save(argThat(t -> "5555".equals(t.getHistoryId())));
    }
}