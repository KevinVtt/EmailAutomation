package com.emailfilter.service;

import com.emailfilter.exception.PermanentApiException;
import com.emailfilter.exception.TransientApiException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryLabelAdded;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.HistoryMessageDeleted;
import com.google.api.services.gmail.model.Profile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailServiceTest {

    private Gmail gmail;
    private Gmail.Users.Messages.List listRequest;
    private TestableGmailService gmailService;

    private static class TestableGmailService extends GmailService {
        private final Gmail gmail;

        TestableGmailService(Gmail gmail, RetryTemplate retryTemplate) {
            super(retryTemplate);
            this.gmail = gmail;
        }

        @Override
        protected Gmail buildGmailClient(String accessToken) {
            return gmail;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        gmail = mock(Gmail.class);
        gmailService = new TestableGmailService(gmail, fastRetryTemplate());
        listRequest = mock(Gmail.Users.Messages.List.class);
        var users = mock(Gmail.Users.class);
        var messages = mock(Gmail.Users.Messages.class);
        lenient().when(gmail.users()).thenReturn(users);
        lenient().when(users.messages()).thenReturn(messages);
        lenient().when(messages.list("me")).thenReturn(listRequest);
        lenient().when(listRequest.setQ(anyString())).thenReturn(listRequest);
        lenient().when(listRequest.setMaxResults(anyLong())).thenReturn(listRequest);
        lenient().when(listRequest.setPageToken(anyString())).thenReturn(listRequest);
    }

    private RetryTemplate fastRetryTemplate() {
        RetryTemplate template = new RetryTemplate();
        SimpleRetryPolicy policy = new SimpleRetryPolicy(
                5, Map.<Class<? extends Throwable>, Boolean>of(TransientApiException.class, true));
        template.setRetryPolicy(policy);
        FixedBackOffPolicy backOff = new FixedBackOffPolicy();
        backOff.setBackOffPeriod(0);
        template.setBackOffPolicy(backOff);
        return template;
    }

    private GoogleJsonResponseException mockGjre(int status) {
        var gjre = mock(GoogleJsonResponseException.class);
        when(gjre.getStatusCode()).thenReturn(status);
        when(gjre.getHeaders()).thenReturn(new com.google.api.client.http.HttpHeaders());
        return gjre;
    }

    @Test
    void fetchAllEmails_fetchesSpamWithOwnBudget() throws Exception {
        // Inbox fills the entire budget (2), spam still gets its own budget (2)
        var inboxResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("inbox-1"), new Message().setId("inbox-2")))
                .setNextPageToken(null);
        var spamResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("spam-1")))
                .setNextPageToken(null);
        when(listRequest.execute()).thenReturn(inboxResp).thenReturn(spamResp);
        stubMessageDetails();

        var result = gmailService.fetchAllEmails("token", 100, 2);

        assertEquals(3, result.size());
        assertTrue(result.stream().anyMatch(e -> e.getProviderEmailId().equals("spam-1")));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(listRequest, times(2)).setQ(captor.capture());
        assertEquals(List.of("in:inbox", "in:spam"), captor.getAllValues());
    }

    @Test
    void fetchIncrementalEmails_fetchesInboxAndSpamWithDedup() throws Exception {
        var since = Instant.parse("2023-11-14T10:00:00Z");
        var existingIds = Set.of("inbox-existing");

        var inboxResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("inbox-existing"), new Message().setId("inbox-new")))
                .setNextPageToken(null);
        var spamResp = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("spam-new")))
                .setNextPageToken(null);
        when(listRequest.execute()).thenReturn(inboxResp).thenReturn(spamResp);
        stubMessageDetails();

        var result = gmailService.fetchIncrementalEmails("token", since, existingIds);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(e -> e.getProviderEmailId().equals("inbox-new")));
        assertTrue(result.stream().anyMatch(e -> e.getProviderEmailId().equals("spam-new")));
        assertTrue(result.stream().noneMatch(e -> e.getProviderEmailId().equals("inbox-existing")));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(listRequest, times(2)).setQ(captor.capture());
        assertTrue(captor.getAllValues().get(0).startsWith("in:inbox after:"));
        assertTrue(captor.getAllValues().get(1).startsWith("in:spam after:"));
    }

    private void stubMessageDetails() throws Exception {
        when(gmail.users().messages().get(eq("me"), anyString())).thenAnswer(inv -> {
            var id = (String) inv.getArgument(1);
            var getRequest = mock(Gmail.Users.Messages.Get.class);
            when(getRequest.setFormat("full")).thenReturn(getRequest);
            when(getRequest.execute()).thenReturn(buildFullMessage(id));
            return getRequest;
        });
    }

    private Message buildFullMessage(String id) {
        return new Message()
                .setId(id)
                .setThreadId("thread-" + id)
                .setInternalDate(1700000000000L)
                .setSnippet("snippet-" + id)
                .setLabelIds(List.of("INBOX"))
                .setPayload(new MessagePart()
                        .setHeaders(List.of(
                                new MessagePartHeader().setName("Subject").setValue("Subject " + id),
                                new MessagePartHeader().setName("From").setValue("from@example.com"),
                                new MessagePartHeader().setName("To").setValue("to@example.com"),
                                new MessagePartHeader().setName("Date").setValue("Tue, 14 Nov 2023 10:00:00 +0000")
                        ))
                        .setBody(new MessagePartBody()
                                .setData(Base64.getUrlEncoder().encodeToString(("body-" + id).getBytes()))));
    }

    @Test
    void fetchMessagesByIds_downloadsEveryIdInOrder() throws Exception {
        stubMessageDetails();

        var result = gmailService.fetchMessagesByIds(gmail, List.of("a", "b", "c"));

        assertEquals(List.of("a", "b", "c"),
                result.stream().map(com.emailfilter.model.EmailMessage::getProviderEmailId).toList());
    }

    @Test
    void buildMimeMessage_containsHeadersAndBody() {
        var mime = gmailService.buildMimeMessage("to@example.com", "Re: Hola", "Cuerpo");

        assertTrue(mime.contains("From: me"));
        assertTrue(mime.contains("To: to@example.com"));
        assertTrue(mime.contains("Subject: Re: Hola"));
        assertTrue(mime.contains("Content-Type: text/plain; charset=UTF-8"));
        assertTrue(mime.endsWith("\r\n\r\nCuerpo"));
    }

    @Test
    void sendEmail_encodesMimeAndCallsSend() throws Exception {
        var sendRequest = mock(Gmail.Users.Messages.Send.class);
        when(gmail.users().messages().send(eq("me"), any(Message.class))).thenReturn(sendRequest);
        when(sendRequest.execute()).thenReturn(new Message().setId("sent-1"));

        var id = gmailService.sendEmail("token", "to@example.com", "Re: Hola", "Cuerpo");

        assertEquals("sent-1", id);
        var captor = ArgumentCaptor.forClass(Message.class);
        verify(gmail.users().messages()).send(eq("me"), captor.capture());
        var decoded = new String(Base64.getUrlDecoder().decode(captor.getValue().getRaw()));
        assertTrue(decoded.contains("To: to@example.com"));
        assertTrue(decoded.contains("Subject: Re: Hola"));
        assertTrue(decoded.contains("Content-Type: text/plain; charset=UTF-8"));
    }

    /**
     * A 403 from Gmail with reason "rateLimitExceeded" is a per-user quota error,
     * NOT a permission error: it must be retried (TransientApiException), otherwise
     * a full sync aborts and leaves 0 emails (the original bug).
     */
    @Test
    void rateLimit403_isTransientAndRetried() throws Exception {
        var gjre = mockGjre(403);
        var details = new com.google.api.client.googleapis.json.GoogleJsonError();
        var errorInfo = new com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo();
        errorInfo.setReason("rateLimitExceeded");
        details.setErrors(List.of(errorInfo));
        when(gjre.getDetails()).thenReturn(details);
        when(listRequest.execute()).thenThrow(gjre);

        assertThrows(TransientApiException.class, () -> gmailService.fetchAllEmails("token", 100, 5));
    }

    /**
     * A 403 without a quota reason (e.g. revoked permissions) must stay permanent
     * so the RetryTemplate does not waste attempts.
     */
    @Test
    void permission403_isPermanent() throws Exception {
        var gjre = mockGjre(403);
        when(listRequest.execute()).thenThrow(gjre);

        assertThrows(PermanentApiException.class, () -> gmailService.fetchAllEmails("token", 100, 5));
    }

    @Test
    void getHistoryId_readsProfileCursor() throws Exception {
        var profileRequest = mock(Gmail.Users.GetProfile.class);
        when(gmail.users().getProfile("me")).thenReturn(profileRequest);
        when(profileRequest.execute()).thenReturn(new Profile().setHistoryId(new BigInteger("123")));

        assertEquals("123", gmailService.getHistoryId("token"));
    }

    @Test
    void listHistory_collectsAddedDeletedAndTrashedMessages() throws Exception {
        var historyRequest = mock(Gmail.Users.History.List.class);
        var history = mock(Gmail.Users.History.class);
        when(gmail.users().history()).thenReturn(history);
        when(history.list("me")).thenReturn(historyRequest);
        when(historyRequest.setStartHistoryId(any())).thenReturn(historyRequest);
        when(historyRequest.setHistoryTypes(any())).thenReturn(historyRequest);
        when(historyRequest.setMaxResults(anyLong())).thenReturn(historyRequest);

        var response = new ListHistoryResponse()
                .setHistory(List.of(new History()
                        .setMessagesAdded(List.of(
                                new HistoryMessageAdded().setMessage(new Message().setId("added-1"))))
                        .setMessagesDeleted(List.of(
                                new HistoryMessageDeleted().setMessage(new Message().setId("perm-1"))))
                        .setLabelsAdded(List.of(new HistoryLabelAdded()
                                .setMessage(new Message().setId("trashed-1"))
                                .setLabelIds(List.of("TRASH"))))))
                .setHistoryId(new BigInteger("2000"))
                .setNextPageToken(null);
        when(historyRequest.execute()).thenReturn(response);

        var changes = gmailService.listHistory("token", "1000");

        assertNotNull(changes);
        assertEquals(Set.of("added-1"), changes.addedIds());
        assertEquals(Set.of("perm-1", "trashed-1"), changes.deletedIds());
        assertEquals("2000", changes.latestHistoryId());
    }

    /** Un cursor vencido devuelve 404: se devuelve null para forzar el resync por fecha. */
    @Test
    void listHistory_expiredCursor_returnsNull() throws Exception {
        var historyRequest = mock(Gmail.Users.History.List.class);
        var history = mock(Gmail.Users.History.class);
        when(gmail.users().history()).thenReturn(history);
        when(history.list("me")).thenReturn(historyRequest);
        when(historyRequest.setStartHistoryId(any())).thenReturn(historyRequest);
        when(historyRequest.setHistoryTypes(any())).thenReturn(historyRequest);
        when(historyRequest.setMaxResults(anyLong())).thenReturn(historyRequest);
        var expired = mockGjre(404);
        when(historyRequest.execute()).thenThrow(expired);

        assertNull(gmailService.listHistory("token", "1"));
    }
}