package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for granting environment access directly (admin operation).
 */
public class GrantAccessDTO {

    @NotBlank(message = "User ID is required")
    private String userId;

    @NotNull(message = "Access level is required")
    private AccessLevel accessLevel;

    /**
     * Optional: Number of days the access should be valid.
     * If null, access is permanent until revoked.
     */
    private Integer durationDays;

    /**
     * Optional notes about the access grant.
     */
    private String notes;

    public GrantAccessDTO() {
    }

    public GrantAccessDTO(String userId, AccessLevel accessLevel, Integer durationDays, String notes) {
        this.userId = userId;
        this.accessLevel = accessLevel;
        this.durationDays = durationDays;
        this.notes = notes;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

