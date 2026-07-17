package com.emailfilter.repository;

import com.emailfilter.model.OAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, UUID> {
    Optional<OAuthToken> findFirstByUserIdAndProviderOrderByCreatedAtDesc(UUID userId, String provider);
}
