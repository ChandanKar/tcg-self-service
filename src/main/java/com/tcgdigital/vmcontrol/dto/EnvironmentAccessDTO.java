package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;

import java.sql.Timestamp;

/**
 * DTO for EnvironmentAccess entity.
 */
public class EnvironmentAccessDTO {

    private String accessId;
    private String environmentId;
    private String environmentName;
    private String userId;
    private String userEmail;
    private String userDisplayName;
    private AccessLevel accessLevel;
    private AccessStatus status;
    private String grantedByUserId;
    private String grantedByUserName;
    private Timestamp grantedAt;
    private Timestamp expiresAt;
    private String notes;

    public EnvironmentAccessDTO() {
    }

    /**
     * Create DTO from EnvironmentAccess entity.
     */
    public static EnvironmentAccessDTO fromEntity(EnvironmentAccess access) {
        EnvironmentAccessDTO dto = new EnvironmentAccessDTO();
        dto.setAccessId(access.getAccessId());
        dto.setAccessLevel(access.getAccessLevel());
        dto.setStatus(access.getStatus());
        dto.setGrantedAt(access.getGrantedAt());
        dto.setExpiresAt(access.getExpiresAt());
        dto.setNotes(access.getNotes());

        if (access.getEnvironment() != null) {
            dto.setEnvironmentId(access.getEnvironment().getEnvironmentId());
            dto.setEnvironmentName(access.getEnvironment().getDisplayName());
        }

        if (access.getUser() != null) {
            dto.setUserId(access.getUser().getUserId());
            dto.setUserEmail(access.getUser().getEmail());
            dto.setUserDisplayName(access.getUser().getDisplayName());
        }

        if (access.getGrantedBy() != null) {
            dto.setGrantedByUserId(access.getGrantedBy().getUserId());
            dto.setGrantedByUserName(access.getGrantedBy().getDisplayName());
        }

        return dto;
    }

    // Getters and Setters
    public String getAccessId() {
        return accessId;
    }

    public void setAccessId(String accessId) {
        this.accessId = accessId;
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

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public AccessStatus getStatus() {
        return status;
    }

    public void setStatus(AccessStatus status) {
        this.status = status;
    }

    public String getGrantedByUserId() {
        return grantedByUserId;
    }

    public void setGrantedByUserId(String grantedByUserId) {
        this.grantedByUserId = grantedByUserId;
    }

    public String getGrantedByUserName() {
        return grantedByUserName;
    }

    public void setGrantedByUserName(String grantedByUserName) {
        this.grantedByUserName = grantedByUserName;
    }

    public Timestamp getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(Timestamp grantedAt) {
        this.grantedAt = grantedAt;
    }

    public Timestamp getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Timestamp expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

