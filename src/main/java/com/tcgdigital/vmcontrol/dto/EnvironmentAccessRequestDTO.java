package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;

import java.sql.Timestamp;

/**
 * DTO for EnvironmentAccessRequest entity.
 */
public class EnvironmentAccessRequestDTO {

    private String requestId;
    private String environmentId;
    private String environmentName;
    private String requesterId;
    private String requesterEmail;
    private String requesterDisplayName;
    private AccessLevel requestedAccessLevel;
    private String businessJustification;
    private Integer durationDays;
    private AccessRequestStatus status;
    private String reviewedByUserId;
    private String reviewedByUserName;
    private Timestamp reviewedAt;
    private String reviewDecisionNotes;
    private Timestamp createdAt;

    public EnvironmentAccessRequestDTO() {
    }

    /**
     * Create DTO from EnvironmentAccessRequest entity.
     */
    public static EnvironmentAccessRequestDTO fromEntity(EnvironmentAccessRequest request) {
        EnvironmentAccessRequestDTO dto = new EnvironmentAccessRequestDTO();
        dto.setRequestId(request.getRequestId());
        dto.setRequestedAccessLevel(request.getRequestedAccessLevel());
        dto.setBusinessJustification(request.getBusinessJustification());
        dto.setDurationDays(request.getDurationDays());
        dto.setStatus(request.getStatus());
        dto.setReviewedAt(request.getReviewedAt());
        dto.setReviewDecisionNotes(request.getReviewDecisionNotes());
        dto.setCreatedAt(request.getCreatedAt());

        if (request.getEnvironment() != null) {
            dto.setEnvironmentId(request.getEnvironment().getEnvironmentId());
            dto.setEnvironmentName(request.getEnvironment().getDisplayName());
        }

        if (request.getRequester() != null) {
            dto.setRequesterId(request.getRequester().getUserId());
            dto.setRequesterEmail(request.getRequester().getEmail());
            dto.setRequesterDisplayName(request.getRequester().getDisplayName());
        }

        if (request.getReviewedBy() != null) {
            dto.setReviewedByUserId(request.getReviewedBy().getUserId());
            dto.setReviewedByUserName(request.getReviewedBy().getDisplayName());
        }

        return dto;
    }

    // Getters and Setters
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public String getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(String requesterId) {
        this.requesterId = requesterId;
    }

    public String getRequesterEmail() {
        return requesterEmail;
    }

    public void setRequesterEmail(String requesterEmail) {
        this.requesterEmail = requesterEmail;
    }

    public String getRequesterDisplayName() {
        return requesterDisplayName;
    }

    public void setRequesterDisplayName(String requesterDisplayName) {
        this.requesterDisplayName = requesterDisplayName;
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

    public String getReviewedByUserId() {
        return reviewedByUserId;
    }

    public void setReviewedByUserId(String reviewedByUserId) {
        this.reviewedByUserId = reviewedByUserId;
    }

    public String getReviewedByUserName() {
        return reviewedByUserName;
    }

    public void setReviewedByUserName(String reviewedByUserName) {
        this.reviewedByUserName = reviewedByUserName;
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
}

