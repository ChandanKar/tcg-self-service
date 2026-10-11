package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.Set;

/** A start or runtime extension waiting for approval (E25-T04). */
@Entity
@Table(name = "approval_request")
public class ApprovalRequest {

    public enum Type { START, EXTEND }

    public enum Status { PENDING, APPROVED, DENIED, EXPIRED, CANCELLED, EXECUTED, FAILED }

    @Id
    @Column(name = "request_id", length = 36)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 10)
    private Type requestType;

    @Column(name = "environment_id", nullable = false, length = 36)
    private String environmentId;

    @Column(name = "requester_user_id", nullable = false, length = 36)
    private String requesterUserId;

    /** JSON: the StartOperationDTO for a start, {minutes, reason} for an extension. */
    @Column(name = "payload", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payload;

    /** e.g. "group web, 8 h". */
    @Column(name = "scope_summary", length = 255)
    private String scopeSummary;

    @Column(name = "estimated_cost", precision = 12, scale = 2)
    private BigDecimal estimatedCost;

    @Column(name = "requested_hours")
    private Integer requestedHours;

    /** Newline-separated reasons from the matched policies. */
    @Column(name = "reasons", columnDefinition = "TEXT")
    private String reasons;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_mode", nullable = false, length = 5)
    private ApprovalPolicy.ApproverMode approverMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "expires_at", nullable = false)
    private Timestamp expiresAt;

    @Column(name = "decided_at")
    private Timestamp decidedAt;

    @Column(name = "execution_id", length = 36)
    private String executionId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /** The approvers eligible when the request was made. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "approval_request_approver", joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "user_id", length = 36)
    private Set<String> approverUserIds = new LinkedHashSet<>();

    public ApprovalRequest() {
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Type getRequestType() { return requestType; }
    public void setRequestType(Type requestType) { this.requestType = requestType; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public String getRequesterUserId() { return requesterUserId; }
    public void setRequesterUserId(String requesterUserId) { this.requesterUserId = requesterUserId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getScopeSummary() { return scopeSummary; }
    public void setScopeSummary(String scopeSummary) { this.scopeSummary = scopeSummary; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public Integer getRequestedHours() { return requestedHours; }
    public void setRequestedHours(Integer requestedHours) { this.requestedHours = requestedHours; }
    public String getReasons() { return reasons; }
    public void setReasons(String reasons) { this.reasons = reasons; }
    public ApprovalPolicy.ApproverMode getApproverMode() { return approverMode; }
    public void setApproverMode(ApprovalPolicy.ApproverMode approverMode) { this.approverMode = approverMode; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Timestamp expiresAt) { this.expiresAt = expiresAt; }
    public Timestamp getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Timestamp decidedAt) { this.decidedAt = decidedAt; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public Long getVersion() { return version; }
    public Set<String> getApproverUserIds() { return approverUserIds; }
}
