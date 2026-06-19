package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Size;

/**
 * DTO for reviewing (approve/deny) an access request.
 */
public class ReviewAccessRequestDTO {

    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    private String notes;

    private Integer durationDays;

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
}

