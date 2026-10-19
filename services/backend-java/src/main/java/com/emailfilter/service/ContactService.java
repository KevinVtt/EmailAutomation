package com.emailfilter.service;

import com.emailfilter.dto.ContactPatchDTO;
import com.emailfilter.dto.EmailContactDTO;
import com.emailfilter.model.EmailContact;
import com.emailfilter.repository.EmailContactRepository;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private final EmailContactRepository contactRepository;
    private final EmailRepository emailRepository;
    private final UserRepository userRepository;

    public List<EmailContactDTO> list(UUID userId, int limit) {
        var page = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        return contactRepository.findMostActive(userId, page).stream().map(this::toDTO).toList();
    }

    @Transactional
    public EmailContactDTO update(UUID userId, UUID contactId, ContactPatchDTO patch) {
        var contact = contactRepository.findById(contactId)
                .filter(c -> c.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));

        // null means "not touched"; an empty string is the user clearing the field.
        if (patch.getCompany() != null) contact.setCompany(patch.getCompany());
        if (patch.getTopic() != null) contact.setTopic(patch.getTopic());
        if (patch.getNotes() != null) contact.setNotes(patch.getNotes());
        return toDTO(contactRepository.save(contact));
    }

    /** Recalcula los recibidos desde cero; no toca empresa, tema ni notas. */
    @Transactional
    public void rebuildInbound(UUID userId) {
        var names = latestNames(userId);
        for (var row : emailRepository.countBySenderSince(userId, Instant.EPOCH)) {
            var address = (String) row[0];
            upsertReceived(userId, address, names.get(address),
                    (Instant) row[2], ((Number) row[1]).longValue());
        }
    }

    /** Una fila por remitente con el último nombre que usó: una sola consulta, sin N+1. */
    private Map<String, String> latestNames(UUID userId) {
        var latest = new HashMap<String, String>();
        var latestSeen = new HashMap<String, Instant>();
        for (var row : emailRepository.nameCandidates(userId)) {
            var address = (String) row[0];
            var seen = (Instant) row[2];
            if (latestSeen.get(address) == null || seen.isAfter(latestSeen.get(address))) {
                latestSeen.put(address, seen);
                latest.put(address, (String) row[1]);
            }
        }
        return latest;
    }

    @Transactional
    public void upsertReceived(UUID userId, String address, String displayName,
                               Instant lastActivityAt, long count) {
        var contact = contactRepository.findByUserIdAndAddress(userId, address)
                .orElseGet(() -> EmailContact.builder()
                        .user(userRepository.getReferenceById(userId))
                        .address(address)
                        .build());

        if (displayName != null && !displayName.isBlank()) contact.setDisplayName(displayName);
        contact.setReceivedCount((int) count);
        if (lastActivityAt != null
                && (contact.getLastActivityAt() == null || lastActivityAt.isAfter(contact.getLastActivityAt()))) {
            contact.setLastActivityAt(lastActivityAt);
        }
        contactRepository.save(contact);
    }

    private EmailContactDTO toDTO(EmailContact c) {
        return EmailContactDTO.builder()
                .id(c.getId())
                .address(c.getAddress())
                .displayName(c.getDisplayName())
                .company(c.getCompany())
                .topic(c.getTopic())
                .notes(c.getNotes())
                .sentCount(c.getSentCount())
                .receivedCount(c.getReceivedCount())
                .lastActivityAt(c.getLastActivityAt())
                .build();
    }
}
