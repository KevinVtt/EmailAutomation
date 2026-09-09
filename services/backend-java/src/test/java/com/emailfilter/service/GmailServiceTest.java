package com.emailfilter.service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        TestableGmailService(Gmail gmail) {
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
        gmailService = new TestableGmailService(gmail);
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
}