package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.OperationDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for OperationDetail entity.
 */
@Repository
public interface OperationDetailRepository extends JpaRepository<OperationDetail, String> {

    /**
     * Find details for an execution ordered by sequence.
     */
    List<OperationDetail> findByExecutionExecutionIdOrderBySequencePositionAsc(String executionId);

    /**
     * Find pending details for an execution.
     */
    List<OperationDetail> findByExecutionExecutionIdAndStatusOrderBySequencePositionAsc(String executionId, String status);

    /**
     * Fetch all completed details (with timing) for a given set of execution IDs.
     * Per-VM avg/min/max is computed in the service layer to avoid HQL dialect issues.
     */
    @Query("SELECT d FROM OperationDetail d WHERE d.execution.executionId IN :executionIds AND d.status = 'completed' AND d.startedAt IS NOT NULL AND d.completedAt IS NOT NULL")
    List<OperationDetail> findCompletedDetailsByExecutionIds(@Param("executionIds") List<String> executionIds);

    /**
     * Fetch completed details scoped to a specific set of VM IDs within a set of executions.
     * Used for group-level and VM-level estimates.
     */
    @Query("SELECT d FROM OperationDetail d WHERE d.execution.executionId IN :executionIds AND d.targetId IN :vmIds AND d.status = 'completed' AND d.startedAt IS NOT NULL AND d.completedAt IS NOT NULL")
    List<OperationDetail> findCompletedDetailsByExecutionIdsAndVmIds(
            @Param("executionIds") List<String> executionIds,
            @Param("vmIds") List<String> vmIds);
}

