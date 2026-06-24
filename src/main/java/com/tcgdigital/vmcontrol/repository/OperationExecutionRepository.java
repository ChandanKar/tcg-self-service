package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}

