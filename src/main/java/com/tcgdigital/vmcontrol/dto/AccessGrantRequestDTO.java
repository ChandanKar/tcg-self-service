package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Body for {@code POST /api/v1/access-grants} — an admin / env-admin granting access directly
 * to a user, at the environment level or to one or more groups within it.
 */
public class AccessGrantRequestDTO {

    @NotBlank(message = "User email is required")
    private String userEmail;

    @NotBlank(message = "Environment id is required")
    private String environmentId;

    @NotNull(message = "Access level is required")
    private AccessLevel accessLevel;

    @NotNull(message = "Scope type is required")
    private AccessScopeType scopeType;

    /** Required and non-empty when {@link #scopeType} is {@code GROUP}; ignored otherwise. */
    private List<String> groupIds;

    /** Optional: days the grant stays valid. Null = permanent. */
    private Integer durationDays;

    /** Optional: when re-granting over a time-boxed grant, true drops its expiry. */
    private Boolean clearExpiry;

    /** Optional reason, shown in the audit log. */
    private String notes;

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public AccessScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(AccessScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public List<String> getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(List<String> groupIds) {
        this.groupIds = groupIds;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public Boolean getClearExpiry() {
        return clearExpiry;
    }

    public void setClearExpiry(Boolean clearExpiry) {
        this.clearExpiry = clearExpiry;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
