package com.emailfilter.service;

import com.emailfilter.model.EmailContact;
import com.emailfilter.model.User;
import com.emailfilter.repository.EmailContactRepository;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock
    private EmailContactRepository contactRepository;
    @Mock
    private EmailRepository emailRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ContactService contactService;

    @Test
    void update_ofAContactOwnedByAnotherUser_is404() {
        var userId = UUID.randomUUID();
        var otherUser = User.builder().id(UUID.randomUUID()).build();
        var contact = EmailContact.builder().id(UUID.randomUUID()).user(otherUser).address("a@b.com").build();
        when(contactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));

        assertThrows(ResponseStatusException.class,
                () -> contactService.update(userId, contact.getId(), new com.emailfilter.dto.ContactPatchDTO()));
        verify(contactRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void rebuildInbound_setsTheReceivedCountAndKeepsWhatTheUserWrote() {
        var userId = UUID.randomUUID();
        var user = User.builder().id(userId).build();
        var existing = EmailContact.builder()
                .id(UUID.randomUUID())
                .user(user)
                .address("juan@x.com")
                .company("Acme")
                .topic("Facturas")
                .build();

        when(emailRepository.countBySenderSince(eq(userId), any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[]{"juan@x.com", 4L, Instant.parse("2026-10-01T10:00:00Z")}));
        when(emailRepository.nameCandidates(userId))
                .thenReturn(List.<Object[]>of(new Object[]{"juan@x.com", "Juan Pérez", Instant.parse("2026-10-01T10:00:00Z")}));
        when(contactRepository.findByUserIdAndAddress(userId, "juan@x.com")).thenReturn(Optional.of(existing));
        when(contactRepository.save(any(EmailContact.class))).thenAnswer(i -> i.getArgument(0));

        contactService.rebuildInbound(userId);

        var saved = ArgumentCaptor.forClass(EmailContact.class);
        verify(contactRepository).save(saved.capture());
        assertEquals(4, saved.getValue().getReceivedCount());
        assertEquals("Juan Pérez", saved.getValue().getDisplayName());
        assertEquals("Acme", saved.getValue().getCompany());
        assertEquals("Facturas", saved.getValue().getTopic());
    }

    @Test
    void rebuildInbound_createsARowForASenderWeHadNeverSeen() {
        var userId = UUID.randomUUID();
        when(emailRepository.countBySenderSince(eq(userId), any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[]{"nuevo@x.com", 1L, Instant.parse("2026-10-02T10:00:00Z")}));
        when(emailRepository.nameCandidates(userId)).thenReturn(List.of());
        when(contactRepository.findByUserIdAndAddress(userId, "nuevo@x.com")).thenReturn(Optional.empty());
        when(contactRepository.save(any(EmailContact.class))).thenAnswer(i -> i.getArgument(0));

        contactService.rebuildInbound(userId);

        var saved = ArgumentCaptor.forClass(EmailContact.class);
        verify(contactRepository).save(saved.capture());
        assertEquals("nuevo@x.com", saved.getValue().getAddress());
        assertEquals(1, saved.getValue().getReceivedCount());
    }

    @Test
    void rebuildInbound_normalizesTheRawFromHeaderAndSumsItsVariants() {
        var userId = UUID.randomUUID();
        when(emailRepository.countBySenderSince(eq(userId), any(Instant.class))).thenReturn(List.<Object[]>of(
                new Object[]{"Juan Pérez <Juan@X.com>", 2L, Instant.parse("2026-10-01T10:00:00Z")},
                new Object[]{"juan@x.com", 1L, Instant.parse("2026-10-02T10:00:00Z")}));
        when(emailRepository.nameCandidates(userId)).thenReturn(List.<Object[]>of(
                new Object[]{"juan pérez <juan@x.com>", "Juan Pérez", Instant.parse("2026-10-01T10:00:00Z")}));
        when(contactRepository.findByUserIdAndAddress(userId, "juan@x.com")).thenReturn(Optional.empty());
        when(contactRepository.save(any(EmailContact.class))).thenAnswer(i -> i.getArgument(0));

        contactService.rebuildInbound(userId);

        var saved = ArgumentCaptor.forClass(EmailContact.class);
        verify(contactRepository).save(saved.capture());
        assertEquals("juan@x.com", saved.getValue().getAddress());
        assertEquals(3, saved.getValue().getReceivedCount());
        assertEquals("Juan Pérez", saved.getValue().getDisplayName());
    }

    @Test
    void applySentCounts_setsTheCountWithoutTouchingWhatIsReceived() {
        var userId = UUID.randomUUID();
        var existing = EmailContact.builder()
                .id(UUID.randomUUID())
                .user(User.builder().id(userId).build())
                .address("a@x.com")
                .receivedCount(7)
                .company("Acme")
                .build();
        when(contactRepository.findByUserIdAndAddress(userId, "a@x.com")).thenReturn(Optional.of(existing));
        when(contactRepository.save(any(EmailContact.class))).thenAnswer(i -> i.getArgument(0));

        contactService.applySentCounts(userId, java.util.Map.of("a@x.com", 4));

        assertEquals(4, existing.getSentCount());
        assertEquals(7, existing.getReceivedCount());
        assertEquals("Acme", existing.getCompany());
    }

    @Test
    void upsertSent_incrementsTheSentCount() {
        var userId = UUID.randomUUID();
        var existing = EmailContact.builder()
                .id(UUID.randomUUID())
                .user(User.builder().id(userId).build())
                .address("a@x.com")
                .sentCount(2)
                .receivedCount(7)
                .build();
        when(contactRepository.findByUserIdAndAddress(userId, "a@x.com")).thenReturn(Optional.of(existing));
        when(contactRepository.save(any(EmailContact.class))).thenAnswer(i -> i.getArgument(0));

        contactService.upsertSent(userId, "A@X.com");

        assertEquals(3, existing.getSentCount());
        assertEquals(7, existing.getReceivedCount());
    }
}
