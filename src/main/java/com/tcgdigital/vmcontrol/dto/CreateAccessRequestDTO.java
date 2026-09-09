package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO for creating an environment access request.
 */
public class CreateAccessRequestDTO {

    @NotNull(message = "Access level is required")
    private AccessLevel accessLevel;

    @NotBlank(message = "Business justification is required")
    @Size(min = 10, max = 1000, message = "Business justification must be between 10 and 1000 characters")
    private String businessJustification;

    /**
     * Optional: Number of days the access should be valid.
     * If null, access is permanent until revoked.
     */
    private Integer durationDays;

    /** ENVIRONMENT (default when null) or GROUP. */
    private AccessScopeType scopeType;

    /** Required when {@link #scopeType} is GROUP; the group must be in the target environment. */
    private String groupId;

    public CreateAccessRequestDTO() {
    }

    public CreateAccessRequestDTO(AccessLevel accessLevel, String businessJustification, Integer durationDays) {
        this.accessLevel = accessLevel;
        this.businessJustification = businessJustification;
        this.durationDays = durationDays;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
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

    public AccessScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(AccessScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
}

