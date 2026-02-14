package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;

import java.sql.Timestamp;

/**
 * DTO for audit log response.
 */
public class AuditLogDTO {

    private String logId;
    private String userId;
    private String userEmail;
    private AuditAction action;
    private String actionDisplay;
    private String targetType;
    private String targetId;
    private String targetName;
    private String environmentId;
    private String environmentName;
    private String details;
    private String oldValue;
    private String newValue;
    private Boolean success;
    private String errorMessage;
    private Timestamp createdAt;

    public AuditLogDTO() {
    }

    public static AuditLogDTO fromEntity(AuditLog auditLog) {
        AuditLogDTO dto = new AuditLogDTO();
        dto.setLogId(auditLog.getLogId());
        dto.setUserId(auditLog.getUserId());
        dto.setUserEmail(auditLog.getUserEmail());
        dto.setAction(auditLog.getAction());
        dto.setActionDisplay(formatActionName(auditLog.getAction()));
        dto.setTargetType(auditLog.getTargetType());
        dto.setTargetId(auditLog.getTargetId());
        dto.setTargetName(auditLog.getTargetName());
        dto.setEnvironmentId(auditLog.getEnvironmentId());
        dto.setEnvironmentName(auditLog.getEnvironmentName());
        dto.setDetails(auditLog.getDetails());
        // oldValue and newValue are not in V1 schema, set to null
        dto.setOldValue(null);
        dto.setNewValue(null);
        dto.setSuccess(auditLog.getSuccess());
        dto.setErrorMessage(auditLog.getErrorMessage());
        dto.setCreatedAt(auditLog.getCreatedAt());
        return dto;
    }

    private static String formatActionName(AuditAction action) {
        if (action == null) return null;
        // Convert ENUM_NAME to "Enum Name"
        String name = action.name().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }

    // Getters and Setters
    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public AuditAction getAction() {
        return action;
    }

    public void setAction(AuditAction action) {
        this.action = action;
    }

    public String getActionDisplay() {
        return actionDisplay;
    }

    public void setActionDisplay(String actionDisplay) {
        this.actionDisplay = actionDisplay;
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

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}

