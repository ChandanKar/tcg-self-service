package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;

/**
 * Body for {@code PATCH /api/v1/access-grants/{accessId}} — change an existing grant's level
 * and/or expiry. Every field is optional; a null field leaves that aspect of the grant as-is.
 */
public class UpdateAccessGrantDTO {

    private AccessLevel accessLevel;

    /** New validity window in days. Ignored when {@link #clearExpiry} is true. */
    private Integer durationDays;

    /** True drops the grant's expiry, making it permanent. */
    private Boolean clearExpiry;

    private String notes;

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
