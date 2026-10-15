package com.emailfilter.service;

import com.emailfilter.dto.EmailDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FilterService {

    private final EmailService emailService;

    @Transactional(readOnly = true)
    public Page<EmailDTO> applyAiFilters(UUID userId, Map<String, String> criteria, int page, int size) {
        return emailService.filterEmails(userId, criteria, page, size);
    }
}
