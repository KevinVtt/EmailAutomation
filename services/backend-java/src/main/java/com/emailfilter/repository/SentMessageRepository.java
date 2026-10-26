package com.emailfilter.repository;

import com.emailfilter.model.SentMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SentMessageRepository extends JpaRepository<SentMessage, UUID> {

    Page<SentMessage> findByUserIdOrderBySentAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT s.providerEmailId FROM SentMessage s WHERE s.user.id = :userId")
    Set<String> findProviderEmailIdsByUserId(@Param("userId") UUID userId);

    List<SentMessage> findByUserIdAndProviderEmailIdIn(UUID userId, List<String> providerEmailIds);
}
