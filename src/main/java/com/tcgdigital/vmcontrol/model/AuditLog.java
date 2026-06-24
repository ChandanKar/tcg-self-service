package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * AuditLog - immutable audit trail for all system actions.
 * Maps to the existing audit_log table from V1 schema.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @Column(name = "audit_id", length = 36)
    private String logId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "action_type", nullable = false, length = 50)
    private String action;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id", length = 36)
    private String targetId;

    @Column(name = "target_name", length = 255)
    private String targetName;

    @Column(name = "action_status", nullable = false, length = 20)
    private String actionStatus = "succeeded";

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    // Transient fields for convenience (not persisted)
    @Transient
    private String userEmail;

    @Transient
    private String userDisplayName;

    @Transient
    private String environmentName;

    public AuditLog() {
    }

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AuditLog log = new AuditLog();

        public Builder logId(String logId) {
            log.logId = logId;
            return this;
        }

        public Builder userId(String userId) {
            log.userId = userId;
            return this;
        }

        public Builder userEmail(String userEmail) {
            log.userEmail = userEmail;
            return this;
        }

        public Builder action(AuditAction action) {
            log.action = action != null ? action.name() : null;
            return this;
        }

        public Builder targetType(String targetType) {
            log.targetType = targetType;
            return this;
        }

        public Builder targetId(String targetId) {
            log.targetId = targetId;
            return this;
        }

        public Builder targetName(String targetName) {
            log.targetName = targetName;
            return this;
        }

        public Builder environmentId(String environmentId) {
            log.environmentId = environmentId;
            return this;
        }

        public Builder environmentName(String environmentName) {
            log.environmentName = environmentName;
            return this;
        }

        public Builder details(String details) {
            log.details = details;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            log.ipAddress = ipAddress;
            return this;
        }

        public Builder userAgent(String userAgent) {
            log.userAgent = userAgent;
            return this;
        }

        public Builder sessionId(String sessionId) {
            log.sessionId = sessionId;
            return this;
        }

        public Builder success(Boolean success) {
            log.actionStatus = Boolean.TRUE.equals(success) ? "succeeded" : "failed";
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            log.errorMessage = errorMessage;
            return this;
        }

        public AuditLog build() {
            return log;
        }
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

    public String getUserDisplayName() {
        return userDisplayName;
    }

    public void setUserDisplayName(String userDisplayName) {
        this.userDisplayName = userDisplayName;
    }

    public AuditAction getAction() {
        if (action == null) return null;
        try {
            return AuditAction.valueOf(action);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setAction(AuditAction action) {
        this.action = action != null ? action.name() : null;
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

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Boolean getSuccess() {
        return "succeeded".equalsIgnoreCase(actionStatus);
    }

    public void setSuccess(Boolean success) {
        this.actionStatus = Boolean.TRUE.equals(success) ? "succeeded" : "failed";
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

    public String getActionStatus() {
        return actionStatus;
    }

    public void setActionStatus(String actionStatus) {
        this.actionStatus = actionStatus;
    }
}
