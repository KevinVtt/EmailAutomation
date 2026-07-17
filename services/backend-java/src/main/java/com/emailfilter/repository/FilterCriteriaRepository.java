package com.emailfilter.repository;

import com.emailfilter.model.FilterCriteria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FilterCriteriaRepository extends JpaRepository<FilterCriteria, UUID> {
    List<FilterCriteria> findByUserId(UUID userId);
}
