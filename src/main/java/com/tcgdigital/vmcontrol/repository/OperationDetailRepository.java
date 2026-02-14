package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.OperationDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
     * Count details by status for an execution.
     */
    long countByExecutionExecutionIdAndStatus(String executionId, String status);

    /**
     * Find all details for a target (VM or group).
     */
    List<OperationDetail> findByTargetIdOrderBySequencePositionAsc(String targetId);

    /**
     * Get max sequence position for an execution.
     */
    @Query("SELECT MAX(d.sequencePosition) FROM OperationDetail d WHERE d.execution.executionId = :executionId")
    Integer findMaxSequencePosition(String executionId);
}

