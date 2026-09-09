package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for granting environment access directly (admin operation).
 */
public class GrantAccessDTO {

    @NotBlank(message = "User email is required")
    private String userEmail;

    @NotNull(message = "Access level is required")
    private AccessLevel accessLevel;

    /**
     * Optional: Number of days the access should be valid.
     * If null, access is permanent until revoked.
     */
    private Integer durationDays;

    /**
     * Optional: when granting over an existing time-boxed grant, set true to drop its expiry
     * and make it permanent. Ignored when {@code durationDays} is also given.
     */
    private Boolean clearExpiry;

    /**
     * Optional notes about the access grant.
     */
    private String notes;

    public GrantAccessDTO() {
    }

    public GrantAccessDTO(String userEmail, AccessLevel accessLevel, Integer durationDays, String notes) {
        this.userEmail = userEmail;
        this.accessLevel = accessLevel;
        this.durationDays = durationDays;
        this.notes = notes;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
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

