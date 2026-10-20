package com.emailfilter.repository;

import com.emailfilter.model.EmailContact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailContactRepository extends JpaRepository<EmailContact, UUID> {
    Optional<EmailContact> findByUserIdAndAddress(UUID userId, String address);

    long countByUserId(UUID userId);

    List<EmailContact> findByUserId(UUID userId);

    @Query("""
            SELECT c FROM EmailContact c
            WHERE c.user.id = :userId
            ORDER BY (c.sentCount + c.receivedCount) DESC, c.lastActivityAt DESC
            """)
    List<EmailContact> findMostActive(@Param("userId") UUID userId, Pageable pageable);
}
