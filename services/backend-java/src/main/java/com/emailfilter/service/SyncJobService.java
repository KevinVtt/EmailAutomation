package com.emailfilter.service;

import com.emailfilter.model.SyncJob;
import com.emailfilter.model.User;
import com.emailfilter.repository.SyncJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private static final long STALE_JOB_MINUTES = 20;

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

    @PostConstruct
    public void reconcileOrphanedJobs() {
        Instant cutoff = Instant.now().minus(STALE_JOB_MINUTES, ChronoUnit.MINUTES);
        int updated = repo.failStaleRunningJobs(cutoff);
        if (updated > 0) {
            log.warn("Se marcaron {} sync_jobs huérfanos como FAILED", updated);
        }
    }
}
