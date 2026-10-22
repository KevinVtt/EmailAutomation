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
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate transactionTemplate;

    public List<EmailContactDTO> list(UUID userId, int limit) {
        var page = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        return contactRepository.findMostActive(userId, page).stream().map(this::toDTO).toList();
    }

    @Transactional
    public EmailContactDTO update(UUID userId, UUID contactId, ContactPatchDTO patch) {
        var contact = findOwned(userId, contactId);

        // null means "not touched"; an empty string is the user clearing the field.
        if (patch.getCompany() != null) contact.setCompany(patch.getCompany());
        if (patch.getTopic() != null) contact.setTopic(patch.getTopic());
        if (patch.getNotes() != null) contact.setNotes(patch.getNotes());
        return toDTO(contactRepository.save(contact));
    }

    public EmailContact findOwned(UUID userId, UUID contactId) {
        return contactRepository.findById(contactId)
                .filter(c -> c.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
    }

    public List<String> recentSubjects(UUID userId, String address) {
        return emailRepository.findRecentSubjects(userId, address, PageRequest.of(0, 5));
    }

    /** La IA sólo completa lo que está vacío: lo que escribió el usuario manda. */
    @Transactional
    public EmailContactDTO applyProfile(EmailContact contact, Map<String, Object> profile) {
        var company = asText(profile.get("company"));
        var topic = asText(profile.get("topic"));
        if (company != null && (contact.getCompany() == null || contact.getCompany().isBlank())) {
            contact.setCompany(company);
        }
        if (topic != null && (contact.getTopic() == null || contact.getTopic().isBlank())) {
            contact.setTopic(topic);
        }
        return toDTO(contactRepository.save(contact));
    }

    private static String asText(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        var trimmed = text.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    /** Recalcula los recibidos desde cero; no toca empresa, tema ni notas. */
    @Transactional
    public void rebuildInbound(UUID userId) {
        var counts = new HashMap<String, Long>();
        var lastSeen = new HashMap<String, Instant>();
        for (var row : emailRepository.countBySenderSince(userId, Instant.EPOCH)) {
            var address = normalizeAddress((String) row[0]);
            if (address == null) continue;
            counts.merge(address, ((Number) row[1]).longValue(), Long::sum);
            var at = (Instant) row[2];
            if (at != null && (lastSeen.get(address) == null || at.isAfter(lastSeen.get(address)))) {
                lastSeen.put(address, at);
            }
        }

        var names = latestNames(userId);
        counts.forEach((address, count) ->
                upsertReceived(userId, address, names.get(address), lastSeen.get(address), count));

        // Un remitente cuyos mails ya no existen (basura, history sync) no puede
        // conservar el conteo viejo. shortcut: la actividad de un contacto con
        // enviados se mantiene, porque el barrido de enviados no guarda su fecha.
        for (var contact : contactRepository.findByUserId(userId)) {
            if (!counts.containsKey(contact.getAddress()) && contact.getReceivedCount() > 0) {
                contact.setReceivedCount(0);
                if (contact.getSentCount() == 0) contact.setLastActivityAt(null);
                contactRepository.save(contact);
            }
        }
    }

    /** La primera vez que se ve al usuario hay que leer sus enviados; después no. */
    public boolean needsSentScan(UUID userId) {
        // shortcut: tabla vacía = "nunca escaneado". Un usuario sin recibidos vuelve a
        // escanear en cada sync; agregar una marca por usuario si eso aparece en los logs.
        return contactRepository.countByUserId(userId) == 0;
    }

    /** Snapshot del barrido de enviados: pisa el conteo, no lo suma. */
    @Transactional
    public void applySentCounts(UUID userId, Map<String, Integer> counts) {
        counts.forEach((rawAddress, count) -> {
            var address = normalizeAddress(rawAddress);
            if (address == null) return;
            var contact = findOrCreate(userId, address);
            contact.setSentCount(count);
            contactRepository.save(contact);
        });
    }

    /** Un envío concreto: suma uno a los enviados de ese contacto. Nunca lanza: el mail ya se envió. */
    public void upsertSent(UUID userId, String address) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                var normalized = normalizeAddress(address);
                if (normalized == null) return;
                var contact = findOrCreate(userId, normalized);
                contact.setSentCount(contact.getSentCount() + 1);
                touch(contact, Instant.now());
                contactRepository.save(contact);
            });
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar el envío a {} en contactos", address, e);
        }
    }

    /** Una fila por remitente con el último nombre que usó: una sola consulta, sin N+1. */
    private Map<String, String> latestNames(UUID userId) {
        var latest = new HashMap<String, String>();
        var latestSeen = new HashMap<String, Instant>();
        for (var row : emailRepository.nameCandidates(userId)) {
            var address = normalizeAddress((String) row[0]);
            if (address == null) continue;
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
        var normalized = normalizeAddress(address);
        if (normalized == null) return;
        var contact = findOrCreate(userId, normalized);

        if (displayName != null && !displayName.isBlank()) contact.setDisplayName(displayName);
        contact.setReceivedCount((int) count);
        touch(contact, lastActivityAt);
        contactRepository.save(contact);
    }

    private EmailContact findOrCreate(UUID userId, String address) {
        return contactRepository.findByUserIdAndAddress(userId, address)
                .orElseGet(() -> EmailContact.builder()
                        .user(userRepository.getReferenceById(userId))
                        .address(address)
                        .build());
    }

    private static void touch(EmailContact contact, Instant at) {
        if (at != null && (contact.getLastActivityAt() == null || at.isAfter(contact.getLastActivityAt()))) {
            contact.setLastActivityAt(at);
        }
    }

    /** "Juan Pérez &lt;Juan@X.com&gt;" → "juan@x.com": el email manda, el nombre no. */
    static String normalizeAddress(String raw) {
        if (raw == null) return null;
        var value = raw.trim();
        int lt = value.lastIndexOf('<');
        int gt = value.lastIndexOf('>');
        if (lt >= 0 && gt > lt) value = value.substring(lt + 1, gt).trim();
        value = value.replace("\"", "").trim().toLowerCase(java.util.Locale.ROOT);
        return value.isEmpty() || !value.contains("@") ? null : value;
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
