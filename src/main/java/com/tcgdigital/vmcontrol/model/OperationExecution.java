package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * OperationExecution - tracks a bulk VM operation (start/stop environment).
 */
@Entity
@Table(name = "operation_execution")
public class OperationExecution {

    @Id
    @Column(name = "execution_id", length = 36)
    private String executionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Column(name = "initiated_by_user_id", nullable = false, length = 36)
    private String initiatedByUserId;

    @Column(name = "started_at", nullable = false)
    private Timestamp startedAt = Timestamp.from(Instant.now());

    @Column(name = "completed_at")
    private Timestamp completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "total_targets", nullable = false)
    private Integer totalTargets = 0;

    @Column(name = "completed_targets", nullable = false)
    private Integer completedTargets = 0;

    @Column(name = "failed_targets", nullable = false)
    private Integer failedTargets = 0;

    @Column(name = "execution_plan", columnDefinition = "TEXT")
    private String executionPlan;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("sequencePosition ASC")
    private List<OperationDetail> details = new ArrayList<>();

    public OperationExecution() {
    }

    // Getters and Setters
    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public OperationType getOperationType() {
        if (operationType == null) return OperationType.START;
        String normalized = operationType.toUpperCase()
                .replace("_ENVIRONMENT", "")
                .replace("_VM", "")
                .replace("_GROUP", "");
        try {
            return OperationType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return OperationType.START;
        }
    }

    public void setOperationType(OperationType operationType) {
        this.operationType = operationType.name();
    }

    public ExecutionStatus getStatus() {
        if (status == null) return ExecutionStatus.PENDING;
        return switch (status.toLowerCase()) {
            case "in_progress" -> ExecutionStatus.IN_PROGRESS;
            case "completed" -> ExecutionStatus.COMPLETED;
            case "failed" -> ExecutionStatus.FAILED;
            case "cancelled" -> ExecutionStatus.CANCELLED;
            case "partial_success" -> ExecutionStatus.PARTIAL_SUCCESS;
            default -> ExecutionStatus.PENDING;
        };
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status.name().toLowerCase();
    }

    public String getInitiatedByUserId() {
        return initiatedByUserId;
    }

    public void setInitiatedByUserId(String initiatedByUserId) {
        this.initiatedByUserId = initiatedByUserId;
    }

    public Timestamp getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Timestamp startedAt) {
        this.startedAt = startedAt;
    }

    public Timestamp getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Timestamp completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getTotalTargets() {
        return totalTargets;
    }

    public void setTotalTargets(Integer totalTargets) {
        this.totalTargets = totalTargets;
    }

    public Integer getCompletedTargets() {
        return completedTargets;
    }

    public void setCompletedTargets(Integer completedTargets) {
        this.completedTargets = completedTargets;
    }

    public Integer getFailedTargets() {
        return failedTargets;
    }

    public void setFailedTargets(Integer failedTargets) {
        this.failedTargets = failedTargets;
    }

    public String getExecutionPlan() {
        return executionPlan;
    }

    public void setExecutionPlan(String executionPlan) {
        this.executionPlan = executionPlan;
    }

    public List<OperationDetail> getDetails() {
        return details;
    }

    public void setDetails(List<OperationDetail> details) {
        this.details = details;
    }

    public void incrementCompleted() {
        this.completedTargets++;
    }

    public void incrementFailed() {
        this.failedTargets++;
    }
}
