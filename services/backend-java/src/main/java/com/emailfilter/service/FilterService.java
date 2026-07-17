package com.emailfilter.service;

import com.emailfilter.dto.EmailDTO;
import com.emailfilter.model.FilterCriteria;
import com.emailfilter.model.User;
import com.emailfilter.repository.FilterCriteriaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FilterService {

    private final FilterCriteriaRepository filterRepository;
    private final EmailService emailService;
    private final AIServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public FilterCriteria saveFilter(User user, String name, String criteriaJson) {
        var filter = FilterCriteria.builder()
                .user(user)
                .name(name)
                .criteria(criteriaJson)
                .build();
        return filterRepository.save(filter);
    }

    @Transactional
    public Page<EmailDTO> applyFilter(User user, UUID filterId, int page, int size) {
        var filter = filterRepository.findById(filterId)
                .orElseThrow(() -> new RuntimeException("Filter not found"));

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> criteria = objectMapper.readValue(filter.getCriteria(), Map.class);
            var stringCriteria = criteria.entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> e.getKey().toString(),
                            e -> e.getValue() != null ? e.getValue().toString() : ""
                    ));
            return emailService.filterEmails(user.getId(), stringCriteria, page, size);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Invalid filter criteria", e);
        }
    }

    @Transactional(readOnly = true)
    public Page<EmailDTO> applyAiFilters(UUID userId, Map<String, String> criteria, int page, int size) {
        return emailService.filterEmails(userId, criteria, page, size);
    }
}
