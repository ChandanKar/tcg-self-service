package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * A request to provision an environment from a template (E32-T01): who asked, the name and
 * parameters, its time-to-live, the review, the engine's record and resource, the environment it
 * produced and when that environment is destroyed.
 */
@Entity
@Table(name = "provisioning_request")
public class ProvisioningRequest {

    @Id
    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "requested_by_user_id", nullable = false, length = 36)
    private String requestedByUserId;

    @Column(name = "environment_name", nullable = false, length = 120)
    private String environmentName;

    /** The requester's parameter values as a JSON object. */
    @Column(name = "parameters", columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "justification", columnDefinition = "TEXT")
    private String justification;

    @Column(name = "ttl_hours", nullable = false)
    private Integer ttlHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProvisioningStatus status = ProvisioningStatus.PENDING_APPROVAL;

    @Column(name = "reviewed_by_user_id", length = 36)
    private String reviewedByUserId;

    @Column(name = "reviewed_at")
    private Timestamp reviewedAt;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "engine_record_id", length = 200)
    private String engineRecordId;

    @Column(name = "engine_resource_id", length = 200)
    private String engineResourceId;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "destroy_at")
    private Timestamp destroyAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Destroy warnings already sent (E32-T05). */
    @Column(name = "warned_day", nullable = false)
    private boolean warnedDay;

    @Column(name = "warned_hour", nullable = false)
    private boolean warnedHour;

    /** The engine record of the destroy run (E32-T05). */
    @Column(name = "destroy_record_id", length = 200)
    private String destroyRecordId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        if (requestId == null) requestId = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = new Timestamp(System.currentTimeMillis());
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getRequestedByUserId() { return requestedByUserId; }
    public void setRequestedByUserId(String requestedByUserId) { this.requestedByUserId = requestedByUserId; }
    public String getEnvironmentName() { return environmentName; }
    public void setEnvironmentName(String environmentName) { this.environmentName = environmentName; }
    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }
    public String getJustification() { return justification; }
    public void setJustification(String justification) { this.justification = justification; }
    public Integer getTtlHours() { return ttlHours; }
    public void setTtlHours(Integer ttlHours) { this.ttlHours = ttlHours; }
    public ProvisioningStatus getStatus() { return status; }
    public void setStatus(ProvisioningStatus status) { this.status = status; }
    public String getReviewedByUserId() { return reviewedByUserId; }
    public void setReviewedByUserId(String reviewedByUserId) { this.reviewedByUserId = reviewedByUserId; }
    public Timestamp getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Timestamp reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewNotes() { return reviewNotes; }
    public void setReviewNotes(String reviewNotes) { this.reviewNotes = reviewNotes; }
    public String getEngineRecordId() { return engineRecordId; }
    public void setEngineRecordId(String engineRecordId) { this.engineRecordId = engineRecordId; }
    public String getEngineResourceId() { return engineResourceId; }
    public void setEngineResourceId(String engineResourceId) { this.engineResourceId = engineResourceId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public Timestamp getDestroyAt() { return destroyAt; }
    public void setDestroyAt(Timestamp destroyAt) { this.destroyAt = destroyAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isWarnedDay() { return warnedDay; }
    public void setWarnedDay(boolean warnedDay) { this.warnedDay = warnedDay; }
    public boolean isWarnedHour() { return warnedHour; }
    public void setWarnedHour(boolean warnedHour) { this.warnedHour = warnedHour; }
    public String getDestroyRecordId() { return destroyRecordId; }
    public void setDestroyRecordId(String destroyRecordId) { this.destroyRecordId = destroyRecordId; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
}
