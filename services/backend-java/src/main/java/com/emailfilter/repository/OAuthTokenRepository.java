package com.emailfilter.repository;

import com.emailfilter.model.OAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, UUID> {
    Optional<OAuthToken> findFirstByUserIdAndProviderOrderByCreatedAtDesc(UUID userId, String provider);

    @Modifying
    @Transactional
    @Query("update OAuthToken t set t.historyId = :historyId where t.id = :id")
    int updateHistoryId(@Param("id") UUID id, @Param("historyId") String historyId);
}
