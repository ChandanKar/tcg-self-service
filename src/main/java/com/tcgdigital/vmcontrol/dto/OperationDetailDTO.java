package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.OperationDetail;

import java.sql.Timestamp;

/**
 * DTO for individual VM operation detail.
 */
public class OperationDetailDTO {

    private String detailId;
    private String targetType;
    private String targetId;
    private String targetName;
    private String action;
    private String status;
    @com.tcgdigital.vmcontrol.dto.json.RedactProviderIds private String stageLabel;
    private Integer progressPercentage;
    private Integer statusChecksPassed;
    private Integer statusChecksTotal;
    private Integer sequencePosition;
    /** The step's VM group, for progress per group (U08-T01); null when the VM is gone. */
    private String groupId;
    private String groupName;
    private Timestamp startedAt;
    private Timestamp completedAt;
    @com.tcgdigital.vmcontrol.dto.json.RedactProviderIds private String errorMessage;
    /** The AWS request or update id; administrators only (U01-T01). */
    @com.tcgdigital.vmcontrol.dto.json.AdminOnly
    private String cloudOperationId;

    public OperationDetailDTO() {
    }

    public static OperationDetailDTO fromEntity(OperationDetail detail) {
        OperationDetailDTO dto = new OperationDetailDTO();
        dto.setDetailId(detail.getDetailId());
        dto.setTargetType(detail.getTargetType());
        dto.setTargetId(detail.getTargetId());
        dto.setTargetName(detail.getTargetName());
        dto.setAction(detail.getAction());
        dto.setStatus(detail.getStatus());
        dto.setStageLabel(detail.getStageLabel());
        dto.setProgressPercentage(detail.getProgressPercentage());
        dto.setStatusChecksPassed(detail.getStatusChecksPassed());
        dto.setStatusChecksTotal(detail.getStatusChecksTotal());
        dto.setSequencePosition(detail.getSequencePosition());
        dto.setStartedAt(detail.getStartedAt());
        dto.setCompletedAt(detail.getCompletedAt());
        dto.setErrorMessage(detail.getErrorMessage());
        dto.setCloudOperationId(detail.getCloudOperationId());
        if (detail.getVm() != null && detail.getVm().getGroup() != null) {
            var group = detail.getVm().getGroup();
            dto.setGroupId(group.getGroupId());
            dto.setGroupName(group.getDisplayName() != null ? group.getDisplayName() : group.getName());
        }
        return dto;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    // Getters and Setters
    public String getDetailId() {
        return detailId;
    }

    public void setDetailId(String detailId) {
        this.detailId = detailId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStageLabel() {
        return stageLabel;
    }

    public void setStageLabel(String stageLabel) {
        this.stageLabel = stageLabel;
    }

    public Integer getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(Integer progressPercentage) {
        this.progressPercentage = progressPercentage;
    }

    public Integer getStatusChecksPassed() {
        return statusChecksPassed;
    }

    public void setStatusChecksPassed(Integer statusChecksPassed) {
        this.statusChecksPassed = statusChecksPassed;
    }

    public Integer getStatusChecksTotal() {
        return statusChecksTotal;
    }

    public void setStatusChecksTotal(Integer statusChecksTotal) {
        this.statusChecksTotal = statusChecksTotal;
    }

    public Integer getSequencePosition() {
        return sequencePosition;
    }

    public void setSequencePosition(Integer sequencePosition) {
        this.sequencePosition = sequencePosition;
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

    public String getCloudOperationId() {
        return cloudOperationId;
    }

    public void setCloudOperationId(String cloudOperationId) {
        this.cloudOperationId = cloudOperationId;
    }
}

