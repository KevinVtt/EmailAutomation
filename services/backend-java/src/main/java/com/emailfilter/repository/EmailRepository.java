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
    Optional<EmailMessage> findTopByUserIdOrderByReceivedAtDesc(UUID userId);

    @Query("SELECT e.providerEmailId FROM EmailMessage e WHERE e.user.id = :userId")
    Page<String> findProviderEmailIdsByUserId(UUID userId, Pageable pageable);

    Optional<EmailMessage> findByProviderEmailIdAndUserId(String providerEmailId, UUID userId);

    List<EmailMessage> findByUserIdAndProviderEmailIdIn(UUID userId, Collection<String> providerEmailIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailMessage e WHERE e.user.id = :userId AND e.providerEmailId IN :providerEmailIds")
    int deleteByUserIdAndProviderEmailIdIn(@Param("userId") UUID userId,
                                           @Param("providerEmailIds") Collection<String> providerEmailIds);

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

    // Quita el token exacto SPAM de labels (posiciones inicial/media/final). Los
    // demás labels válidos no contienen "SPAM" como substring, así que REPLACE es seguro.
    @Modifying
    @Transactional
    @Query("UPDATE EmailMessage e SET e.labels = " +
            "REPLACE(REPLACE(REPLACE(e.labels, ',SPAM', ''), 'SPAM,', ''), 'SPAM', '') " +
            "WHERE e.user.id = :userId AND e.labels LIKE '%SPAM%'")
    int removeSpamLabelByUserId(@Param("userId") UUID userId);

    // --- Stats endpoint (`GET /api/emails/stats`) ---
    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isRead = false")
    long countUnreadByUserId(@Param("userId") UUID userId);

    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isStarred = true")
    long countStarredByUserId(@Param("userId") UUID userId);

    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.labels LIKE '%SPAM%'")
    long countSpamByUserId(@Param("userId") UUID userId);

    @Query("SELECT e.receivedAt FROM EmailMessage e WHERE e.user.id = :userId AND e.receivedAt >= :since")
    List<Instant> findReceivedAtByUserIdSince(@Param("userId") UUID userId, @Param("since") Instant since);

    // --- Priority summary counts ---
    // PROXY: no `urgency` column exists — Gmail auto-marks IMPORTANT, so "urgentes"
    // = unread + IMPORTANT label (best available urgency proxy).
    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isRead = false AND e.labels LIKE '%IMPORTANT%'")
    long countUrgentesByUserId(@Param("userId") UUID userId);

    // PROXY: matches the existing "destacados" filter semantics (isStarred OR IMPORTANT) + unread.
    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isRead = false AND (e.isStarred = true OR e.labels LIKE '%IMPORTANT%')")
    long countDestacadosSinLeerByUserId(@Param("userId") UUID userId);

    // PROXY: sent emails are NOT synced (inbox+spam only), so a thread-based "no reply"
    // query would count everything. Unread received emails are the honest proxy for
    // "waiting for your response".
    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isRead = false AND e.fromAddress IS NOT NULL AND e.fromAddress <> :userEmail")
    long countPendientesRespuestaByUserId(@Param("userId") UUID userId, @Param("userEmail") String userEmail);
}
