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

    /** Recibidos agrupados por remitente, sin importar mayúsculas: [address, count, last]. */
    @Query("""
            SELECT lower(e.fromAddress), COUNT(e), MAX(e.receivedAt)
            FROM EmailMessage e
            WHERE e.user.id = :userId AND e.fromAddress IS NOT NULL AND e.receivedAt >= :since
            GROUP BY lower(e.fromAddress)
            """)
    List<Object[]> countBySenderSince(@Param("userId") UUID userId, @Param("since") Instant since);

    /** [address, fromName, lastSeen] por variante de nombre: el service se queda con la más reciente. */
    @Query("""
            SELECT lower(e.fromAddress), e.fromName, MAX(e.receivedAt)
            FROM EmailMessage e
            WHERE e.user.id = :userId AND e.fromAddress IS NOT NULL AND e.fromName IS NOT NULL
            GROUP BY lower(e.fromAddress), e.fromName
            """)
    List<Object[]> nameCandidates(@Param("userId") UUID userId);

    /** Asuntos recientes de un remitente, para deducir empresa y tema. */
    @Query("""
            SELECT e.subject FROM EmailMessage e
            WHERE e.user.id = :userId
              AND lower(e.fromAddress) LIKE lower(concat('%', :address, '%'))
              AND e.subject IS NOT NULL AND e.subject <> ''
            ORDER BY e.receivedAt DESC
            """)
    List<String> findRecentSubjects(@Param("userId") UUID userId,
                                    @Param("address") String address,
                                    Pageable pageable);

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
    // "No leído" = el proveedor sigue diciendo UNREAD *y* el usuario nunca lo abrió acá.
    // `is_read` solo no alcanza: el sync lo pisa con lo que diga el proveedor, así que los
    // mails ya abiertos (visto) volverían a contar.
    String NOT_SEEN = " AND NOT EXISTS (SELECT 1 FROM Visto v WHERE v.user.id = :userId" +
            " AND v.providerEmailId = e.providerEmailId AND v.visto = true)";

    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isRead = false" + NOT_SEEN)
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
    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isRead = false" + NOT_SEEN +
            " AND e.labels LIKE '%IMPORTANT%'")
    long countUrgentesByUserId(@Param("userId") UUID userId);

    // PROXY: matches the existing "destacados" filter semantics (isStarred OR IMPORTANT) + unread.
    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isRead = false" + NOT_SEEN +
            " AND (e.isStarred = true OR e.labels LIKE '%IMPORTANT%')")
    long countDestacadosSinLeerByUserId(@Param("userId") UUID userId);

    // PROXY: sent emails are NOT synced (inbox+spam only), so a thread-based "no reply"
    // query would count everything. Unread received emails are the honest proxy for
    // "waiting for your response".
    @Query("SELECT COUNT(e) FROM EmailMessage e WHERE e.user.id = :userId AND e.isRead = false" + NOT_SEEN +
            " AND e.fromAddress IS NOT NULL AND e.fromAddress <> :userEmail")
    long countPendientesRespuestaByUserId(@Param("userId") UUID userId, @Param("userEmail") String userEmail);
}
