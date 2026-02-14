package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;

/**
 * EnvironmentAccessRequest - workflow for requesting access to an environment.
 * Users submit requests which are reviewed by admins or environment admins.
 */
@Entity
@Table(name = "environment_access_request")
public class EnvironmentAccessRequest {

    @Id
    @Column(name = "request_id", length = 36)
    private String requestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_user_id", nullable = false)
    private User requester;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_access_level", nullable = false, length = 20)
    private AccessLevel requestedAccessLevel;

    @Column(name = "business_justification", nullable = false, columnDefinition = "TEXT")
    private String businessJustification;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessRequestStatus status = AccessRequestStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Timestamp reviewedAt;

    @Column(name = "review_decision_notes", columnDefinition = "TEXT")
    private String reviewDecisionNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    @Column(name = "auto_expire_at")
    private Timestamp autoExpireAt;

    public EnvironmentAccessRequest() {
    }

    public EnvironmentAccessRequest(String requestId) {
        this.requestId = requestId;
    }

    /**
     * Factory method to create a new access request.
     */
    public static EnvironmentAccessRequest create(Environment environment, User requester,
                                                   AccessLevel requestedAccessLevel,
                                                   String businessJustification,
                                                   Integer durationDays) {
        EnvironmentAccessRequest request = new EnvironmentAccessRequest();
        request.setRequestId(java.util.UUID.randomUUID().toString());
        request.setEnvironment(environment);
        request.setRequester(requester);
        request.setRequestedAccessLevel(requestedAccessLevel);
        request.setBusinessJustification(businessJustification);
        request.setDurationDays(durationDays);
        request.setStatus(AccessRequestStatus.PENDING);
        return request;
    }

    /**
     * Approve this request.
     */
    public void approve(User reviewer, String notes) {
        this.status = AccessRequestStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = new Timestamp(System.currentTimeMillis());
        this.reviewDecisionNotes = notes;
    }

    /**
     * Deny this request.
     */
    public void deny(User reviewer, String notes) {
        this.status = AccessRequestStatus.DENIED;
        this.reviewedBy = reviewer;
        this.reviewedAt = new Timestamp(System.currentTimeMillis());
        this.reviewDecisionNotes = notes;
    }

    /**
     * Cancel this request (by the requester).
     */
    public void cancel() {
        this.status = AccessRequestStatus.CANCELLED;
    }

    /**
     * Check if this request is pending.
     */
    public boolean isPending() {
        return status == AccessRequestStatus.PENDING;
    }

    // Getters and Setters
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public User getRequester() {
        return requester;
    }

    public void setRequester(User requester) {
        this.requester = requester;
    }

    public AccessLevel getRequestedAccessLevel() {
        return requestedAccessLevel;
    }

    public void setRequestedAccessLevel(AccessLevel requestedAccessLevel) {
        this.requestedAccessLevel = requestedAccessLevel;
    }

    public String getBusinessJustification() {
        return businessJustification;
    }

    public void setBusinessJustification(String businessJustification) {
        this.businessJustification = businessJustification;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public AccessRequestStatus getStatus() {
        return status;
    }

    public void setStatus(AccessRequestStatus status) {
        this.status = status;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(User reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Timestamp getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Timestamp reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewDecisionNotes() {
        return reviewDecisionNotes;
    }

    public void setReviewDecisionNotes(String reviewDecisionNotes) {
        this.reviewDecisionNotes = reviewDecisionNotes;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Timestamp getAutoExpireAt() {
        return autoExpireAt;
    }

    public void setAutoExpireAt(Timestamp autoExpireAt) {
        this.autoExpireAt = autoExpireAt;
    }

    @Override
    public String toString() {
        return "EnvironmentAccessRequest{" +
                "requestId='" + requestId + '\'' +
                ", environmentId='" + (environment != null ? environment.getEnvironmentId() : null) + '\'' +
                ", requesterId='" + (requester != null ? requester.getUserId() : null) + '\'' +
                ", requestedAccessLevel=" + requestedAccessLevel +
                ", status=" + status +
                '}';
    }
}

