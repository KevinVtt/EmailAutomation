package com.emailfilter.repository;

import com.emailfilter.model.EmailMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
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
    Page<String> findProviderEmailIdsByUserId(UUID userId, Pageable pageable);

    Optional<EmailMessage> findByProviderEmailIdAndUserId(String providerEmailId, UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE EmailMessage e SET e.isStarred = false WHERE e.user.id = :userId")
    int unstarAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE EmailMessage e SET e.isStarred = true WHERE e.user.id = :userId AND e.providerEmailId IN :starredIds")
    int starByProviderEmailIds(@Param("userId") UUID userId, @Param("starredIds") Collection<String> starredIds);

    @Modifying
    @Transactional
    @Query("UPDATE EmailMessage e SET e.labels = CASE WHEN e.labels IS NULL OR e.labels = '' THEN 'SPAM' ELSE CONCAT(e.labels, ',SPAM') END WHERE e.user.id = :userId AND e.providerEmailId IN :spamIds AND (e.labels IS NULL OR e.labels NOT LIKE '%SPAM%')")
    int addSpamLabelByProviderEmailIds(@Param("userId") UUID userId, @Param("spamIds") Collection<String> spamIds);
}
