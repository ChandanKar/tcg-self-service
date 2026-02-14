package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}

