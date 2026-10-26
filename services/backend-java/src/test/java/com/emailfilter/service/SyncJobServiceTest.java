package com.emailfilter.service;

import com.emailfilter.model.SyncJob;
import com.emailfilter.model.User;
import com.emailfilter.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncJobServiceTest {

    @Mock
    private SyncJobRepository repo;

    private SyncJobService service;

    @BeforeEach
    void setUp() {
        service = new SyncJobService(repo);
    }

    @Test
    void start_noActiveJob_createsRunningJob() {
        var userId = UUID.randomUUID();
        when(repo.findActiveByUserId(userId)).thenReturn(Optional.empty());
        var saved = SyncJob.builder()
                .id(UUID.randomUUID())
                .user(User.builder().id(userId).build())
                .provider("google")
                .mode("incremental")
                .status("RUNNING")
                .fetched(0)
                .total(0)
                .build();
        when(repo.save(any(SyncJob.class))).thenReturn(saved);

        var job = service.start(userId, "google", "incremental");

        assertEquals("RUNNING", job.getStatus());
        assertEquals("google", job.getProvider());
        assertEquals("incremental", job.getMode());
        assertEquals(0, job.getFetched());
        assertEquals(0, job.getTotal());
        verify(repo).failStaleRunningForUser(eq(userId), any(Instant.class));
        verify(repo).save(any(SyncJob.class));
    }

    @Test
    void start_activeJobExists_throwsIllegalStateException() {
        var userId = UUID.randomUUID();
        var active = SyncJob.builder()
                .id(UUID.randomUUID())
                .user(User.builder().id(userId).build())
                .provider("google")
                .mode("full")
                .status("RUNNING")
                .build();
        when(repo.findActiveByUserId(userId)).thenReturn(Optional.of(active));

        assertThrows(IllegalStateException.class, () -> service.start(userId, "google", "full"));
        verify(repo, never()).save(any(SyncJob.class));
    }

    @Test
    void updateProgress_delegatesToRepository() {
        var jobId = UUID.randomUUID();

        service.updateProgress(jobId, 25, 100);

        verify(repo).updateProgress(eq(jobId), eq(25), eq(100), any(Instant.class));
    }

    @Test
    void complete_updatesStatusToCompleted() {
        var jobId = UUID.randomUUID();

        service.complete(jobId);

        verify(repo).updateStatus(eq(jobId), eq("COMPLETED"), eq(null), any(Instant.class));
    }

    @Test
    void fail_updatesStatusToFailedWithError() {
        var jobId = UUID.randomUUID();

        service.fail(jobId, "boom");

        verify(repo).updateStatus(eq(jobId), eq("FAILED"), eq("boom"), any(Instant.class));
    }

    @Test
    void reconcileOrphanedJobs_marksStaleRunningAsFailed() {
        when(repo.failStaleRunningJobs(any(Instant.class))).thenReturn(3);

        service.reconcileOrphanedJobs();

        var cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repo).failStaleRunningJobs(cutoffCaptor.capture());
        // Al arrancar no puede seguir corriendo ningún job: el cutoff es ahora (margen de 5 s).
        var cutoff = cutoffCaptor.getValue();
        assertTrue(Math.abs(Instant.now().getEpochSecond() - cutoff.getEpochSecond()) <= 5);
    }

    @Test
    void reconcileOrphanedJobs_noStaleJobs_returnsZero() {
        when(repo.failStaleRunningJobs(any(Instant.class))).thenReturn(0);

        service.reconcileOrphanedJobs();

        verify(repo).failStaleRunningJobs(any(Instant.class));
    }
}