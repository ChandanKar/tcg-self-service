package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessInitiation;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
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
    private AccessScopeType scopeType;
    private String scopeId;
    private String scopeName;
    private AccessInitiation initiation;
    private String grantedByUserId;
    private String grantedByUserName;
    private Timestamp grantedAt;
    private Timestamp expiresAt;
    private Timestamp revokedAt;
    private String notes;

    public EnvironmentAccessDTO() {
    }

    /**
     * Create DTO from EnvironmentAccess entity. {@code scopeName} falls back to the
     * environment name for an ENVIRONMENT grant and the raw scope id for a GROUP grant —
     * pass {@link #fromEntity(EnvironmentAccess, String)} with the resolved group name to
     * get a human label for group grants.
     */
    public static EnvironmentAccessDTO fromEntity(EnvironmentAccess access) {
        return fromEntity(access, null);
    }

    public static EnvironmentAccessDTO fromEntity(EnvironmentAccess access, String resolvedScopeName) {
        EnvironmentAccessDTO dto = new EnvironmentAccessDTO();
        dto.setAccessId(access.getAccessId());
        dto.setAccessLevel(access.getAccessLevel());
        dto.setStatus(access.getStatus());
        dto.setScopeType(access.getScopeType());
        dto.setScopeId(access.getScopeId());
        dto.setInitiation(access.getInitiation());
        dto.setGrantedAt(access.getGrantedAt());
        dto.setExpiresAt(access.getExpiresAt());
        dto.setRevokedAt(access.getRevokedAt());
        dto.setNotes(access.getNotes());

        if (access.getEnvironment() != null) {
            dto.setEnvironmentId(access.getEnvironment().getEnvironmentId());
            dto.setEnvironmentName(access.getEnvironment().getDisplayName());
        }

        if (resolvedScopeName != null) {
            dto.setScopeName(resolvedScopeName);
        } else if (access.getScopeType() == AccessScopeType.ENVIRONMENT) {
            dto.setScopeName(dto.getEnvironmentName());
        } else {
            dto.setScopeName(access.getScopeId());
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

    public AccessScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(AccessScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    public String getScopeName() {
        return scopeName;
    }

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }

    public AccessInitiation getInitiation() {
        return initiation;
    }

    public void setInitiation(AccessInitiation initiation) {
        this.initiation = initiation;
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

    public Timestamp getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Timestamp revokedAt) {
        this.revokedAt = revokedAt;
    }
}
