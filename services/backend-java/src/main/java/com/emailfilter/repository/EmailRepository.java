package com.emailfilter.repository;

import com.emailfilter.model.EmailMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailRepository extends JpaRepository<EmailMessage, UUID>,
                                         JpaSpecificationExecutor<EmailMessage> {
    Page<EmailMessage> findByUserIdOrderByReceivedAtDesc(UUID userId, Pageable pageable);
    boolean existsByUserIdAndProviderEmailId(UUID userId, String providerEmailId);
    Optional<EmailMessage> findTopByUserIdOrderByReceivedAtDesc(UUID userId);
    long countByUserIdAndReceivedAtAfter(UUID userId, Instant since);

    @Query("SELECT e.providerEmailId FROM EmailMessage e WHERE e.user.id = :userId")
    List<String> findProviderEmailIdsByUserId(UUID userId);

    Optional<EmailMessage> findByProviderEmailIdAndUserId(String providerEmailId, UUID userId);
}
