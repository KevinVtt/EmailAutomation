package com.emailfilter.repository;

import com.emailfilter.model.Visto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VistoRepository extends JpaRepository<Visto, UUID> {
    Optional<Visto> findByProviderEmailIdAndUserId(String providerEmailId, UUID userId);
}
