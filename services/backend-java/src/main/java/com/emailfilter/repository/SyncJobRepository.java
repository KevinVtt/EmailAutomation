package com.emailfilter.repository;

import com.emailfilter.model.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SyncJobRepository extends JpaRepository<SyncJob, UUID> {

    @Query("SELECT j FROM SyncJob j WHERE j.user.id = :userId AND j.status = 'RUNNING'")
    Optional<SyncJob> findActiveByUserId(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE SyncJob j SET j.fetched = :fetched, j.total = :total, j.updatedAt = :updatedAt WHERE j.id = :jobId")
    int updateProgress(@Param("jobId") UUID jobId, @Param("fetched") int fetched, @Param("total") int total, @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Transactional
    @Query("UPDATE SyncJob j SET j.status = :status, j.errorMessage = :errorMessage, j.updatedAt = :updatedAt WHERE j.id = :jobId")
    int updateStatus(@Param("jobId") UUID jobId, @Param("status") String status, @Param("errorMessage") String errorMessage, @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Transactional
    @Query("UPDATE SyncJob j SET j.status = 'FAILED', j.errorMessage = 'Stale job: backend restarted', j.updatedAt = CURRENT_TIMESTAMP WHERE j.status = 'RUNNING' AND j.startedAt < :cutoff")
    int failStaleRunningJobs(@Param("cutoff") Instant cutoff);
}
