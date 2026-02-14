package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;

/**
 * OperationDetail - tracks individual VM/group operation within a bulk execution.
 * Matches existing database schema.
 */
@Entity
@Table(name = "operation_detail")
public class OperationDetail {

    @Id
    @Column(name = "detail_id", length = 36)
    private String detailId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private OperationExecution execution;

    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType = "vm"; // 'vm' or 'group'

    @Column(name = "target_id", nullable = false, length = 36)
    private String targetId;

    @Column(name = "target_name", nullable = false)
    private String targetName;

    @Column(nullable = false, length = 20)
    private String action; // 'start', 'stop'

    @Column(name = "sequence_position", nullable = false)
    private Integer sequencePosition;

    @Column(name = "depends_on_detail_ids", columnDefinition = "TEXT")
    private String dependsOnDetailIds; // JSON array

    @Column(nullable = false, length = 20)
    private String status = "pending"; // 'pending', 'waiting', 'in_progress', 'completed', 'failed', 'skipped'

    @Column(name = "started_at")
    private Timestamp startedAt;

    @Column(name = "completed_at")
    private Timestamp completedAt;

    @Column(name = "cloud_operation_id")
    private String cloudOperationId;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    // Transient fields for convenience
    @Transient
    private Vm vm;

    public OperationDetail() {
    }

    // Getters and Setters
    public String getDetailId() {
        return detailId;
    }

    public void setDetailId(String detailId) {
        this.detailId = detailId;
    }

    public OperationExecution getExecution() {
        return execution;
    }

    public void setExecution(OperationExecution execution) {
        this.execution = execution;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getSequencePosition() {
        return sequencePosition;
    }

    public void setSequencePosition(Integer sequencePosition) {
        this.sequencePosition = sequencePosition;
    }

    public String getDependsOnDetailIds() {
        return dependsOnDetailIds;
    }

    public void setDependsOnDetailIds(String dependsOnDetailIds) {
        this.dependsOnDetailIds = dependsOnDetailIds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getCloudOperationId() {
        return cloudOperationId;
    }

    public void setCloudOperationId(String cloudOperationId) {
        this.cloudOperationId = cloudOperationId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Vm getVm() {
        return vm;
    }

    public void setVm(Vm vm) {
        this.vm = vm;
    }

    // Convenience methods for status
    public boolean isPending() {
        return "pending".equals(status);
    }

    public boolean isInProgress() {
        return "in_progress".equals(status);
    }

    public boolean isCompleted() {
        return "completed".equals(status);
    }

    public boolean isFailed() {
        return "failed".equals(status);
    }
}

