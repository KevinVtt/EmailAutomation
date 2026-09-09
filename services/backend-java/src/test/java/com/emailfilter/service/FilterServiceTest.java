package com.emailfilter.service;

import com.emailfilter.exception.ResourceNotFoundException;
import com.emailfilter.model.FilterCriteria;
import com.emailfilter.model.User;
import com.emailfilter.repository.FilterCriteriaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FilterServiceTest {

    @Mock
    private FilterCriteriaRepository filterRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private AIServiceClient aiServiceClient;

    private FilterService filterService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filterService = new FilterService(filterRepository, emailService, aiServiceClient, objectMapper);
    }

    @Test
    void saveFilter_createsAndReturnsFilter() {
        var user = User.builder().id(UUID.randomUUID()).email("test@example.com").build();
        var criteriaJson = "{\"fromAddress\":\"boss@example.com\"}";
        var savedFilter = FilterCriteria.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name("Boss emails")
                .criteria(criteriaJson)
                .build();

        when(filterRepository.save(any())).thenReturn(savedFilter);

        var result = filterService.saveFilter(user, "Boss emails", criteriaJson);

        assertNotNull(result);
        assertEquals("Boss emails", result.getName());
        verify(filterRepository).save(any());
    }

    @Test
    void applyFilter_filterNotFound_throwsException() {
        var filterId = UUID.randomUUID();
        when(filterRepository.findById(filterId)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () ->
                filterService.applyFilter(null, filterId, 0, 20));
    }

    @Test
    void applyFilter_filterBelongsToAnotherUser_throwsException() {
        var owner = User.builder().id(UUID.randomUUID()).email("owner@example.com").build();
        var otherUser = User.builder().id(UUID.randomUUID()).email("other@example.com").build();
        var filterId = UUID.randomUUID();
        var filter = FilterCriteria.builder()
                .id(filterId)
                .user(owner)
                .name("Owner filter")
                .criteria("{\"fromAddress\":\"boss@example.com\"}")
                .build();

        when(filterRepository.findById(filterId)).thenReturn(Optional.of(filter));

        assertThrows(ResourceNotFoundException.class, () ->
                filterService.applyFilter(otherUser, filterId, 0, 20));
    }
}
