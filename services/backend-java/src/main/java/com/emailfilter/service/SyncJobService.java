package com.emailfilter.service;

import com.emailfilter.model.SyncJob;
import com.emailfilter.model.User;
import com.emailfilter.repository.SyncJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncJobService {

    private final SyncJobRepository repo;

    @Transactional
    public SyncJob start(UUID userId, String provider, String mode) {
        // Un job RUNNING que dejó de reportar progreso no puede bloquear el sync para siempre.
        repo.failStaleRunningForUser(userId, Instant.now().minus(10, ChronoUnit.MINUTES));
        repo.findActiveByUserId(userId).ifPresent(j -> {
            throw new IllegalStateException("Ya hay una sincronización en curso");
        });
        SyncJob job = SyncJob.builder()
                .user(User.builder().id(userId).build())
                .provider(provider).mode(mode)
                .status("RUNNING").fetched(0).total(0)
                .build();
        return repo.save(job);
    }

    @Transactional
    public void updateProgress(UUID jobId, int fetched, int total) {
        repo.updateProgress(jobId, fetched, total, Instant.now());
    }

    @Transactional
    public void complete(UUID jobId) {
        repo.updateStatus(jobId, "COMPLETED", null, Instant.now());
    }

    @Transactional
    public void fail(UUID jobId, String error) {
        repo.updateStatus(jobId, "FAILED", error, Instant.now());
    }

    /** Último job del usuario: la UI lo usa para no mostrar "listo" cuando en realidad falló. */
    @Transactional(readOnly = true)
    public java.util.Optional<SyncJob> lastForUser(UUID userId) {
        return repo.findTopByUserIdOrderByStartedAtDesc(userId);
    }

    /** Job todavía RUNNING, aunque el flag en memoria haya vencido. */
    @Transactional(readOnly = true)
    public boolean hasActive(UUID userId) {
        return repo.findActiveByUserId(userId).isPresent();
    }

    @PostConstruct
    public void reconcileOrphanedJobs() {
        // Al arrancar, ningún job puede seguir corriendo: los RUNNING son de la vida anterior del
        // proceso (se murió a mitad de sync) y bloqueaban todo sync nuevo hasta 10 min con
        // "Ya hay una sincronización en curso". El cutoff es ahora mismo, no una ventana.
        // shortcut: con varias instancias del backend, una marcaría FAILED el job de la otra.
        int updated = repo.failStaleRunningJobs(Instant.now());
        if (updated > 0) {
            log.warn("Se marcaron {} sync_jobs huérfanos como FAILED", updated);
        }
        purgeOldJobs();
    }

    /** Purga diaria: el auto-sync cada 2 min deja ~720 filas/día por usuario. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeOldJobs() {
        int purged = repo.deleteOlderThan(Instant.now().minus(7, ChronoUnit.DAYS));
        if (purged > 0) {
            log.info("Se purgaron {} sync_jobs de más de 7 días", purged);
        }
    }
}
