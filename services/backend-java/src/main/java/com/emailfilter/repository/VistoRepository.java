package com.emailfilter.repository;

import com.emailfilter.model.Visto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VistoRepository extends JpaRepository<Visto, UUID> {
    Optional<Visto> findByProviderEmailIdAndUserId(String providerEmailId, UUID userId);

    @Query("SELECT v.providerEmailId FROM Visto v WHERE v.user.id = :userId AND v.providerEmailId IN :emailIds AND v.visto = true")
    List<String> findVistoEmailIdsByUserIdAndProviderEmailIdIn(UUID userId, Collection<String> emailIds);
}
