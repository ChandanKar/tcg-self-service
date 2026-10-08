package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for OperationExecution entity.
 */
@Repository
public interface OperationExecutionRepository extends JpaRepository<OperationExecution, String> {

    /**
     * Find executions for an environment ordered by start time.
     */
    List<OperationExecution> findByEnvironmentEnvironmentIdOrderByStartedAtDesc(String environmentId);

    /**
     * Find recent executions for an environment (limited).
     */
    List<OperationExecution> findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(String environmentId);

    /**
     * Find executions by status.
     */
    List<OperationExecution> findByStatus(ExecutionStatus status);

    /**
     * Find active executions (pending or in progress).
     */
    @Query("SELECT e FROM OperationExecution e WHERE e.status = 'pending' OR e.status = 'in_progress' ORDER BY e.startedAt ASC")
    List<OperationExecution> findActiveExecutions();

    /**
     * Find active executions for an environment.
     */
    @Query("SELECT e FROM OperationExecution e WHERE e.environment.environmentId = :environmentId AND (e.status = 'pending' OR e.status = 'in_progress')")
    List<OperationExecution> findActiveExecutionsByEnvironmentId(String environmentId);

    /**
     * Check if environment has active operations.
     */
    @Query("SELECT COUNT(e) > 0 FROM OperationExecution e WHERE e.environment.environmentId = :environmentId AND (e.status = 'pending' OR e.status = 'in_progress')")
    boolean hasActiveOperations(String environmentId);

    /**
     * Fetch execution with environment initialized for async terminal-status handling.
     */
    @Query("SELECT e FROM OperationExecution e JOIN FETCH e.environment WHERE e.executionId = :executionId")
    Optional<OperationExecution> findByIdWithEnvironment(@Param("executionId") String executionId);

    /**
     * Find the last N completed executions for a given environment and operationType.
     * Pass PageRequest.of(0, 20) as pageable to limit to 20 results.
     * Duration stats are computed in the service layer to avoid HQL dialect issues.
     */
    @Query("SELECT e FROM OperationExecution e WHERE e.environment.environmentId = :environmentId AND e.operationType = :operationType AND e.status = 'completed' AND e.completedAt IS NOT NULL ORDER BY e.startedAt DESC")
    List<OperationExecution> findRecentCompletedByEnvironmentAndType(
            @Param("environmentId") String environmentId,
            @Param("operationType") String operationType,
            Pageable pageable);

    /**
     * Most recent executions a user started, newest first — the "VM operations" events in the
     * user's own activity feed.
     */
    @Query("SELECT oe FROM OperationExecution oe " +
           "JOIN FETCH oe.environment " +
           "WHERE oe.initiatedByUserId = :userId " +
           "ORDER BY oe.startedAt DESC")
    List<OperationExecution> findRecentByInitiator(@Param("userId") String userId, Pageable pageable);

    /**
     * Move an execution to {@code to} only if its current status is one of {@code from}
     * (lower-case stored values, see OperationExecution#statusValue). Returns 0 when another
     * writer (a cancel, a worker, recovery) got there first. Bumps the version so a stale entity
     * save fails fast (M8).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE OperationExecution e SET e.status = :to, " +
           "e.completedAt = COALESCE(:completedAt, e.completedAt), " +
           "e.errorMessage = COALESCE(:error, e.errorMessage), " +
           "e.version = e.version + 1 " +
           "WHERE e.executionId = :id AND e.status IN :from")
    int transitionStatus(@Param("id") String id,
                         @Param("from") Collection<String> from,
                         @Param("to") String to,
                         @Param("completedAt") Timestamp completedAt,
                         @Param("error") String error);

    /** Add to the step counters in one atomic UPDATE (workers finish steps in parallel). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE OperationExecution e SET e.completedTargets = e.completedTargets + :completed, " +
           "e.failedTargets = e.failedTargets + :failed, " +
           "e.skippedTargets = e.skippedTargets + :skipped, " +
           "e.version = e.version + 1 " +
           "WHERE e.executionId = :id")
    int incrementCounters(@Param("id") String id,
                          @Param("completed") int completed,
                          @Param("failed") int failed,
                          @Param("skipped") int skipped);

    /**
     * Mark the execution as alive (H12). Deliberately does not bump the version: it races with
     * nothing that matters, and runs on every provider poll tick.
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE OperationExecution e SET e.lastHeartbeatAt = :at, " +
           "e.executorId = COALESCE(:executorId, e.executorId) WHERE e.executionId = :id")
    int touchHeartbeat(@Param("id") String id, @Param("at") Timestamp at, @Param("executorId") String executorId);

    /** PENDING / IN_PROGRESS executions, of one environment or (null) all, with their environment. */
    @Query("SELECT e FROM OperationExecution e JOIN FETCH e.environment " +
           "WHERE (e.status = 'pending' OR e.status = 'in_progress') " +
           "AND (:environmentId IS NULL OR e.environment.environmentId = :environmentId)")
    List<OperationExecution> findActiveWithEnvironment(@Param("environmentId") String environmentId);

    /**
     * The environment's active executions as a locking read: unlike a plain read inside a
     * REPEATABLE READ transaction it sees rows committed after the transaction began (e.g. a
     * stale run just failed by recovery), and two concurrent starts serialize on it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OperationExecution e WHERE e.environment.environmentId = :environmentId " +
           "AND (e.status = 'pending' OR e.status = 'in_progress')")
    List<OperationExecution> findActiveForUpdate(@Param("environmentId") String environmentId);
}
