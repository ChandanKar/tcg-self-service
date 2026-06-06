package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;

import java.sql.Timestamp;
import java.util.List;

/**
 * DTO for operation execution response.
 */
public class OperationExecutionDTO {

    private String executionId;
    private String environmentId;
    private String environmentName;
    private OperationType operationType;
    private ExecutionStatus status;
    private String initiatedByUserId;
    private String initiatedByDisplayName;
    private Timestamp startedAt;
    private Timestamp completedAt;
    private String errorMessage;
    private Integer totalTargets;
    private Integer completedTargets;
    private Integer failedTargets;
    private List<OperationDetailDTO> details;
    private double progressPercentage;

    public OperationExecutionDTO() {
    }

    public static OperationExecutionDTO fromEntity(OperationExecution execution) {
        OperationExecutionDTO dto = new OperationExecutionDTO();
        dto.setExecutionId(execution.getExecutionId());
        dto.setEnvironmentId(execution.getEnvironment().getEnvironmentId());
        dto.setEnvironmentName(execution.getEnvironment().getName());
        dto.setOperationType(execution.getOperationType());
        dto.setStatus(execution.getStatus());
        dto.setInitiatedByUserId(execution.getInitiatedByUserId());
        dto.setStartedAt(execution.getStartedAt());
        dto.setCompletedAt(execution.getCompletedAt());
        dto.setErrorMessage(execution.getErrorMessage());
        dto.setTotalTargets(execution.getTotalTargets());
        dto.setCompletedTargets(execution.getCompletedTargets());
        dto.setFailedTargets(execution.getFailedTargets());

        // Calculate progress
        if (execution.getTotalTargets() > 0) {
            double progress = ((double) (execution.getCompletedTargets() + execution.getFailedTargets())
                    / execution.getTotalTargets()) * 100;
            dto.setProgressPercentage(Math.round(progress * 100.0) / 100.0);
        } else {
            dto.setProgressPercentage(0);
        }

        return dto;
    }

    /**
     * Create DTO from entity with user display name.
     */
    public static OperationExecutionDTO fromEntity(OperationExecution execution, String displayName) {
        OperationExecutionDTO dto = fromEntity(execution);
        dto.setInitiatedByDisplayName(displayName);
        return dto;
    }

    public static OperationExecutionDTO fromEntityWithDetails(OperationExecution execution) {
        OperationExecutionDTO dto = fromEntity(execution);
        if (execution.getDetails() != null) {
            dto.setDetails(execution.getDetails().stream()
                    .map(OperationDetailDTO::fromEntity)
                    .toList());
        }
        return dto;
    }

    /**
     * Create DTO from entity with details and user display name.
     */
    public static OperationExecutionDTO fromEntityWithDetails(OperationExecution execution, String displayName) {
        OperationExecutionDTO dto = fromEntityWithDetails(execution);
        dto.setInitiatedByDisplayName(displayName);
        return dto;
    }

    // Getters and Setters
    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public void setEnvironmentName(String environmentName) {
        this.environmentName = environmentName;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(OperationType operationType) {
        this.operationType = operationType;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public String getInitiatedByUserId() {
        return initiatedByUserId;
    }

    public void setInitiatedByUserId(String initiatedByUserId) {
        this.initiatedByUserId = initiatedByUserId;
    }

    /**
     * Alias for initiatedByUserId for frontend compatibility.
     */
    @JsonProperty("initiatedBy")
    public String getInitiatedBy() {
        return initiatedByUserId;
    }

    public String getInitiatedByDisplayName() {
        return initiatedByDisplayName;
    }

    public void setInitiatedByDisplayName(String initiatedByDisplayName) {
        this.initiatedByDisplayName = initiatedByDisplayName;
    }

    public Timestamp getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Timestamp startedAt) {
        this.startedAt = startedAt;
    }

    /**
     * Alias for startedAt for frontend compatibility (uses createdAt in history display).
     */
    @JsonProperty("createdAt")
    public Timestamp getCreatedAt() {
        return startedAt;
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

    public List<OperationDetailDTO> getDetails() {
        return details;
    }

    public void setDetails(List<OperationDetailDTO> details) {
        this.details = details;
    }

    public double getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(double progressPercentage) {
        this.progressPercentage = progressPercentage;
    }
}

