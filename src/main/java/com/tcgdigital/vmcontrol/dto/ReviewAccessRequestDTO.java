package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * DTO for reviewing (approve/deny) an access request.
 */
public class ReviewAccessRequestDTO {

    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    private String notes;

    @Min(value = 1, message = AccessDurations.MIN_MESSAGE)
    @Max(value = AccessDurations.HARD_MAX_DAYS, message = AccessDurations.MAX_MESSAGE)
    private Integer durationDays;

    /** Approve with no expiry, whatever duration the requester asked for. */
    private Boolean clearExpiry;

    public ReviewAccessRequestDTO() {
    }

    public ReviewAccessRequestDTO(String notes, Integer durationDays) {
        this.notes = notes;
        this.durationDays = durationDays;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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
}
